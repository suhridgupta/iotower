package com.iotower.android;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.util.Log;

import com.iotower.core.usb.DescriptorParser;
import com.iotower.core.usb.EndpointInfo;
import com.iotower.core.usb.EndpointMap;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M6 — Android claim &amp; read (Host-API spike, no network).
 *
 * <p>First contact with the Android USB Host API (architecture.md §2, §5, §8).
 * This class exists to de-risk the port before the network is wired in (M7): it
 * proves, on the real TV, that a userspace app can take a USB device away from
 * the kernel driver <b>without root</b> and read its input reports. It does not
 * touch the USB/IP protocol, the socket server, or the {@link
 * com.iotower.core.usb.UsbBackend} contract — that is M7.
 *
 * <p>The whole lifecycle for one device lives here: {@link #start} opens the
 * device, claims <em>every</em> interface with {@code forceClaim = true} (§2 —
 * the moment the kernel input driver lets go and the TV UI stops scrolling),
 * builds the endpoint map from the raw descriptors via the device-agnostic
 * {@code core} {@link DescriptorParser} (Invariant 1: route by endpoint, never
 * by VID/PID), and starts one reader thread per interrupt-IN endpoint logging
 * each report to Logcat. {@link #stop} tears it all down cleanly.
 *
 * <p>Invariant 2: this class is in {@code :android} and only <em>consumes</em>
 * {@code core}; {@code core} never imports Android. Invariant 6: every
 * null-returning Host API call is guarded so the spike aborts cleanly rather
 * than crashing a long-running process.
 */
public final class HostApiSpike {

    /** Single stable Logcat tag for the whole spike — {@code adb logcat -s IoTowerSpike}. */
    static final String TAG = "IoTowerSpike";

    private UsbDeviceConnection connection;
    private final List<UsbInterface> claimed = new ArrayList<>();
    private final List<ReaderThread> readers = new ArrayList<>();
    private volatile boolean running;

    /** True once {@link #start} has opened + claimed and reader threads are live. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Open {@code device}, claim all interfaces (forceClaim), build the endpoint
     * map, and start logging interrupt-IN reports. Returns a short human-readable
     * status line for the UI. Safe to call only when not already running.
     */
    public synchronized String start(UsbManager usbManager, UsbDevice device) {
        if (running) {
            return "Spike already running";
        }
        if (usbManager == null || device == null) {
            Log.e(TAG, "start: null UsbManager or device");
            return "No device";
        }

        Log.i(TAG, "Opening device " + describe(device));
        connection = usbManager.openDevice(device);
        if (connection == null) {
            // §8 / Invariant 6: openDevice can return null (permission lost, race).
            Log.e(TAG, "openDevice returned null — cannot claim");
            return "openDevice failed (no permission?)";
        }

        // §2: claimInterface(forceClaim=true) on EVERY interface detaches the
        // kernel driver. This is the step that stops the TV UI scrolling.
        int total = device.getInterfaceCount();
        int claimedCount = 0;
        for (int i = 0; i < total; i++) {
            UsbInterface intf = device.getInterface(i);
            boolean ok = connection.claimInterface(intf, /* forceClaim = */ true);
            Log.i(TAG, String.format(Locale.US,
                    "claimInterface #%d (class 0x%02X) forceClaim -> %s",
                    intf.getId(), intf.getInterfaceClass(), ok));
            if (ok) {
                claimed.add(intf);
                claimedCount++;
            }
        }
        Log.i(TAG, "Claimed " + claimedCount + "/" + total + " interfaces");

        // §5: build the endpoint map from descriptors, not assumptions. Reuse the
        // core parser so the real hardware validates the same code the desktop
        // harness proved. getRawDescriptors() may be null on some devices/levels.
        byte[] raw = connection.getRawDescriptors();
        if (raw == null || raw.length < 2) {
            Log.w(TAG, "getRawDescriptors() null/short (" + (raw == null ? "null" : raw.length)
                    + ") — core parser skipped; reading via Host-API enumeration only");
        } else {
            try {
                EndpointMap map = DescriptorParser.parse(raw);
                logEndpointMap(map);
                crossCheck(device, map);
            } catch (RuntimeException e) {
                Log.w(TAG, "DescriptorParser failed on live descriptors: " + e.getMessage());
            }
        }

        // Start one reader per interrupt-IN endpoint. We enumerate the Host API's
        // own UsbEndpoint objects (we need the real handle to queue a request);
        // routing is by (type == interrupt, direction == IN), never by device.
        running = true;
        int readerCount = 0;
        for (UsbInterface intf : claimed) {
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                        && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                    ReaderThread t = new ReaderThread(ep);
                    readers.add(t);
                    t.start();
                    readerCount++;
                    Log.i(TAG, String.format(Locale.US,
                            "reader started for interrupt-IN ep 0x%02X (maxPacket %d, interval %d)",
                            ep.getAddress(), ep.getMaxPacketSize(), ep.getInterval()));
                }
            }
        }

        if (readerCount == 0) {
            Log.w(TAG, "No interrupt-IN endpoint found — claimed but nothing to read");
            return "Claimed " + claimedCount + "/" + total + " intf, no interrupt-IN ep";
        }
        return "Reading " + readerCount + " interrupt-IN ep from " + describe(device);
    }

    /**
     * Cancel the reader loops, release every claimed interface, and close the
     * connection. Idempotent; the TV regains control of the device afterwards.
     */
    public synchronized void stop() {
        if (!running && connection == null) {
            return;
        }
        running = false;

        for (ReaderThread t : readers) {
            t.shutdown();
        }
        for (ReaderThread t : readers) {
            try {
                t.join(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        readers.clear();

        if (connection != null) {
            for (UsbInterface intf : claimed) {
                try {
                    connection.releaseInterface(intf);
                } catch (RuntimeException e) {
                    Log.w(TAG, "releaseInterface #" + intf.getId() + " failed: " + e.getMessage());
                }
            }
            connection.close();
            connection = null;
        }
        claimed.clear();
        Log.i(TAG, "Spike stopped — device released");
    }

    /** One interrupt-IN endpoint's read loop: queue a UsbRequest, wait, log the report. */
    private final class ReaderThread extends Thread {
        private final UsbEndpoint endpoint;
        private volatile boolean alive = true;
        private UsbRequest request;

        ReaderThread(UsbEndpoint endpoint) {
            super("iotower-spike-ep-" + Integer.toHexString(endpoint.getAddress()));
            this.endpoint = endpoint;
            setDaemon(true);
        }

        @Override
        public void run() {
            UsbDeviceConnection conn = connection;
            if (conn == null) {
                return;
            }
            request = new UsbRequest();
            if (!request.initialize(conn, endpoint)) {
                Log.e(TAG, String.format(Locale.US,
                        "UsbRequest.initialize failed for ep 0x%02X", endpoint.getAddress()));
                return;
            }
            int max = endpoint.getMaxPacketSize();
            ByteBuffer buf = ByteBuffer.allocate(Math.max(max, 1));

            while (alive && running) {
                buf.clear();
                // queue(ByteBuffer) is the non-deprecated form (API 26+; minSdk 28).
                if (!request.queue(buf)) {
                    Log.w(TAG, String.format(Locale.US,
                            "queue failed on ep 0x%02X — stopping reader", endpoint.getAddress()));
                    break;
                }
                UsbRequest done = conn.requestWait();
                if (done == null || !alive || !running) {
                    break;
                }
                if (done == request) {
                    int len = buf.position();
                    Log.i(TAG, String.format(Locale.US,
                            "ep 0x%02X report [%d] %s",
                            endpoint.getAddress(), len, hex(buf.array(), len)));
                }
            }

            try {
                request.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "request.close failed: " + e.getMessage());
            }
        }

        void shutdown() {
            alive = false;
            // Cancel unblocks the in-flight requestWait() (firmware-dependent; the
            // connection.close() in stop() is the backstop if this does not).
            UsbRequest r = request;
            if (r != null) {
                try {
                    r.cancel();
                } catch (RuntimeException e) {
                    Log.w(TAG, "request.cancel failed: " + e.getMessage());
                }
            }
        }
    }

    private static void logEndpointMap(EndpointMap map) {
        Log.i(TAG, "Endpoint map: " + map.interfaceCount() + " interface(s), "
                + map.size() + " endpoint(s) (from core DescriptorParser)");
        for (Map.Entry<Integer, EndpointInfo> e : map.all().entrySet()) {
            EndpointInfo ep = e.getValue();
            Log.i(TAG, String.format(Locale.US,
                    "  ep 0x%02X type=%s dir=%s maxPacket=%d interval=%d intf=%d",
                    ep.address, typeName(ep.type), ep.direction == 1 ? "IN" : "OUT",
                    ep.maxPacketSize, ep.interval, ep.interfaceNumber));
        }
    }

    /**
     * Confirm the core parser's interrupt-IN endpoint addresses agree with what
     * the Host API itself reports for the live device (a hardware cross-check of
     * the parser M2 proved against a captured dump).
     */
    private static void crossCheck(UsbDevice device, EndpointMap map) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_INT
                        || ep.getDirection() != UsbConstants.USB_DIR_IN) {
                    continue;
                }
                EndpointInfo parsed = map.get(ep.getAddress());
                if (parsed == null) {
                    Log.w(TAG, String.format(Locale.US,
                            "cross-check: Host API reports interrupt-IN ep 0x%02X that the "
                                    + "core parser did not find", ep.getAddress()));
                } else {
                    Log.i(TAG, String.format(Locale.US,
                            "cross-check OK: ep 0x%02X present in both (Host API + core parser)",
                            ep.getAddress()));
                }
            }
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case EndpointInfo.TYPE_CONTROL:   return "control";
            case EndpointInfo.TYPE_ISO:       return "iso";
            case EndpointInfo.TYPE_BULK:      return "bulk";
            case EndpointInfo.TYPE_INTERRUPT: return "interrupt";
            default:                          return "?" + type;
        }
    }

    private static String describe(UsbDevice device) {
        return String.format(Locale.US, "%04X:%04X %s",
                device.getVendorId(), device.getProductId(), device.getDeviceName());
    }

    private static String hex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            sb.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
            if (i + 1 < len) sb.append(' ');
        }
        return sb.toString();
    }
}
