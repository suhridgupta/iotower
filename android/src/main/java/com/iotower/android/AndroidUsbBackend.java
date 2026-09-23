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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link UsbBackend} backed by the Android USB Host API (§2, §5). This is the
 * only class that touches real USB hardware; everything above it is plain Java.
 *
 * <p><b>EXPERIMENT (M7 bring-up):</b> interrupt/bulk-IN reads use the
 * <em>synchronous</em> {@link UsbDeviceConnection#bulkTransfer} instead of
 * {@code UsbRequest}/{@code requestWait}. Rationale: with {@code requestWait} the
 * IN reads complete with 0 bytes once a USB/IP client attaches (see
 * bugs/m7-interrupt-in-empty-reports.md), and {@code requestWait} hides the URB
 * status — {@code bulkTransfer} returns the byte count or {@code -1} on
 * error/timeout, so it distinguishes "errored" from "empty" and may deliver the
 * report outright. One dedicated reader thread per IN endpoint loops
 * {@code bulkTransfer} and hands each non-empty read to the oldest waiting client
 * URB. OUT also uses {@code bulkTransfer} (worker pool); ep0 uses
 * {@link #controlTransfer}. Device-agnostic (Invariant 1); {@code core} stays
 * Android-free (Invariant 2); failures are reported as {@code -ECONNRESET}
 * (Invariant 6).
 */
public final class AndroidUsbBackend implements UsbBackend {

    private static final String TAG = "IoTowerBackend";
    private static final int STATUS_ECONNRESET = -104;
    private static final int OUT_TIMEOUT_MS = 1000;
    /** IN read timeout: bounds how often each reader thread rechecks {@code running}. */
    private static final int READ_TIMEOUT_MS = 200;

    /**
     * USB full-speed, matching what {@code FakeUsbBackend} advertises. No Host
     * API device-speed getter exists; confirmed on hardware (2026-09-23) — the
     * F310 enumerated full-speed over vhci with this value (PRD Risk 1).
     */
    private static final int SPEED = 2;

    private final UsbDeviceConnection connection;
    private final List<UsbInterface> claimedInterfaces;
    private final Map<Integer, UsbEndpoint> endpoints;

    /** One reader (endpoint + waiter FIFO + thread) per interrupt/bulk-IN endpoint. */
    private final Map<Integer, EndpointReader> readers = new HashMap<>();
    private final Object inLock = new Object();
    private final ExecutorService outWorkers =
            Executors.newFixedThreadPool(2, named("iotower-usb-out"));

    private final AtomicLong reads = new AtomicLong();
    private final AtomicLong diag = new AtomicLong();

    private volatile boolean running;

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

        // One reader thread per interrupt/bulk-IN endpoint (bulkTransfer is blocking).
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
        return connection.controlTransfer(requestType, request, value, index,
                buffer, length, timeoutMillis);
    }

    @Override
    public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
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

    /** IN: register a waiter; the endpoint's reader thread delivers the next non-empty read. */
    private UsbTransfer submitIn(int endpointAddress) {
        synchronized (inLock) {
            EndpointReader r = readers.get(endpointAddress);
            if (r == null) {
                Log.w(TAG, "submitIn: no reader for 0x" + Integer.toHexString(endpointAddress));
                return null;
            }
            UsbTransfer transfer = new UsbTransfer(null);
            r.waiters.addLast(transfer);
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
        synchronized (inLock) {
            for (EndpointReader r : readers.values()) {
                if (r.waiters.remove(transfer)) {
                    break;
                }
            }
        }
        if (!transfer.completion.isDone()) {
            transfer.completion.complete(new UsbTransfer.Result(STATUS_ECONNRESET, null, 0));
        }
    }

    @Override
    public void close() {
        running = false;
        outWorkers.shutdownNow();
        // Fail any pending waiters.
        synchronized (inLock) {
            for (EndpointReader r : readers.values()) {
                for (UsbTransfer waiter : r.waiters) {
                    if (!waiter.completion.isDone()) {
                        waiter.completion.complete(new UsbTransfer.Result(STATUS_ECONNRESET, null, 0));
                    }
                }
                r.waiters.clear();
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

    /** Dedicated per-endpoint reader: blocking {@code bulkTransfer} loop. */
    private void readLoop(EndpointReader r) {
        int max = Math.max(1, r.endpoint.getMaxPacketSize());
        byte[] buf = new byte[max];
        while (running) {
            int n;
            try {
                n = connection.bulkTransfer(r.endpoint, buf, buf.length, READ_TIMEOUT_MS);
            } catch (RuntimeException e) {
                if (!running) {
                    break;
                }
                continue;
            }
            long total = reads.incrementAndGet();
            if (diag.incrementAndGet() <= 12 || total % 500 == 0) {
                Log.i(TAG, "bulkRead ep=0x" + Integer.toHexString(r.address) + " n=" + n
                        + " waiters=" + r.waiters.size() + " (#" + total + ")");
            }
            if (n > 0) {
                UsbTransfer waiter;
                synchronized (inLock) {
                    waiter = r.waiters.pollFirst();
                }
                if (waiter != null && !waiter.completion.isDone()) {
                    waiter.completion.complete(new UsbTransfer.Result(0, Arrays.copyOf(buf, n), n));
                }
            }
            // n <= 0: timeout / no data / error — loop and recheck `running`.
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

    /** One interrupt/bulk-IN endpoint: its waiter FIFO and dedicated reader thread. */
    private static final class EndpointReader {
        final int address;
        final UsbEndpoint endpoint;
        final ArrayDeque<UsbTransfer> waiters = new ArrayDeque<>();
        Thread thread;

        EndpointReader(int address, UsbEndpoint endpoint) {
            this.address = address;
            this.endpoint = endpoint;
        }
    }
}
