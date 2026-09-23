package com.iotower.android;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import com.iotower.core.protocol.UsbIp;
import com.iotower.core.usb.DescriptorParser;
import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * {@link UsbBackend} backed by the Android USB Host API (§2, §5). This is the
 * only class that touches real USB hardware; everything above it is plain Java.
 *
 * <p>The M6 spike ({@link HostApiSpike}) proved the open +
 * {@code claimInterface(intf, forceClaim=true)} + interrupt-IN read path on the
 * real TV, standalone and without the network (M6 gate MET, 2026-09-23). This
 * class mirrors that proven Host-API usage but fills out the full {@link
 * UsbBackend} contract so the device-agnostic {@code UsbIpServer} /
 * {@code TransferEngine} can run behind it inside {@link ServerService} (M7).
 *
 * <p><b>Ownership.</b> {@link #open} performs the open + forceClaim(every
 * interface) + endpoint-map build and starts the single dispatcher thread
 * before returning a ready backend, or {@code null} on failure (Invariant 6).
 * The private constructor is only ever called from {@link #open}.
 *
 * <p><b>Routing.</b> {@code submit} looks up the real {@code UsbEndpoint}
 * purely by {@code endpointAddress} against the claim-time map built from the
 * device's own descriptors — never by VID/PID or device identity (Invariant
 * 1).
 *
 * <p><b>Dispatcher thread.</b> Exactly one thread may call {@code
 * UsbDeviceConnection.requestWait()} on a connection (Android Host API
 * constraint), so a single daemon thread owns it here, in a loop, for the
 * lifetime of the backend. It uses the timeout form ({@code requestWait(200)}, API 26+,
 * milliseconds) so it polls the {@code running} flag rather than
 * relying solely on {@link #close()}'s {@code connection.close()} backstop to
 * unblock a parked wait (PRD Risk 3).
 */
public final class AndroidUsbBackend implements UsbBackend {

    /** {@code -ECONNRESET} — matches {@code TransferEngine}/{@code FakeUsbBackend}. */
    private static final int STATUS_ECONNRESET = -104;

    /**
     * USB full-speed, matching what {@code FakeUsbBackend} advertises. The Host
     * API exposes no device-speed getter; this is believed cosmetic (the vhci
     * client re-reads descriptors after import and the kernel re-derives
     * topology) but is a guess — flagged as an open question (PRD Risk 1).
     */
    private static final int SPEED = 2;

    /** Poll interval for the dispatcher's {@code requestWait} timeout form. */
    private static final long DISPATCH_POLL_MS = 200;

    private final UsbDeviceConnection connection;
    private final List<UsbInterface> claimedInterfaces;
    private final Map<Integer, UsbEndpoint> endpoints;
    private final Thread dispatcher;

    private volatile boolean running;

    /**
     * Open {@code device}, claim every interface (forceClaim, §2), build the
     * address&#8594;endpoint map, and start the dispatcher thread. Mirrors {@code
     * HostApiSpike.start}. Returns {@code null} on failure (Invariant 6 / §8:
     * permission lost or race) — the caller ({@link ServerService}) must not
     * crash, just report the failure.
     */
    public static AndroidUsbBackend open(UsbManager usbManager, UsbDevice device) {
        if (usbManager == null || device == null) {
            return null;
        }

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            // §8 / Invariant 6: openDevice can return null (permission lost, race).
            return null;
        }

        List<UsbInterface> claimedInterfaces = new ArrayList<>();
        Map<Integer, UsbEndpoint> endpoints = new HashMap<>();

        int total = device.getInterfaceCount();
        for (int i = 0; i < total; i++) {
            UsbInterface intf = device.getInterface(i);
            // §2: forceClaim on EVERY interface detaches the kernel driver.
            if (connection.claimInterface(intf, /* forceClaim = */ true)) {
                claimedInterfaces.add(intf);
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    endpoints.put(ep.getAddress(), ep);
                }
            }
        }

        return new AndroidUsbBackend(connection, claimedInterfaces, endpoints);
    }

    private AndroidUsbBackend(UsbDeviceConnection connection, List<UsbInterface> claimedInterfaces,
            Map<Integer, UsbEndpoint> endpoints) {
        this.connection = connection;
        this.claimedInterfaces = claimedInterfaces;
        this.endpoints = endpoints;
        this.running = true;
        this.dispatcher = new Thread(this::dispatchLoop, "iotower-usb-dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    @Override
    public byte[] rawDescriptors() {
        return connection.getRawDescriptors();
    }

    @Override
    public DeviceInfo deviceInfo() {
        byte[] raw = connection.getRawDescriptors();
        if (raw == null) {
            // Invariant 6: guard the null-returning Host API call. UsbIpServer
            // already treats a null deviceInfo() as "no descriptors — close"
            // (see handleDevlist/handleImport), so null is the correct failure
            // signal here, not an exception.
            return null;
        }
        try {
            return DescriptorParser.parseDeviceInfo(raw, SPEED);
        } catch (RuntimeException e) {
            // Malformed/short descriptors: same null-on-failure contract.
            return null;
        }
    }

    @Override
    public int controlTransfer(int requestType, int request, int value, int index,
                               byte[] buffer, int length, int timeoutMillis) {
        return connection.controlTransfer(requestType, request, value, index,
                buffer, length, timeoutMillis);
    }

    @Override
    public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
        UsbEndpoint endpoint = endpoints.get(endpointAddress);
        if (endpoint == null) {
            // Unknown endpoint — the engine treats a null return as -ECONNRESET
            // (TransferEngine.submit). Invariant 6.
            return null;
        }

        UsbRequest request = new UsbRequest();
        if (!request.initialize(connection, endpoint)) {
            // Invariant 6: guard the null/false-returning Host API call.
            return null;
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        UsbTransfer transfer = new UsbTransfer(request);
        request.setClientData(new Pending(transfer, byteBuffer, direction, length));

        // queue(ByteBuffer) is the non-deprecated form (API 26+; minSdk 28).
        if (!request.queue(byteBuffer)) {
            // Invariant 6: do not desync the stream — complete the transfer
            // instead of leaving it hanging, and release the now-unused request.
            transfer.completion.complete(new UsbTransfer.Result(STATUS_ECONNRESET, null, 0));
            closeQuietly(request);
            return transfer;
        }

        return transfer;
    }

    @Override
    public void cancel(UsbTransfer transfer) {
        if (transfer == null || transfer.handle == null) {
            return;
        }
        // Resolve the completion here, best-effort: the dispatcher may also see
        // this UsbRequest come back through requestWait() after cancel(), but by
        // then completion.isDone() is already true and it is a harmless no-op —
        // the engine's atomic inflight.remove (not this completion) is the real
        // arbiter between a cancel and a race with a normal completion.
        if (!transfer.completion.isDone()) {
            transfer.completion.complete(new UsbTransfer.Result(STATUS_ECONNRESET, null, 0));
        }
        try {
            ((UsbRequest) transfer.handle).cancel();
        } catch (RuntimeException e) {
            // best-effort, per HostApiSpike.ReaderThread.shutdown (Invariant 6:
            // a failed cancel must not kill the session).
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            // The dispatcher polls `running` at most every DISPATCH_POLL_MS, so
            // this bounded join succeeds well before it; connection.close()
            // below is still the backstop for a wait that firmware does not
            // unblock on its own (PRD Risk 3).
            dispatcher.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (UsbInterface intf : claimedInterfaces) {
            try {
                connection.releaseInterface(intf);
            } catch (RuntimeException e) {
                // best-effort teardown, per HostApiSpike.stop.
            }
        }
        connection.close();
    }

    /** The single thread allowed to call {@code connection.requestWait()} (§5.1). */
    private void dispatchLoop() {
        while (running) {
            UsbRequest request;
            try {
                request = connection.requestWait(DISPATCH_POLL_MS);
            } catch (TimeoutException e) {
                continue; // nothing completed within the poll window; recheck `running`.
            } catch (RuntimeException e) {
                // Invariant 6: a bad wait must not kill the dispatcher mid-session
                // (e.g. a torn-down connection on some Host API versions).
                continue;
            }
            if (request == null) {
                continue; // connection closed elsewhere; recheck `running` and exit.
            }
            completeRequest(request);
        }
    }

    /** Matches a completed {@code UsbRequest} back to its {@link UsbTransfer} and completes it. */
    private void completeRequest(UsbRequest request) {
        Object clientData = request.getClientData();
        if (!(clientData instanceof Pending)) {
            // Not one of ours — nothing to correlate.
            closeQuietly(request);
            return;
        }
        Pending pending = (Pending) clientData;
        if (!pending.transfer.completion.isDone()) {
            byte[] data = null;
            int actualLength;
            if (pending.direction == UsbIp.DIR_IN) {
                // The ByteBuffer's position after queue()+requestWait() gives the
                // actual bytes transferred in (same pattern as HostApiSpike.ReaderThread).
                actualLength = pending.buffer.position();
                data = Arrays.copyOf(pending.buffer.array(), actualLength);
            } else {
                // OUT: nothing to read back; actual length is what we sent.
                actualLength = pending.length;
            }
            pending.transfer.completion.complete(new UsbTransfer.Result(0, data, actualLength));
        }
        closeQuietly(request);
    }

    private static void closeQuietly(UsbRequest request) {
        try {
            request.close();
        } catch (RuntimeException e) {
            // best-effort
        }
    }

    /** Correlates a queued {@link UsbRequest} back to its {@link UsbTransfer} and buffer. */
    private static final class Pending {
        final UsbTransfer transfer;
        final ByteBuffer buffer;
        final int direction;
        final int length;

        Pending(UsbTransfer transfer, ByteBuffer buffer, int direction, int length) {
            this.transfer = transfer;
            this.buffer = buffer;
            this.direction = direction;
            this.length = length;
        }
    }
}
