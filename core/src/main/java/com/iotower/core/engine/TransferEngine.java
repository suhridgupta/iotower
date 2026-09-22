package com.iotower.core.engine;

import com.iotower.core.protocol.CmdSubmit;
import com.iotower.core.protocol.CmdUnlink;
import com.iotower.core.protocol.RetSubmit;
import com.iotower.core.protocol.RetUnlink;
import com.iotower.core.protocol.UsbIp;
import com.iotower.core.protocol.UsbIpHeaderBasic;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The asynchronous USB/IP transfer engine (§5, §5.1). Translates transfer-phase
 * URBs to {@link UsbBackend} calls and correlates their (possibly out-of-order)
 * completions back to their {@code seqnum}, framing each as a {@code RET_SUBMIT}
 * / {@code RET_UNLINK} on the wire.
 *
 * <p>This is the real reader &#8594; engine &#8594; writer model that replaces
 * M4's provisional single-threaded control loop. The <em>reader</em> is the
 * caller's accept thread (see {@code UsbIpServer.runTransferPhase}); it parses
 * frames and calls {@link #submit} / {@link #unlink} and never writes to the
 * socket. This engine owns:
 *
 * <ul>
 *   <li>a single <b>writer</b> thread — the only holder of the socket
 *       {@link OutputStream}, draining a queue of fully-framed replies so
 *       replies never interleave on the wire (§5.1) and every frame is flushed
 *       (§6, latency);</li>
 *   <li>a small <b>worker pool</b> that runs the blocking ep0
 *       {@link UsbBackend#controlTransfer} so a slow control transfer never
 *       stalls the reader;</li>
 *   <li>a {@link ConcurrentHashMap} keyed by {@code seqnum} whose atomic
 *       {@code remove} is the single arbiter between a completion and its
 *       cancel — exactly one wins, so a cancelled URB never also emits a
 *       {@code RET_SUBMIT} and a reply is never lost or duplicated.</li>
 * </ul>
 *
 * <p>Device-agnostic (Invariant 1): routing is by {@code (ep, direction)} only;
 * the engine never inspects VID/PID or endpoint type. One engine per client
 * connection; {@link #shutdown()} tears down its threads but never closes the
 * backend, which outlives a single connection.
 */
public final class TransferEngine {

    /** {@code -ECONNRESET} — status for a URB terminated by cancel (§5.1). */
    private static final int STATUS_ECONNRESET = -104;

    /** Timeout for the synchronous ep0 {@code controlTransfer} calls (§5). */
    private static final int CONTROL_TIMEOUT_MS = 5000;

    /** Sentinel enqueued by {@link #shutdown()} to stop the writer thread. */
    private static final byte[] POISON = new byte[0];

    private final UsbBackend backend;
    private final OutputStream out;
    private final int devid;

    /** seqnum -> in-flight URB. Its atomic {@code remove} arbitrates cancel vs completion. */
    private final Map<Integer, Inflight> inflight = new ConcurrentHashMap<>();

    /** Fully-framed replies awaiting the single writer thread. */
    private final BlockingQueue<byte[]> outbound = new LinkedBlockingQueue<>();

    private final ExecutorService controlWorkers =
            Executors.newFixedThreadPool(2, named("iotower-control"));
    private final Thread writerThread = new Thread(this::writerLoop, "iotower-writer");

    private volatile boolean running;

    public TransferEngine(UsbBackend backend, OutputStream out, int devid) {
        this.backend = backend;
        this.out = out;
        this.devid = devid;
    }

    /** Starts the writer thread. Call once, before the first {@link #submit}. */
    public void start() {
        running = true;
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /**
     * Dispatch a {@code CMD_SUBMIT}. ep0 runs on the worker pool
     * ({@code controlTransfer} blocks); ep&#8800;0 goes to the asynchronous
     * {@link UsbBackend#submit}. Registered in {@link #inflight} under
     * {@code basic.seqnum} before dispatch so a racing {@code CMD_UNLINK} can
     * find it.
     *
     * @param outPayload the OUT transfer buffer already read by the reader, or
     *                   {@code null} for an IN transfer.
     */
    public void submit(UsbIpHeaderBasic basic, CmdSubmit cmd, byte[] outPayload) {
        final int seqnum = basic.seqnum;
        final int direction = basic.direction;
        final int ep = basic.ep;
        inflight.put(seqnum, new Inflight(seqnum, direction, ep, null));

        if (ep == 0) {
            controlWorkers.execute(() -> runControl(seqnum, basic, cmd, outPayload));
            return;
        }

        int endpointAddress = ep | (direction == UsbIp.DIR_IN ? 0x80 : 0x00);
        byte[] buffer;
        if (direction == UsbIp.DIR_IN) {
            buffer = new byte[cmd.transferBufferLength];
        } else {
            buffer = outPayload != null ? outPayload : new byte[0];
        }

        UsbTransfer transfer;
        try {
            transfer = backend.submit(endpointAddress, direction, buffer, cmd.transferBufferLength);
        } catch (RuntimeException e) {
            // A backend that cannot submit must not desync the stream: reply an
            // error for this seqnum and keep going (Invariant 6).
            System.out.println("[iotower] submit failed ep=0x" + Integer.toHexString(endpointAddress)
                    + ": " + e);
            completeSubmit(seqnum, direction, ep, STATUS_ECONNRESET, null, 0);
            return;
        }

        if (transfer == null) {
            completeSubmit(seqnum, direction, ep, STATUS_ECONNRESET, null, 0);
            return;
        }

        // Record the handle so CMD_UNLINK can cancel it.
        Inflight entry = inflight.get(seqnum);
        if (entry != null) {
            entry.transfer = transfer;
        }

        transfer.completion.whenComplete((result, error) -> {
            if (error != null || result == null) {
                completeSubmit(seqnum, direction, ep, STATUS_ECONNRESET, null, 0);
                return;
            }
            byte[] data = null;
            int actualLength = result.actualLength;
            if (direction == UsbIp.DIR_IN && result.data != null && actualLength > 0) {
                actualLength = Math.min(actualLength, result.data.length);
                data = result.data;
            }
            completeSubmit(seqnum, direction, ep, result.status, data, actualLength);
        });
    }

    /** Runs one ep0 control transfer on the worker pool, then frames its reply. */
    private void runControl(int seqnum, UsbIpHeaderBasic basic, CmdSubmit cmd, byte[] outPayload) {
        int bmRequestType = cmd.setup[0] & 0xFF;
        int bRequest = cmd.setup[1] & 0xFF;
        // wValue/wIndex/wLength in the 8-byte setup packet are little-endian (USB
        // spec) — the one mixed-endianness spot; every enclosing frame is big-endian.
        int wValue = (cmd.setup[2] & 0xFF) | ((cmd.setup[3] & 0xFF) << 8);
        int wIndex = (cmd.setup[4] & 0xFF) | ((cmd.setup[5] & 0xFF) << 8);

        byte[] buffer;
        if (basic.direction == UsbIp.DIR_IN) {
            buffer = new byte[cmd.transferBufferLength];
        } else {
            buffer = outPayload != null ? outPayload : new byte[0];
        }

        int ret;
        try {
            ret = backend.controlTransfer(bmRequestType, bRequest, wValue, wIndex,
                    buffer, cmd.transferBufferLength, CONTROL_TIMEOUT_MS);
        } catch (RuntimeException e) {
            System.out.println("[iotower] controlTransfer failed seqnum=" + seqnum + ": " + e);
            ret = STATUS_ECONNRESET;
        }

        int status = ret < 0 ? ret : 0;
        int actualLength = ret < 0 ? 0 : ret;
        byte[] data = (basic.direction == UsbIp.DIR_IN && actualLength > 0) ? buffer : null;
        completeSubmit(seqnum, basic.direction, basic.ep, status, data, actualLength);
    }

    /**
     * Frames and enqueues a {@code RET_SUBMIT} for {@code seqnum} — but only if
     * this seqnum is still in flight. A {@code CMD_UNLINK} that removed it first
     * wins the {@link ConcurrentHashMap#remove} race, and this completion is
     * silently dropped (no double reply).
     */
    private void completeSubmit(int seqnum, int direction, int ep, int status, byte[] data,
            int actualLength) {
        if (inflight.remove(seqnum) == null) {
            return; // already unlinked, or the engine is shutting down.
        }
        boolean withPayload = direction == UsbIp.DIR_IN && actualLength > 0 && data != null;
        int size = UsbIpHeaderBasic.BYTES + RetSubmit.BYTES + (withPayload ? actualLength : 0);
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        new UsbIpHeaderBasic(UsbIp.RET_SUBMIT, seqnum, devid, direction, ep).writeTo(buf);
        new RetSubmit(status, actualLength, 0, 0, 0).writeTo(buf);
        if (withPayload) {
            buf.put(data, 0, actualLength);
        }
        enqueue(buf.array());
    }

    /**
     * Handle a {@code CMD_UNLINK}: cancel the targeted in-flight transfer (if
     * still present) and reply {@code RET_UNLINK}. Removing the target from
     * {@link #inflight} suppresses its pending {@code RET_SUBMIT} (§5.1). A
     * target that already completed simply isn't found — still a status-0 ack.
     */
    public void unlink(UsbIpHeaderBasic basic, CmdUnlink cmd) {
        Inflight target = inflight.remove(cmd.unlinkSeqnum);
        if (target != null && target.transfer != null) {
            try {
                backend.cancel(target.transfer);
            } catch (RuntimeException e) {
                System.out.println("[iotower] cancel failed seqnum=" + cmd.unlinkSeqnum + ": " + e);
            }
        }
        ByteBuffer buf = ByteBuffer.allocate(UsbIpHeaderBasic.BYTES + RetUnlink.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        new UsbIpHeaderBasic(UsbIp.RET_UNLINK, basic.seqnum, devid, basic.direction, basic.ep)
                .writeTo(buf);
        new RetUnlink(0).writeTo(buf);
        enqueue(buf.array());
    }

    private void enqueue(byte[] frame) {
        if (running) {
            outbound.offer(frame);
        }
    }

    /** The single writer thread: sole owner of the socket {@link OutputStream}. */
    private void writerLoop() {
        try {
            while (true) {
                byte[] frame = outbound.take();
                if (frame == POISON) {
                    return;
                }
                out.write(frame);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // Client went away mid-write; the reader will also see EOF and shut us down.
            System.out.println("[iotower] writer: " + e);
        }
    }

    /**
     * Stop the engine's threads and release any still-in-flight transfers. Does
     * <b>not</b> close the {@link UsbBackend} — it outlives one connection
     * (desktop process, Android service) and must survive client reconnect.
     */
    public void shutdown() {
        running = false;

        // Release Host-API resources for URBs the client never unlinked.
        List<Inflight> remaining = new ArrayList<>(inflight.values());
        inflight.clear();
        for (Inflight e : remaining) {
            if (e.transfer != null) {
                try {
                    backend.cancel(e.transfer);
                } catch (RuntimeException ignored) {
                    // best-effort on teardown
                }
            }
        }

        controlWorkers.shutdownNow();
        try {
            controlWorkers.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        outbound.offer(POISON);
        try {
            writerThread.join(1000);
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

    /** One in-flight URB: its header echo fields and (for ep&#8800;0) its cancel handle. */
    private static final class Inflight {
        final int seqnum;
        final int direction;
        final int ep;
        volatile UsbTransfer transfer;

        Inflight(int seqnum, int direction, int ep, UsbTransfer transfer) {
            this.seqnum = seqnum;
            this.direction = direction;
            this.ep = ep;
            this.transfer = transfer;
        }
    }
}
