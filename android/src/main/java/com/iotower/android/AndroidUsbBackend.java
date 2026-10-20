package com.iotower.android;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import com.iotower.core.protocol.UsbIp;
import com.iotower.core.usb.DescriptorParser;
import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link UsbBackend} backed by the Android USB Host API (§2, §5). This is the
 * only class that touches real USB hardware; everything above it is plain Java.
 *
 * <p><b>Read path (M7b — demand-driven):</b> interrupt/bulk-IN reads use the
 * <em>synchronous</em> {@link UsbDeviceConnection#bulkTransfer} (it returns the
 * byte count, or {@code -1} on error/timeout, so error is distinguishable from
 * empty — which {@code UsbRequest}/{@code requestWait} hid), but — unlike the
 * earlier free-running experiment — a read is issued <em>only when a client IN
 * URB is waiting</em>, and each read is bound 1:1 to the head URB. One worker
 * thread per IN endpoint owns all reads for that endpoint (single-threaded
 * endpoint access) and a FIFO of outstanding {@link UsbTransfer} URBs:
 * <ul>
 *   <li>{@code n > 0}: complete the head URB with the report;</li>
 *   <li>no data - an {@code n == 0}, or an {@code n < 0} that took ~the full
 *       {@link #READ_TIMEOUT_MS} (Android cannot tell a timeout from a stall by
 *       return code, so a full-window {@code -1} is a no-data poll): keep the URB
 *       queued and re-poll; it legitimately pends until data or a client
 *       {@code CMD_UNLINK} (m7d - this removed the ~24 s first-attach stall and the
 *       mid-stream dips, both idle timeouts misread as errors);</li>
 *   <li>immediate {@code n < 0}: a real endpoint error - rate-limited
 *       {@code CLEAR_FEATURE(ENDPOINT_HALT)} recovery, else complete the head URB
 *       with {@code -EPIPE} and back off. Sustained immediate errors whose
 *       halt-clear also fails mean the device is gone: the readers park and submits
 *       fail fast with {@code -ESHUTDOWN} (m7d), surfaced via {@link #isDeviceGone()}
 *       for the M8 reset path.</li>
 * </ul>
 * OUT uses {@code bulkTransfer} on a worker pool; ep0 uses
 * {@link #controlTransfer}.
 *
 * <p><b>Endpoint-halt handling (M7c):</b> the Android framework already
 * {@code SET_CONFIGURATION}s the device when we claim it at {@link #open}, so
 * re-forwarding the client's {@code SET_CONFIGURATION} as a raw control transfer
 * resets/halts the endpoints; {@link #controlTransfer} therefore no-op-ACKs it
 * instead of forwarding. As a backstop, an IN read error triggers a rate-limited
 * {@code CLEAR_FEATURE(ENDPOINT_HALT)} on that endpoint so a subsequent read can
 * recover. Both decide by standard request number only — no VID/PID (Invariant 1).
 *
 * <p>Device-agnostic (Invariant 1); {@code core} stays
 * Android-free (Invariant 2); cancel/close report {@code -ECONNRESET}
 * (Invariant 6). Diagnostics are gated behind {@link #DEBUG}
 * (see bugs/m7-interrupt-in-empty-reports.md and prds/m7b-interrupt-in-read-path.md).
 */
public final class AndroidUsbBackend implements UsbBackend {

    private static final String TAG = "IoTowerBackend";

    /** Toggle for the verbose M7b URB-lifecycle diagnostics. Flip to false to quiet. */
    private static final boolean DEBUG = true;

    /** {@code -ECONNRESET}: a URB terminated by cancel/close. */
    private static final int STATUS_ECONNRESET = -104;
    /** {@code -EPIPE}: a device/endpoint read error (USB stall / endpoint error). */
    private static final int STATUS_EPIPE = -32;
    /** m7d: {@code -ESHUTDOWN} - the device is gone (unplugged / re-enumerating). */
    private static final int STATUS_ESHUTDOWN = -108;

    private static final int OUT_TIMEOUT_MS = 1000;
    /** IN read timeout: bounds how often each reader rechecks state when a URB is pending. */
    private static final int READ_TIMEOUT_MS = 200;
    /** Backoff after a read error, so a stalled endpoint cannot busy-spin. */
    private static final int ERROR_BACKOFF_MS = 10;

    /**
     * m7c: how often to attempt ENDPOINT_HALT recovery while an IN endpoint keeps
     * erroring. Rate-limits the control transfer so ep0 is not flooded (the first
     * error fires immediately; then at most once per interval).
     */
    private static final int HALT_CLEAR_INTERVAL_MS = 1000;

    /**
     * m7d: a {@code bulkTransfer} returning {@code -1} only after about the full
     * {@link #READ_TIMEOUT_MS} window is a no-data poll, not a stall - Android
     * cannot distinguish the two by return code. At/above this floor the read is a
     * benign timeout (keep the URB pending, re-poll); below it an immediate
     * {@code -1} is a genuine endpoint error / gone device.
     */
    private static final int TIMEOUT_FLOOR_MS = 150;

    /**
     * m7d: consecutive immediate IN errors that, together with a failing
     * {@code CLEAR_FEATURE(ENDPOINT_HALT)} (ep0 itself erroring), mark the device
     * gone so readers park instead of busy-looping after an unplug. At the observed
     * ~96 immediate errors/s this trips within ~0.2 s of the device disappearing.
     */
    private static final int GONE_ERROR_THRESHOLD = 20;

    /**
     * USB full-speed, matching what {@code FakeUsbBackend} advertises. No Host
     * API device-speed getter exists; confirmed on hardware (2026-09-23) — the
     * F310 enumerated full-speed over vhci with this value (PRD Risk 1).
     */
    private static final int SPEED = 2;

    private final UsbDeviceConnection connection;
    private final List<UsbInterface> claimedInterfaces;
    private final Map<Integer, UsbEndpoint> endpoints;

    /** One reader (endpoint + URB FIFO + worker thread) per interrupt/bulk-IN endpoint. */
    private final Map<Integer, EndpointReader> readers = new HashMap<>();
    /** Guards every {@link EndpointReader#urbs} queue and gates its worker. */
    private final Object inLock = new Object();
    private final ExecutorService outWorkers =
            Executors.newFixedThreadPool(2, named("iotower-usb-out"));

    private volatile boolean running;
    /** m7d: set once the device is detected gone (unplug / re-enumeration); parks the readers. */
    private volatile boolean deviceGone;
    /** M9: one-shot listener invoked on the {@link #deviceGone} transition (see {@link #setGoneListener}). */
    private volatile Runnable goneListener;
    private final AtomicBoolean goneNotified = new AtomicBoolean();

    public static AndroidUsbBackend open(UsbManager usbManager, UsbDevice device) {
        if (usbManager == null || device == null) {
            return null;
        }
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "openDevice returned null — cannot claim");
            return null;
        }

        List<UsbInterface> claimedInterfaces = new ArrayList<>();
        Map<Integer, UsbEndpoint> endpoints = new HashMap<>();
        int total = device.getInterfaceCount();
        for (int i = 0; i < total; i++) {
            UsbInterface intf = device.getInterface(i);
            if (connection.claimInterface(intf, /* forceClaim = */ true)) {
                claimedInterfaces.add(intf);
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    endpoints.put(ep.getAddress(), ep);
                }
            }
        }

        StringBuilder eps = new StringBuilder();
        for (Integer addr : endpoints.keySet()) {
            eps.append("0x").append(Integer.toHexString(addr)).append(' ');
        }
        Log.i(TAG, "opened + claimed " + claimedInterfaces.size() + "/" + total
                + " interface(s); endpoints: " + eps.toString().trim());

        return new AndroidUsbBackend(connection, claimedInterfaces, endpoints);
    }

    private AndroidUsbBackend(UsbDeviceConnection connection, List<UsbInterface> claimedInterfaces,
            Map<Integer, UsbEndpoint> endpoints) {
        this.connection = connection;
        this.claimedInterfaces = claimedInterfaces;
        this.endpoints = endpoints;
        this.running = true;

        // One worker thread per interrupt/bulk-IN endpoint. Routing is by
        // (direction == IN, type == interrupt|bulk) only — never by VID/PID.
        for (UsbEndpoint ep : endpoints.values()) {
            boolean in = ep.getDirection() == UsbConstants.USB_DIR_IN;
            boolean pollable = ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                    || ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK;
            if (in && pollable) {
                EndpointReader r = new EndpointReader(ep.getAddress(), ep);
                readers.put(ep.getAddress(), r);
                r.thread = new Thread(() -> readLoop(r),
                        "iotower-usb-read-0x" + Integer.toHexString(ep.getAddress()));
                r.thread.setDaemon(true);
                r.thread.start();
            }
        }
    }

    @Override
    public byte[] rawDescriptors() {
        return connection.getRawDescriptors();
    }

    @Override
    public DeviceInfo deviceInfo() {
        byte[] raw = connection.getRawDescriptors();
        if (raw == null) {
            return null;
        }
        try {
            return DescriptorParser.parseDeviceInfo(raw, SPEED);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public int controlTransfer(int requestType, int request, int value, int index,
                               byte[] buffer, int length, int timeoutMillis) {
        // m7c: the Android framework already SET_CONFIGURATION'd the device when we
        // claimed it at open() (§2); re-forwarding the client's SET_CONFIGURATION
        // as a raw control transfer resets/halts the endpoints (see
        // bugs/m7-interrupt-in-empty-reports.md). ACK it as a no-op success instead
        // of forwarding. Protocol-generic (standard request number, no VID/PID):
        // Invariant 1 holds; this is an Android-host config quirk, kept in :android.
        // The ctrl line is still logged (step-1 diagnostic) so the attach sequence
        // is visible and correlates with the IN URB outcomes.
        boolean setConfig = (requestType & 0xFF) == 0x00 && (request & 0xFF) == 0x09;
        int ret = setConfig
                ? 0
                : connection.controlTransfer(requestType, request, value, index,
                        buffer, length, timeoutMillis);
        if (DEBUG) {
            Log.i(TAG, String.format(
                    "ctrl bmRequestType=0x%02x bRequest=0x%02x(%s) wValue=0x%04x wIndex=0x%04x wLength=%d -> ret=%d%s",
                    requestType & 0xFF, request & 0xFF, stdRequestName(requestType, request),
                    value & 0xFFFF, index & 0xFFFF, length, ret,
                    setConfig ? " [intercepted: already configured]" : ""));
        }
        return ret;
    }

    /**
     * Short label for a USB control request, for the diagnostic log above.
     * Standard requests (type field == 0) are named; class/vendor requests are
     * tagged by type only. Protocol-generic — no device specifics (Invariant 1).
     */
    private static String stdRequestName(int bmRequestType, int bRequest) {
        int type = (bmRequestType >> 5) & 0x03; // 0=standard, 1=class, 2=vendor
        if (type == 1) return "class";
        if (type == 2) return "vendor";
        if (type == 3) return "reserved";
        switch (bRequest & 0xFF) {
            case 0x00: return "GET_STATUS";
            case 0x01: return "CLEAR_FEATURE";
            case 0x03: return "SET_FEATURE";
            case 0x05: return "SET_ADDRESS";
            case 0x06: return "GET_DESCRIPTOR";
            case 0x07: return "SET_DESCRIPTOR";
            case 0x08: return "GET_CONFIGURATION";
            case 0x09: return "SET_CONFIGURATION";
            case 0x0a: return "GET_INTERFACE";
            case 0x0b: return "SET_INTERFACE";
            case 0x0c: return "SYNCH_FRAME";
            default:   return "std?";
        }
    }

    @Override
    public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
        if (deviceGone) {
            return completedTransfer(STATUS_ESHUTDOWN); // m7d: device gone - fail fast, don't queue
        }
        UsbEndpoint endpoint = endpoints.get(endpointAddress);
        if (endpoint == null) {
            Log.w(TAG, "submit: no claimed endpoint for 0x" + Integer.toHexString(endpointAddress));
            return null; // engine maps a null return to -ECONNRESET
        }
        if (direction == UsbIp.DIR_IN) {
            return submitIn(endpointAddress);
        }
        return submitOut(endpoint, buffer, length);
    }

    /** IN: enqueue a URB on its endpoint's FIFO and wake the worker; it drives one read per URB. */
    private UsbTransfer submitIn(int endpointAddress) {
        synchronized (inLock) {
            EndpointReader r = readers.get(endpointAddress);
            if (r == null) {
                Log.w(TAG, "submitIn: no reader for 0x" + Integer.toHexString(endpointAddress));
                return null;
            }
            UsbTransfer transfer = new UsbTransfer(null);
            r.urbs.addLast(transfer);
            if (r.urbs.size() > r.maxDepth) {
                r.maxDepth = r.urbs.size();
            }
            if (DEBUG) {
                Log.i(TAG, "urbIN ep=0x" + Integer.toHexString(endpointAddress)
                        + " submit depth=" + r.urbs.size());
            }
            inLock.notifyAll();
            return transfer;
        }
    }

    /** OUT: synchronous {@code bulkTransfer} on a worker pool. */
    private UsbTransfer submitOut(UsbEndpoint endpoint, byte[] buffer, int length) {
        UsbTransfer transfer = new UsbTransfer(null);
        try {
            outWorkers.execute(() -> {
                int sent;
                try {
                    sent = connection.bulkTransfer(endpoint, buffer, length, OUT_TIMEOUT_MS);
                } catch (RuntimeException e) {
                    sent = -1;
                }
                transfer.completion.complete(sent < 0
                        ? new UsbTransfer.Result(STATUS_ECONNRESET, null, 0)
                        : new UsbTransfer.Result(0, null, sent));
            });
        } catch (RuntimeException e) {
            transfer.completion.complete(new UsbTransfer.Result(STATUS_ECONNRESET, null, 0));
        }
        return transfer;
    }

    @Override
    public void cancel(UsbTransfer transfer) {
        if (transfer == null) {
            return;
        }
        int addr = -1;
        synchronized (inLock) {
            for (EndpointReader r : readers.values()) {
                if (r.urbs.remove(transfer)) {
                    addr = r.address;
                    break;
                }
            }
        }
        if (!transfer.completion.isDone()) {
            transfer.completion.complete(new UsbTransfer.Result(STATUS_ECONNRESET, null, 0));
        }
        if (DEBUG && addr >= 0) {
            Log.i(TAG, "urbIN ep=0x" + Integer.toHexString(addr) + " cancel");
        }
    }

    /**
     * m7d: true once the device was detected gone (sustained immediate IN errors
     * whose halt-clear also failed). The M8 reset-recovery path polls this to tear
     * the backend down and re-acquire when the device returns.
     */
    public boolean isDeviceGone() {
        return deviceGone;
    }

    /**
     * M9 (§7/§8): register a one-shot callback fired when the backend detects the
     * device is gone (the same transition {@link #markDeviceGone} logs). Lets
     * {@code ServerService} drive teardown from the backend's own observation, not
     * only the {@code ACTION_USB_DEVICE_DETACHED} broadcast. The callback runs on a
     * reader thread, so it must not block or close this backend inline - hand off to
     * another thread. If the device is already gone it fires immediately. Fires at
     * most once. No effect on detection; device-agnostic (Invariant 1).
     */
    public void setGoneListener(Runnable listener) {
        goneListener = listener;
        if (deviceGone) {
            notifyGone();
        }
    }

    private void notifyGone() {
        Runnable l = goneListener;
        if (l != null && goneNotified.compareAndSet(false, true)) {
            try {
                l.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "gone listener threw: " + e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        synchronized (inLock) {
            inLock.notifyAll(); // wake any worker blocked waiting for a URB
        }
        outWorkers.shutdownNow();
        // Fail any still-queued URBs.
        synchronized (inLock) {
            for (EndpointReader r : readers.values()) {
                for (UsbTransfer urb : r.urbs) {
                    if (!urb.completion.isDone()) {
                        urb.completion.complete(new UsbTransfer.Result(STATUS_ECONNRESET, null, 0));
                    }
                }
                r.urbs.clear();
            }
        }
        for (UsbInterface intf : claimedInterfaces) {
            try {
                connection.releaseInterface(intf);
            } catch (RuntimeException ignored) {
            }
        }
        connection.close(); // unblocks any in-flight bulkTransfer
        for (EndpointReader r : readers.values()) {
            if (r.thread != null) {
                try {
                    r.thread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Per-endpoint worker: read only while a client URB is waiting, binding each
     * {@code bulkTransfer} to the head URB. See the class Javadoc for the n>0 /
     * n==0 / n<0 handling.
     */
    private void readLoop(EndpointReader r) {
        while (running) {
            synchronized (inLock) {
                // Park while there is nothing to read, or once the device is gone
                // (m7d): a gone device makes every read return an immediate error, so
                // we stop polling instead of busy-looping at ~96/s until close().
                while (running && (deviceGone || r.urbs.isEmpty())) {
                    try {
                        inLock.wait(1000); // periodic wake to recheck running / deviceGone
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    return;
                }
            }

            long startMs = System.nanoTime() / 1_000_000L;
            int n;
            try {
                n = connection.bulkTransfer(r.endpoint, r.buf, r.buf.length, READ_TIMEOUT_MS);
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                n = -1;
            }
            long elapsedMs = (System.nanoTime() / 1_000_000L) - startMs;

            r.reads++;
            if (n > 0) {
                r.ok++;
                r.consecErr = 0;
                UsbTransfer w;
                synchronized (inLock) {
                    w = r.urbs.pollFirst();
                }
                if (w != null && !w.completion.isDone()) {
                    w.completion.complete(new UsbTransfer.Result(0, Arrays.copyOf(r.buf, n), n));
                    if (DEBUG) {
                        Log.i(TAG, "urbIN ep=0x" + Integer.toHexString(r.address)
                                + " deliver n=" + n + " depth=" + depthOf(r));
                    }
                }
            } else if (n == 0 || elapsedMs >= TIMEOUT_FLOOR_MS) {
                // m7d: no data within the read window. Android's bulkTransfer returns
                // -1 on a timeout just as it does on a stall, so a -1 that took ~the
                // full READ_TIMEOUT_MS is a no-data poll, not an endpoint error. Keep
                // the client URB queued and re-poll - it legitimately pends until data
                // arrives (as on real hardware), with no CLEAR_FEATURE and no -EPIPE
                // flood. This removes the ~24 s first-attach "stall" and the
                // mid-stream dips (both idle timeouts previously misread as errors).
                r.timeouts++;
                r.consecErr = 0;
            } else {
                // Immediate -1: a genuine endpoint error (stall) or a gone device.
                r.errs++;
                r.consecErr++;
                long now = System.currentTimeMillis();
                // Clear ENDPOINT_HALT to recover a real stall: fire on the first
                // immediate error, then rate-limited so ep0 is not flooded.
                if (r.consecErr == 1 || now - r.lastHaltClearMs >= HALT_CLEAR_INTERVAL_MS) {
                    r.lastHaltClearRet = clearEndpointHalt(r.address);
                    r.lastHaltClearMs = now;
                    if (DEBUG) {
                        Log.i(TAG, "urbIN ep=0x" + Integer.toHexString(r.address)
                                + " clearHalt(ENDPOINT_HALT) -> ret=" + r.lastHaltClearRet);
                    }
                }
                // m7d: immediate errors whose halt-clear also fails (ep0 itself
                // erroring) mean the device is gone - park instead of spinning.
                if (r.lastHaltClearRet < 0 && r.consecErr >= GONE_ERROR_THRESHOLD) {
                    markDeviceGone(r);
                    continue; // queued URBs already failed; the loop top parks the reader
                }
                UsbTransfer w;
                synchronized (inLock) {
                    w = r.urbs.pollFirst();
                }
                if (w != null && !w.completion.isDone()) {
                    w.completion.complete(new UsbTransfer.Result(STATUS_EPIPE, null, 0));
                }
                if (DEBUG && (r.consecErr <= 5 || r.errs % 500 == 0)) {
                    Log.i(TAG, "urbIN ep=0x" + Integer.toHexString(r.address)
                            + " error status=" + STATUS_EPIPE + " consec=" + r.consecErr
                            + " depth=" + depthOf(r));
                }
                sleep(ERROR_BACKOFF_MS);
            }
            maybeSummary(r);
        }
    }

    /**
     * m7d: mark the device gone (detected from sustained immediate IN errors whose
     * {@code CLEAR_FEATURE(ENDPOINT_HALT)} also fails - ep0 itself erroring). Fails
     * every queued URB with {@code -ESHUTDOWN} and wakes the readers so they park at
     * the loop top instead of busy-looping. Idempotent. Device-agnostic (decided by
     * transfer outcome only, never VID/PID - Invariant 1).
     */
    private void markDeviceGone(EndpointReader trigger) {
        if (deviceGone) {
            return;
        }
        deviceGone = true;
        if (DEBUG) {
            Log.w(TAG, "urbIN ep=0x" + Integer.toHexString(trigger.address)
                    + " device gone (immediate errors + halt-clear failing) - parking readers");
        }
        synchronized (inLock) {
            for (EndpointReader r : readers.values()) {
                for (UsbTransfer urb : r.urbs) {
                    if (!urb.completion.isDone()) {
                        urb.completion.complete(new UsbTransfer.Result(STATUS_ESHUTDOWN, null, 0));
                    }
                }
                r.urbs.clear();
            }
            inLock.notifyAll();
        }
        notifyGone(); // M9: outside inLock - the listener must never run under it
    }

    /** A pre-completed transfer carrying {@code status} - for fail-fast returns (m7d). */
    private static UsbTransfer completedTransfer(int status) {
        UsbTransfer t = new UsbTransfer(null);
        t.completion.complete(new UsbTransfer.Result(status, null, 0));
        return t;
    }

    private int depthOf(EndpointReader r) {
        synchronized (inLock) {
            return r.urbs.size();
        }
    }

    /** Emit a 1 Hz per-endpoint throughput summary (the key M7b instrument). */
    private void maybeSummary(EndpointReader r) {
        if (!DEBUG) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - r.lastSummaryMs < 1000) {
            return;
        }
        int depth;
        int maxDepth;
        synchronized (inLock) {
            depth = r.urbs.size();
            maxDepth = r.maxDepth;
            r.maxDepth = depth;
        }
        Log.i(TAG, "urbIN ep=0x" + Integer.toHexString(r.address) + " 1s reads=" + r.reads
                + " ok=" + r.ok + " timeout=" + r.timeouts + " err=" + r.errs
                + " depth=" + depth + " maxDepth=" + maxDepth);
        r.reads = 0;
        r.ok = 0;
        r.timeouts = 0;
        r.errs = 0;
        r.lastSummaryMs = now;
    }

    /**
     * m7c: issue a standard {@code CLEAR_FEATURE(ENDPOINT_HALT)} on {@code
     * endpointAddress} (USB stall recovery). Runs on the reader thread; short
     * timeout, best-effort. Returns the control-transfer result ({@code 0} = ok).
     */
    private int clearEndpointHalt(int endpointAddress) {
        try {
            // bmRequestType=0x02 (OUT, standard, recipient=endpoint),
            // bRequest=0x01 (CLEAR_FEATURE), wValue=0x0000 (ENDPOINT_HALT),
            // wIndex=endpoint address, no data.
            return connection.controlTransfer(0x02, 0x01, 0x0000, endpointAddress,
                    null, 0, READ_TIMEOUT_MS);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** One interrupt/bulk-IN endpoint: its URB FIFO, read buffer, worker and diagnostics. */
    private static final class EndpointReader {
        final int address;
        final UsbEndpoint endpoint;
        final ArrayDeque<UsbTransfer> urbs = new ArrayDeque<>();
        final byte[] buf;
        Thread thread;

        // Diagnostics: interval counters mutated only by the worker thread;
        // maxDepth is updated under inLock by submitIn and the summary.
        long reads;
        long ok;
        long timeouts;
        long errs;
        long consecErr;
        int maxDepth;
        long lastSummaryMs;
        long lastHaltClearMs; // m7c: 0 => first read error clears the halt immediately
        int lastHaltClearRet; // m7d: last CLEAR_FEATURE(ENDPOINT_HALT) result; <0 => ep0 failing (gone)

        EndpointReader(int address, UsbEndpoint endpoint) {
            this.address = address;
            this.endpoint = endpoint;
            this.buf = new byte[Math.max(1, endpoint.getMaxPacketSize())];
            this.lastSummaryMs = System.currentTimeMillis();
        }
    }
}
