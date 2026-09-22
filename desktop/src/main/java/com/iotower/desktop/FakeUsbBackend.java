package com.iotower.desktop;

import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A synthetic USB device for local end-to-end testing. Serves a real captured
 * descriptor dump (a Logitech F310 gamepad, testdata/simple-gamepad-descriptors.bin)
 * so the whole server loop can be validated without hardware (§12: start with a
 * dumb pad). Its {@link #deviceInfo()} identity is kept consistent with that
 * capture.
 *
 * <p>TODO: replay periodic input reports on the interrupt-IN endpoint (M5).
 */
public final class FakeUsbBackend implements UsbBackend {

    private static final String CAPTURE = "simple-gamepad-descriptors.bin";

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
        UsbTransfer t = new UsbTransfer(null);
        t.completion.complete(new UsbTransfer.Result(0, new byte[0], 0));
        return t;
    }

    @Override
    public void cancel(UsbTransfer transfer) { }

    @Override
    public void close() { }
}
