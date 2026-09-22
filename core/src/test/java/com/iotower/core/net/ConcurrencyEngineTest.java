package com.iotower.core.net;

import com.iotower.core.protocol.CmdSubmit;
import com.iotower.core.protocol.CmdUnlink;
import com.iotower.core.protocol.OpHeader;
import com.iotower.core.protocol.RetSubmit;
import com.iotower.core.protocol.RetUnlink;
import com.iotower.core.protocol.UsbIp;
import com.iotower.core.protocol.UsbIpHeaderBasic;
import com.iotower.core.protocol.UsbIpUsbDevice;
import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 integration test for the M5 asynchronous engine (§5.1), driving the real
 * {@link UsbIpServer} over loopback with a backend whose completion timing the
 * test fully controls. Covers exactly the code paths M5 adds and no existing
 * test touches: the async ep&#8800;0 submit path, {@code seqnum} correlation of
 * <b>out-of-order</b> completions through the single writer, and
 * {@code CMD_UNLINK} &#8594; cancel with suppression of the late
 * {@code RET_SUBMIT}. ep0 control is left to {@code ImportControlTest}, whose
 * continued green run is the regression guard that control still works through
 * the new engine.
 */
class ConcurrencyEngineTest {

    /**
     * Backend that parks every ep&#8800;0 submit — it hands the returned
     * {@link UsbTransfer} to the test via {@link #submitted} and never completes
     * it on its own, so the test decides completion order and timing. IMPORT
     * needs only non-null descriptors/identity (it never parses them).
     */
    private static final class QueueingBackend implements UsbBackend {
        final BlockingQueue<UsbTransfer> submitted = new LinkedBlockingQueue<>();
        final CountDownLatch cancelLatch = new CountDownLatch(1);
        final AtomicReference<UsbTransfer> cancelled = new AtomicReference<>();

        @Override
        public byte[] rawDescriptors() {
            byte[] d = new byte[18];
            d[0] = 0x12;
            d[1] = 0x01; // device descriptor, enough to be non-null for IMPORT
            return d;
        }

        @Override
        public DeviceInfo deviceInfo() {
            return new DeviceInfo(0x046d, 0xc21d, 0x4014, 0xff, 0xff, 0xff, 1, 1, 1, 2);
        }

        @Override
        public int controlTransfer(int requestType, int request, int value, int index,
                                   byte[] buffer, int length, int timeoutMillis) {
            return -1; // ep0 not exercised here
        }

        @Override
        public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
            UsbTransfer t = new UsbTransfer(null);
            submitted.add(t);
            return t; // parked: the test completes it
        }

        @Override
        public void cancel(UsbTransfer transfer) {
            cancelled.set(transfer);
            cancelLatch.countDown();
            // Simulate the unlinked URB completing late — the engine must drop it.
            if (!transfer.completion.isDone()) {
                transfer.completion.complete(new UsbTransfer.Result(-104, null, 0));
            }
        }

