package com.iotower.core.net;

import com.iotower.core.protocol.CmdSubmit;
import com.iotower.core.protocol.OpHeader;
import com.iotower.core.protocol.RetSubmit;
import com.iotower.core.protocol.UsbIp;
import com.iotower.core.protocol.UsbIpHeaderBasic;
import com.iotower.core.protocol.UsbIpUsbDevice;
import com.iotower.core.protocol.UsbIpUsbInterface;
import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * L1 integration test for v2 multi-device serving (§10, M12), driving the real
 * {@link UsbIpServer} over loopback with two in-test backends that share a
 * descriptor/VID:PID but have distinct bus addresses and interrupt-IN payloads.
 * Covers the three paths no single-device test can: multi-device DEVLIST,
 * concurrent clients with busid->backend routing, and the in-use guard.
 */
class MultiDeviceServerTest {

    /** Minimal one-HID-interface descriptor set (same shape as DevlistNegotiationTest). */
    private static byte[] descriptors() {
        return new byte[] {
                0x12, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x08,
                0x34, 0x12, 0x78, 0x56, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x09, 0x02, 0x22, 0x00, 0x01, 0x01, 0x00, (byte) 0x80, 0x32,
                0x09, 0x04, 0x00, 0x00, 0x01, 0x03, 0x00, 0x00, 0x00,
                0x09, 0x21, 0x11, 0x01, 0x00, 0x01, 0x22, 0x34, 0x00,
                0x07, 0x05, (byte) 0x81, 0x03, 0x08, 0x00, 0x0A,
        };
    }

    /** Identical identity for every instance; completes interrupt-IN with its own payload. */
    private static final class PadBackend implements UsbBackend {
        private final byte[] payload;

        PadBackend(byte marker) {
            this.payload = new byte[] {marker, marker, marker, marker};
        }

        @Override
        public byte[] rawDescriptors() {
            return descriptors();
        }

        @Override
        public DeviceInfo deviceInfo() {
            return new DeviceInfo(0x046d, 0xc21d, 0x4014, 0, 0, 0, 1, 1, 1, 2);
        }

        @Override
        public int controlTransfer(int requestType, int request, int value, int index,
                                   byte[] buffer, int length, int timeoutMillis) {
            return -1;
        }

        @Override
        public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
            UsbTransfer t = new UsbTransfer(null);
            t.completion.complete(new UsbTransfer.Result(0, payload, payload.length));
            return t;
        }

        @Override
        public void cancel(UsbTransfer transfer) { }

