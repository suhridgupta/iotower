package com.iotower.desktop;

import com.iotower.core.protocol.UsbIp;
import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A synthetic USB device for local end-to-end testing. Serves a real captured
 * descriptor dump (a Logitech F310 gamepad, testdata/simple-gamepad-descriptors.bin)
 * so the whole server loop can be validated without hardware (§12: start with a
 * dumb pad). Its {@link #deviceInfo()} identity is kept consistent with that
 * capture.
 *
 * <p>For M5 it also <b>replays scripted input reports</b> on the interrupt-IN
 * endpoint ({@code 0x81}): each IN {@link #submit} completes after the endpoint
 * poll interval with the next 20-byte XInput frame from a cycling script
 * (toggling button A, sweeping the left-stick X axis). The stock vhci client
 * keeps re-submitting, so a steady report stream flows and {@code evtest} on the
 * PC shows moving axes/buttons with nothing plugged in — the M5 L2 gate. OUT
 * transfers (e.g. rumble on {@code 0x02}) are accepted and dropped; the fake has
 * nothing to actuate. The server never sees any of this — it stays
 * device-agnostic.
 */
public final class FakeUsbBackend implements UsbBackend {

    private static final String CAPTURE = "simple-gamepad-descriptors.bin";

    /** Interrupt-IN endpoint of the F310 capture (0x81, interval 4 — see RealCaptureTest). */
    private static final int EP_INTERRUPT_IN = 0x81;
    /** Poll interval used to pace the scripted report stream (ms). */
    private static final int IN_INTERVAL_MS = 4;
    /** Length of an XInput input report. */
    private static final int REPORT_LEN = 20;
    /** {@code -ECONNRESET}: status for a report transfer terminated by cancel. */
    private static final int STATUS_ECONNRESET = -104;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fake-reports");
                t.setDaemon(true);
                return t;
            });
    /** Drives the scripted report cycle; advances once per delivered report. */
    private final AtomicInteger frame = new AtomicInteger();
    /** In-flight IN transfers -> their scheduled completion, so {@link #cancel} can abort. */
    private final java.util.Map<UsbTransfer, ScheduledFuture<?>> pending =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Walk up from the working dir to find {@code testdata/<name>}. */
    private static Path locateTestData(String name) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("testdata").resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate testdata/" + name
                + " (searched upward from " + Paths.get("").toAbsolutePath()
                + "); capture it per testdata/README.md §1");
    }

    @Override
    public byte[] rawDescriptors() {
        try {
            return Files.readAllBytes(locateTestData(CAPTURE));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read captured descriptors " + CAPTURE, e);
        }
    }

    @Override
    public DeviceInfo deviceInfo() {
        // Matches testdata/simple-gamepad-descriptors.bin (F310, XInput mode).
        return new DeviceInfo(
                0x046d, 0xc21d, 0x4014,   // idVendor, idProduct, bcdDevice
                0xff, 0xff, 0xff,         // bDeviceClass/SubClass/Protocol (vendor-specific)
                1, 1, 1,                  // bConfigurationValue, bNumConfigurations, bNumInterfaces
                /* speed */ 2);           // USB full speed
    }

    // GET_DESCRIPTOR request type/request per the USB spec (device-to-host, standard,
    // device recipient) — generic protocol constants, not device-specific.
    private static final int REQ_TYPE_GET_DESCRIPTOR = 0x80;
    private static final int REQUEST_GET_DESCRIPTOR = 0x06;
    private static final int DESC_TYPE_DEVICE = 0x01;
    private static final int DESC_TYPE_CONFIGURATION = 0x02;

    @Override
    public int controlTransfer(int requestType, int request, int value, int index,
                               byte[] buffer, int length, int timeoutMillis) {
        if (requestType == REQ_TYPE_GET_DESCRIPTOR && request == REQUEST_GET_DESCRIPTOR) {
            int descriptorType = (value >> 8) & 0xFF;
            byte[] blob = rawDescriptors();

            if (descriptorType == DESC_TYPE_DEVICE) {
                int deviceLen = blob[0] & 0xFF; // bLength of the device descriptor
                return copyDescriptor(blob, 0, deviceLen, buffer, length);
            }
            if (descriptorType == DESC_TYPE_CONFIGURATION) {
                int deviceLen = blob[0] & 0xFF;
                // wTotalLength is the LE u16 at offset 2 of the configuration descriptor.
                int configOffset = deviceLen;
                int totalLength = (blob[configOffset + 2] & 0xFF)
                        | ((blob[configOffset + 3] & 0xFF) << 8);
                return copyDescriptor(blob, configOffset, totalLength, buffer, length);
            }
        }
        // Everything else (strings, etc.) stalls — the fake stands in for a real
        // device; the server itself stays device-agnostic (it never sees this logic).
        return -1;
    }

    /** Copies {@code min(available, requestedLength)} bytes of a descriptor into {@code buffer}. */
    private static int copyDescriptor(byte[] blob, int offset, int available, byte[] buffer,
            int requestedLength) {
        int n = Math.min(Math.min(available, requestedLength), buffer.length);
        System.arraycopy(blob, offset, buffer, 0, n);
        return n;
    }

    @Override
    public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
        if (direction == UsbIp.DIR_IN && endpointAddress == EP_INTERRUPT_IN) {
            // Complete after the poll interval with the next scripted report, so the
            // client's re-submits produce a continuous input stream (the L2 gate).
            UsbTransfer t = new UsbTransfer(null);
            ScheduledFuture<?> f = scheduler.schedule(() -> {
                if (t.completion.isDone()) {
                    return; // cancelled before it fired
                }
                byte[] report = nextReport();
                t.completion.complete(new UsbTransfer.Result(0, report, report.length));
            }, IN_INTERVAL_MS, TimeUnit.MILLISECONDS);
            pending.put(t, f);
            t.completion.whenComplete((r, e) -> pending.remove(t));
            return t;
        }
        // OUT (rumble/LED/FFB) and anything else: accept and drop — nothing to actuate.
        UsbTransfer t = new UsbTransfer(null);
        t.completion.complete(new UsbTransfer.Result(0, null, length));
        return t;
    }

    @Override
    public void cancel(UsbTransfer transfer) {
        if (transfer == null) {
            return;
        }
        ScheduledFuture<?> f = pending.remove(transfer);
        if (f != null) {
            f.cancel(false);
        }
        if (!transfer.completion.isDone()) {
            transfer.completion.complete(new UsbTransfer.Result(STATUS_ECONNRESET, null, 0));
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /** Builds the next 20-byte XInput report: toggles button A and sweeps left-stick X. */
    private byte[] nextReport() {
        int i = frame.getAndIncrement();
        byte[] r = new byte[REPORT_LEN];
        r[0] = 0x00;            // report type
        r[1] = 0x14;            // report length (20)
        r[2] = (byte) ((i % 2 == 0) ? 0x10 : 0x00); // buttons low: A pressed on even frames
        r[3] = 0x00;            // buttons high
        r[4] = 0x00;            // left trigger
        r[5] = 0x00;            // right trigger
        short lx = (short) (i * 4096); // left-stick X sweeps the full s16 range, wrapping
        r[6] = (byte) (lx & 0xFF);
        r[7] = (byte) ((lx >> 8) & 0xFF);
        return r;
    }
}