        @Override
        public void close() { }
    }

    @Test
    void outOfOrderInterruptCompletionsCarryCorrectSeqnums() throws Exception {
        QueueingBackend backend = new QueueingBackend();
        UsbIpServer server = new UsbIpServer(backend, 0);
        Thread serverThread = new Thread(server, "usbip-server-test");
        serverThread.setDaemon(true);
        serverThread.start();
        try {
            int port = server.awaitBoundPort(2000);
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setTcpNoDelay(true);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                importDevice(out, in);

                // Two interrupt-IN submits on ep 0x81 (header ep=1, dir=IN), seqnum 1 then 2.
                sendInterruptInSubmit(out, /* seqnum */ 1, /* len */ 8);
                sendInterruptInSubmit(out, /* seqnum */ 2, /* len */ 8);

                // Backend receives them in reader order: first parked == seqnum 1's.
                UsbTransfer t1 = backend.submitted.poll(2, TimeUnit.SECONDS);
                UsbTransfer t2 = backend.submitted.poll(2, TimeUnit.SECONDS);
                assertTrue(t1 != null && t2 != null, "both submits reached the backend");

                byte[] payload1 = {0x11, 0x12, 0x13, 0x14};
                byte[] payload2 = {0x21, 0x22, 0x23, 0x24, 0x25};

                // Complete OUT OF ORDER: seqnum 2 first, then seqnum 1.
                t2.completion.complete(new UsbTransfer.Result(0, payload2, payload2.length));
                Ret r2 = readRetSubmit(in);
                assertEquals(2, r2.seqnum, "first reply is seqnum 2 (completed first)");
                assertEquals(0, r2.status, "seqnum 2 status");
                assertArrayEquals(payload2, r2.payload, "seqnum 2 payload");

                t1.completion.complete(new UsbTransfer.Result(0, payload1, payload1.length));
                Ret r1 = readRetSubmit(in);
                assertEquals(1, r1.seqnum, "second reply is seqnum 1 (completed second)");
                assertArrayEquals(payload1, r1.payload, "seqnum 1 payload");
            }
        } finally {
            server.stop();
            serverThread.join(2000);
        }
    }

    @Test
    void unlinkCancelsInflightAndSuppressesRetSubmit() throws Exception {
        QueueingBackend backend = new QueueingBackend();
        UsbIpServer server = new UsbIpServer(backend, 0);
        Thread serverThread = new Thread(server, "usbip-server-test");
        serverThread.setDaemon(true);
        serverThread.start();
        try {
            int port = server.awaitBoundPort(2000);
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setTcpNoDelay(true);
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                importDevice(out, in);

                // One interrupt-IN submit (seqnum 5) that the backend leaves in-flight.
                sendInterruptInSubmit(out, /* seqnum */ 5, /* len */ 8);
                UsbTransfer t5 = backend.submitted.poll(2, TimeUnit.SECONDS);
                assertTrue(t5 != null, "submit reached the backend");

                // UNLINK targeting seqnum 5; this UNLINK's own seqnum is 6.
                sendUnlink(out, /* unlinkCmdSeqnum */ 6, /* targetSeqnum */ 5);

                // The in-flight transfer is cancelled...
                assertTrue(backend.cancelLatch.await(2, TimeUnit.SECONDS), "cancel was invoked");
                assertSame(t5, backend.cancelled.get(), "cancelled the right transfer");

                // ...and RET_UNLINK (status 0) comes back for the UNLINK's seqnum.
                UsbIpHeaderBasic unlinkHdr = readBasic(in);
                assertEquals(UsbIp.RET_UNLINK, unlinkHdr.command, "reply is RET_UNLINK");
                assertEquals(6, unlinkHdr.seqnum, "RET_UNLINK echoes the UNLINK seqnum");
                RetUnlink retUnlink = RetUnlink.readFrom(
                        ByteBuffer.wrap(readN(in, RetUnlink.BYTES)).order(ByteOrder.BIG_ENDIAN));
                assertEquals(0, retUnlink.status, "RET_UNLINK status");

                // The cancelled URB's late completion must NOT produce a RET_SUBMIT.
                socket.setSoTimeout(400);
                assertThrows(SocketTimeoutException.class, () -> in.read(),
                        "no RET_SUBMIT may follow a successful UNLINK");
            }
        } finally {
            server.stop();
            serverThread.join(2000);
        }
    }

    // --- helpers -----------------------------------------------------------

    private static void importDevice(OutputStream out, InputStream in) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(OpHeader.BYTES + 32).order(ByteOrder.BIG_ENDIAN);
        new OpHeader(UsbIp.VERSION, UsbIp.OP_REQ_IMPORT, 0).writeTo(req);
        byte[] busid = new byte[32];
        byte[] b = "1-1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, busid, 0, b.length);
        req.put(busid);
        out.write(req.array());
        out.flush();

        // Drain the OP_REP_IMPORT header + usbip_usb_device that precede the transfer phase.
        OpHeader header = OpHeader.readFrom(
                ByteBuffer.wrap(readN(in, OpHeader.BYTES)).order(ByteOrder.BIG_ENDIAN));
        assertEquals(UsbIp.OP_REP_IMPORT, header.code, "IMPORT reply code");
        assertEquals(0, header.status, "IMPORT reply status");
        UsbIpUsbDevice.readFrom(
                ByteBuffer.wrap(readN(in, UsbIpUsbDevice.BYTES)).order(ByteOrder.BIG_ENDIAN));
    }

    /** interrupt-IN CMD_SUBMIT: header ep=1/dir=IN -> endpoint address 0x81. */
    private static void sendInterruptInSubmit(OutputStream out, int seqnum, int length)
            throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(UsbIpHeaderBasic.BYTES + CmdSubmit.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        new UsbIpHeaderBasic(UsbIp.CMD_SUBMIT, seqnum, (1 << 16) | 1, UsbIp.DIR_IN, 1).writeTo(buf);
        new CmdSubmit(0, length, 0, 0xFFFFFFFF, 4, new byte[8]).writeTo(buf);
        out.write(buf.array());
        out.flush();
    }

    private static void sendUnlink(OutputStream out, int unlinkCmdSeqnum, int targetSeqnum)
            throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(UsbIpHeaderBasic.BYTES + CmdUnlink.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        new UsbIpHeaderBasic(UsbIp.CMD_UNLINK, unlinkCmdSeqnum, (1 << 16) | 1, UsbIp.DIR_IN, 1)
                .writeTo(buf);
        new CmdUnlink(targetSeqnum).writeTo(buf);
        out.write(buf.array());
        out.flush();
    }

    private static UsbIpHeaderBasic readBasic(InputStream in) throws IOException {
        return UsbIpHeaderBasic.readFrom(
                ByteBuffer.wrap(readN(in, UsbIpHeaderBasic.BYTES)).order(ByteOrder.BIG_ENDIAN));
    }

    /** Reads a full RET_SUBMIT: basic header + ret block + actualLength payload. */
    private static Ret readRetSubmit(InputStream in) throws IOException {
        UsbIpHeaderBasic basic = readBasic(in);
        assertEquals(UsbIp.RET_SUBMIT, basic.command, "reply is RET_SUBMIT");
        RetSubmit ret = RetSubmit.readFrom(
                ByteBuffer.wrap(readN(in, RetSubmit.BYTES)).order(ByteOrder.BIG_ENDIAN));
        byte[] payload = readN(in, ret.actualLength);
        return new Ret(basic.seqnum, ret.status, payload);
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new IOException("EOF after " + off + " of " + n + " bytes");
            }
            off += r;
        }
        return buf;
    }

    private static final class Ret {
        final int seqnum;
        final int status;
        final byte[] payload;

        Ret(int seqnum, int status, byte[] payload) {
            this.seqnum = seqnum;
            this.status = status;
            this.payload = payload;
        }
    }
}