        @Override
        public void close() { }
    }

    private static UsbIpServer startTwoDeviceServer(Thread[] threadOut) throws Exception {
        UsbIpServer server = new UsbIpServer(0);
        server.addDevice(new PadBackend((byte) 0xAA), 1, 5);
        server.addDevice(new PadBackend((byte) 0xBB), 1, 7);
        Thread t = new Thread(server, "usbip-server-test");
        t.setDaemon(true);
        t.start();
        threadOut[0] = t;
        return server;
    }

    @Test
    void devlistAdvertisesAllRegisteredDevices() throws Exception {
        Thread[] th = new Thread[1];
        UsbIpServer server = startTwoDeviceServer(th);
        try {
            int port = server.awaitBoundPort(2000);
            try (Socket socket = new Socket("127.0.0.1", port)) {
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                ByteBuffer req = ByteBuffer.allocate(OpHeader.BYTES).order(ByteOrder.BIG_ENDIAN);
                new OpHeader(UsbIp.VERSION, UsbIp.OP_REQ_DEVLIST, 0).writeTo(req);
                out.write(req.array());
                out.flush();

                OpHeader header = OpHeader.readFrom(
                        ByteBuffer.wrap(readN(in, OpHeader.BYTES)).order(ByteOrder.BIG_ENDIAN));
                assertEquals(UsbIp.OP_REP_DEVLIST, header.code, "reply code");
                assertEquals(0, header.status, "reply status");
                assertEquals(2, ByteBuffer.wrap(readN(in, 4)).order(ByteOrder.BIG_ENDIAN).getInt(),
                        "exported device count");

                int[] devnums = {5, 7};
                for (int devnum : devnums) {
                    UsbIpUsbDevice d = UsbIpUsbDevice.readFrom(ByteBuffer
                            .wrap(readN(in, UsbIpUsbDevice.BYTES)).order(ByteOrder.BIG_ENDIAN));
                    assertEquals("1-" + devnum, d.busid, "busid");
                    assertEquals(1, d.busnum, "busnum");
                    assertEquals(devnum, d.devnum, "devnum");
                    assertEquals(0x046d, d.idVendor, "idVendor (identical across devices)");
                    assertEquals(1, d.bNumInterfaces, "bNumInterfaces");
                    UsbIpUsbInterface iface = UsbIpUsbInterface.readFrom(ByteBuffer
                            .wrap(readN(in, UsbIpUsbInterface.BYTES)).order(ByteOrder.BIG_ENDIAN));
                    assertEquals(0x03, iface.bInterfaceClass, "bInterfaceClass");
                }
                assertEquals(-1, in.read(), "server closes after the DEVLIST reply");
            }
        } finally {
            server.stop();
            th[0].join(2000);
        }
    }

    @Test
    void concurrentClientsReadTheirOwnDeviceIndependently() throws Exception {
        Thread[] th = new Thread[1];
        UsbIpServer server = startTwoDeviceServer(th);
        try {
            int port = server.awaitBoundPort(2000);
            try (Socket s1 = new Socket("127.0.0.1", port);
                 Socket s2 = new Socket("127.0.0.1", port)) {
                // Both connections are live at the same time.
                int devid1 = importOk(s1, "1-5");
                int devid2 = importOk(s2, "1-7");
                assertEquals((1 << 16) | 5, devid1);
                assertEquals((1 << 16) | 7, devid2);

                sendInterruptInSubmit(s1.getOutputStream(), 1, devid1);
                sendInterruptInSubmit(s2.getOutputStream(), 1, devid2);

                UsbIpHeaderBasic b1 = readBasic(s1.getInputStream());
                byte[] p1 = readRetPayload(s1.getInputStream());
                UsbIpHeaderBasic b2 = readBasic(s2.getInputStream());
                byte[] p2 = readRetPayload(s2.getInputStream());

                assertEquals(devid1, b1.devid, "conn1 RET_SUBMIT devid");
                assertArrayEquals(new byte[] {(byte) 0xAA, (byte) 0xAA, (byte) 0xAA, (byte) 0xAA},
                        p1, "conn1 carries device 1-5's payload");
                assertEquals(devid2, b2.devid, "conn2 RET_SUBMIT devid");
                assertArrayEquals(new byte[] {(byte) 0xBB, (byte) 0xBB, (byte) 0xBB, (byte) 0xBB},
                        p2, "conn2 carries device 1-7's payload");
            }
        } finally {
            server.stop();
            th[0].join(2000);
        }
    }

    @Test
    void importOfInUseBusidIsRejected() throws Exception {
        Thread[] th = new Thread[1];
        UsbIpServer server = startTwoDeviceServer(th);
        try {
            int port = server.awaitBoundPort(2000);
            try (Socket s1 = new Socket("127.0.0.1", port);
                 Socket s2 = new Socket("127.0.0.1", port)) {
                int devid1 = importOk(s1, "1-5"); // conn1 now held in the transfer phase

                sendImport(s2.getOutputStream(), "1-5");
                OpHeader rej = OpHeader.readFrom(ByteBuffer
                        .wrap(readN(s2.getInputStream(), OpHeader.BYTES)).order(ByteOrder.BIG_ENDIAN));
                assertEquals(UsbIp.OP_REP_IMPORT, rej.code, "reply code");
                assertNotEquals(0, rej.status, "second import of an in-use busid must fail");

                // conn1 is still served.
                sendInterruptInSubmit(s1.getOutputStream(), 9, devid1);
                UsbIpHeaderBasic b1 = readBasic(s1.getInputStream());
                readRetPayload(s1.getInputStream());
                assertEquals(9, b1.seqnum, "conn1 still answers after conn2's rejection");
            }
        } finally {
            server.stop();
            th[0].join(2000);
        }
    }

    // --- helpers -----------------------------------------------------------

    private static void sendImport(OutputStream out, String busid) throws IOException {
        ByteBuffer req = ByteBuffer.allocate(OpHeader.BYTES + 32).order(ByteOrder.BIG_ENDIAN);
        new OpHeader(UsbIp.VERSION, UsbIp.OP_REQ_IMPORT, 0).writeTo(req);
        byte[] b = new byte[32];
        byte[] id = busid.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, b, 0, id.length);
        req.put(b);
        out.write(req.array());
        out.flush();
    }

    /** IMPORTs {@code busid}, asserts success, returns the device's devid. */
    private static int importOk(Socket s, String busid) throws IOException {
        sendImport(s.getOutputStream(), busid);
        InputStream in = s.getInputStream();
        OpHeader h = OpHeader.readFrom(
                ByteBuffer.wrap(readN(in, OpHeader.BYTES)).order(ByteOrder.BIG_ENDIAN));
        assertEquals(UsbIp.OP_REP_IMPORT, h.code, "IMPORT reply code");
        assertEquals(0, h.status, "IMPORT reply status for " + busid);
        UsbIpUsbDevice d = UsbIpUsbDevice.readFrom(
                ByteBuffer.wrap(readN(in, UsbIpUsbDevice.BYTES)).order(ByteOrder.BIG_ENDIAN));
        assertEquals(busid, d.busid, "imported busid");
        return (d.busnum << 16) | d.devnum;
    }

    private static void sendInterruptInSubmit(OutputStream out, int seqnum, int devid)
            throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(UsbIpHeaderBasic.BYTES + CmdSubmit.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        new UsbIpHeaderBasic(UsbIp.CMD_SUBMIT, seqnum, devid, UsbIp.DIR_IN, 1).writeTo(buf);
        new CmdSubmit(0, 8, 0, 0xFFFFFFFF, 4, new byte[8]).writeTo(buf);
        out.write(buf.array());
        out.flush();
    }

    private static UsbIpHeaderBasic readBasic(InputStream in) throws IOException {
        UsbIpHeaderBasic basic = UsbIpHeaderBasic.readFrom(
                ByteBuffer.wrap(readN(in, UsbIpHeaderBasic.BYTES)).order(ByteOrder.BIG_ENDIAN));
        assertEquals(UsbIp.RET_SUBMIT, basic.command, "reply is RET_SUBMIT");
        return basic;
    }

    /** Reads the RET_SUBMIT block + payload that follows {@link #readBasic}. */
    private static byte[] readRetPayload(InputStream in) throws IOException {
        RetSubmit ret = RetSubmit.readFrom(
                ByteBuffer.wrap(readN(in, RetSubmit.BYTES)).order(ByteOrder.BIG_ENDIAN));
        assertEquals(0, ret.status, "RET_SUBMIT status");
        return readN(in, ret.actualLength);
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
}
