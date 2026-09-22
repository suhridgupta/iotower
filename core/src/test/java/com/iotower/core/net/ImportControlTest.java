package com.iotower.core.net;

import com.iotower.core.protocol.CmdSubmit;
import com.iotower.core.protocol.OpHeader;
import com.iotower.core.protocol.RetSubmit;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * L1 integration test for the M4 IMPORT + control-transfer path (§4.1, §4.2):
 * proves IMPORT parse/match, the {@code OP_REP_IMPORT} framing, the phase
 * switch into the transfer phase, ep0 setup decode, forwarding to
 * {@code controlTransfer}, and {@code RET_SUBMIT} framing + IN payload — none
 * of which any existing test covers (M1 = struct codecs in isolation; M3 =
 * DEVLIST only, never the transfer phase).
 */
class ImportControlTest {

    /**
     * Device descriptor (18 bytes, byte-identical in its first 18 bytes to
     * {@code DevlistNegotiationTest}'s fixture) followed by a 48-byte
     * configuration block (66 bytes total) — mirroring the shape of the real
     * F310 capture ({@code testdata/simple-gamepad-descriptors.bin}, Approach
     * §5) so this test's control-transfer offsets/lengths (18-byte DEVICE,
     * 48-byte CONFIGURATION, wTotalLength 0x0030) match what L2 exercises
     * against the desktop harness. IMPORT + control transfers never run this
     * blob through {@code DescriptorParser} (only DEVLIST does), so the
     * configuration bytes beyond its 9-byte header need not form a fully
     * parseable interface/endpoint chain — only the wTotalLength field at
     * offset 18+2 is load-bearing here.
     */
    private static byte[] fixtureSimpleGamepad() {
        byte[] device = {
                // --- Device descriptor (bDescriptorType 0x01), 18 bytes ---
                0x12, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x08,
                0x34, 0x12, 0x78, 0x56, 0x00, 0x01, 0x00, 0x00,
                0x00, 0x01,
        };
        byte[] config = new byte[48];
        // --- Configuration descriptor header (0x02), 9 bytes ---
        config[0] = 0x09;        // bLength
        config[1] = 0x02;        // bDescriptorType = CONFIGURATION
        config[2] = 0x30;        // wTotalLength LE lo = 48
        config[3] = 0x00;        // wTotalLength LE hi
        config[4] = 0x01;        // bNumInterfaces
        config[5] = 0x01;        // bConfigurationValue
        config[6] = 0x00;        // iConfiguration
        config[7] = (byte) 0x80; // bmAttributes (bus-powered)
        config[8] = 0x32;        // bMaxPower = 100 mA
        // Remaining 39 bytes: deterministic filler standing in for the
        // interface/HID/endpoint sub-descriptors the real capture carries.
        for (int i = 9; i < config.length; i++) {
            config[i] = (byte) (i - 9);
        }

        byte[] blob = new byte[device.length + config.length];
        System.arraycopy(device, 0, blob, 0, device.length);
        System.arraycopy(config, 0, blob, device.length, config.length);
        return blob;
    }

    private static final class FakeBackend implements UsbBackend {
        private static final int REQ_TYPE_GET_DESCRIPTOR = 0x80;
        private static final int REQUEST_GET_DESCRIPTOR = 0x06;
        private static final int DESC_TYPE_DEVICE = 0x01;
        private static final int DESC_TYPE_CONFIGURATION = 0x02;

        @Override
        public byte[] rawDescriptors() {
            return fixtureSimpleGamepad();
        }

        @Override
        public DeviceInfo deviceInfo() {
            return new DeviceInfo(0x046d, 0xc294, 0x0100, 0, 0, 0, 1, 1, 1, 3);
        }

        @Override
        public int controlTransfer(int requestType, int request, int value, int index,
                                   byte[] buffer, int length, int timeoutMillis) {
            if (requestType == REQ_TYPE_GET_DESCRIPTOR && request == REQUEST_GET_DESCRIPTOR) {
                int descriptorType = (value >> 8) & 0xFF;
                byte[] blob = rawDescriptors();
                if (descriptorType == DESC_TYPE_DEVICE) {
                    int n = Math.min(Math.min(18, length), buffer.length);
                    System.arraycopy(blob, 0, buffer, 0, n);
                    return n;
                }
                if (descriptorType == DESC_TYPE_CONFIGURATION) {
                    int wTotalLength = (blob[18 + 2] & 0xFF) | ((blob[18 + 3] & 0xFF) << 8);
                    int n = Math.min(Math.min(wTotalLength, length), buffer.length);
                    System.arraycopy(blob, 18, buffer, 0, n);
                    return n;
                }
            }
            return -1;
        }

        @Override
        public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
            throw new UnsupportedOperationException("not used by M4 control-only test");
        }

        @Override
        public void cancel(UsbTransfer transfer) {
            throw new UnsupportedOperationException("not used by M4 control-only test");
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("not used by M4 control-only test");
        }
    }

    @Test
    void importThenControlDescriptors() throws Exception {
        UsbIpServer server = new UsbIpServer(new FakeBackend(), 0);
        Thread serverThread = new Thread(server, "usbip-server-test");
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            int port = server.awaitBoundPort(2000);

            try (Socket socket = new Socket("127.0.0.1", port)) {
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                // --- OP_REQ_IMPORT "1-1" ---
                ByteBuffer request = ByteBuffer.allocate(OpHeader.BYTES + 32)
                        .order(ByteOrder.BIG_ENDIAN);
                new OpHeader(UsbIp.VERSION, UsbIp.OP_REQ_IMPORT, 0).writeTo(request);
                byte[] busid = new byte[32];
                byte[] busidBytes = "1-1".getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(busidBytes, 0, busid, 0, busidBytes.length);
                request.put(busid);
                out.write(request.array());
                out.flush();

                byte[] headerBytes = readFully(in, OpHeader.BYTES);
                OpHeader header = OpHeader.readFrom(
                        ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN));
                assertEquals(0x0111, header.version, "reply version");
                assertEquals(UsbIp.OP_REP_IMPORT, header.code, "reply code");
                assertEquals(0, header.status, "reply status");

                byte[] deviceBytes = readFully(in, UsbIpUsbDevice.BYTES);
                UsbIpUsbDevice device = UsbIpUsbDevice.readFrom(
                        ByteBuffer.wrap(deviceBytes).order(ByteOrder.BIG_ENDIAN));
                assertEquals("1-1", device.busid, "busid");
                assertEquals(1, device.busnum, "busnum");
                assertEquals(1, device.devnum, "devnum");
                assertEquals(0x046d, device.idVendor, "idVendor");
                assertEquals(0xc294, device.idProduct, "idProduct");
                assertEquals(1, device.bNumInterfaces, "bNumInterfaces");

                int exportedDevid = (1 << 16) | 1;

                // --- CMD_SUBMIT: GET_DESCRIPTOR(DEVICE), seqnum 1 ---
                sendControlSubmit(out, 1, exportedDevid, 18,
                        new byte[] {(byte) 0x80, 0x06, 0x00, 0x01, 0x00, 0x00, 0x12, 0x00});

                byte[] basicBytes1 = readFully(in, UsbIpHeaderBasic.BYTES);
                UsbIpHeaderBasic basic1 = UsbIpHeaderBasic.readFrom(
                        ByteBuffer.wrap(basicBytes1).order(ByteOrder.BIG_ENDIAN));
                assertEquals(UsbIp.RET_SUBMIT, basic1.command, "ret1 command");
                assertEquals(1, basic1.seqnum, "ret1 seqnum");
                assertEquals(exportedDevid, basic1.devid, "ret1 devid");

                byte[] retBytes1 = readFully(in, RetSubmit.BYTES);
                RetSubmit ret1 = RetSubmit.readFrom(
                        ByteBuffer.wrap(retBytes1).order(ByteOrder.BIG_ENDIAN));
                assertEquals(0, ret1.status, "ret1 status");
                assertEquals(18, ret1.actualLength, "ret1 actualLength");

                byte[] payload1 = readFully(in, 18);
                byte[] expected1 = new byte[18];
                System.arraycopy(fixtureSimpleGamepad(), 0, expected1, 0, 18);
                assertArrayEquals(expected1, payload1, "device descriptor payload");

                // --- CMD_SUBMIT: GET_DESCRIPTOR(CONFIGURATION), seqnum 2 ---
                sendControlSubmit(out, 2, exportedDevid, 48,
                        new byte[] {(byte) 0x80, 0x06, 0x00, 0x02, 0x00, 0x00, 0x30, 0x00});

                byte[] basicBytes2 = readFully(in, UsbIpHeaderBasic.BYTES);
                UsbIpHeaderBasic basic2 = UsbIpHeaderBasic.readFrom(
                        ByteBuffer.wrap(basicBytes2).order(ByteOrder.BIG_ENDIAN));
                assertEquals(UsbIp.RET_SUBMIT, basic2.command, "ret2 command");
                assertEquals(2, basic2.seqnum, "ret2 seqnum");

                byte[] retBytes2 = readFully(in, RetSubmit.BYTES);
                RetSubmit ret2 = RetSubmit.readFrom(
                        ByteBuffer.wrap(retBytes2).order(ByteOrder.BIG_ENDIAN));
                assertEquals(0, ret2.status, "ret2 status");
                assertEquals(48, ret2.actualLength, "ret2 actualLength (wTotalLength=0x0030=48)");

                byte[] payload2 = readFully(in, 48);
                byte[] fixture = fixtureSimpleGamepad();
                byte[] expected2 = new byte[48];
                System.arraycopy(fixture, 18, expected2, 0, 48);
                assertArrayEquals(expected2, payload2, "configuration block payload");
            }
        } finally {
            server.stop();
            serverThread.join(2000);
        }
    }

    @Test
    void importUnknownBusidRejected() throws Exception {
        UsbIpServer server = new UsbIpServer(new FakeBackend(), 0);
        Thread serverThread = new Thread(server, "usbip-server-test");
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            int port = server.awaitBoundPort(2000);

            try (Socket socket = new Socket("127.0.0.1", port)) {
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                ByteBuffer request = ByteBuffer.allocate(OpHeader.BYTES + 32)
                        .order(ByteOrder.BIG_ENDIAN);
                new OpHeader(UsbIp.VERSION, UsbIp.OP_REQ_IMPORT, 0).writeTo(request);
                byte[] busid = new byte[32];
                byte[] busidBytes = "9-9".getBytes(StandardCharsets.US_ASCII);
                System.arraycopy(busidBytes, 0, busid, 0, busidBytes.length);
                request.put(busid);
                out.write(request.array());
                out.flush();

                byte[] headerBytes = readFully(in, OpHeader.BYTES);
                OpHeader header = OpHeader.readFrom(
                        ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN));
                assertEquals(UsbIp.OP_REP_IMPORT, header.code, "reply code");
                assertNotEquals(0, header.status, "reply status must be non-zero");

                assertEquals(-1, in.read(), "server must close the connection after rejecting IMPORT");
            }
        } finally {
            server.stop();
            serverThread.join(2000);
        }
    }

    private static void sendControlSubmit(OutputStream out, int seqnum, int devid,
            int transferBufferLength, byte[] setup) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(UsbIpHeaderBasic.BYTES + CmdSubmit.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        new UsbIpHeaderBasic(UsbIp.CMD_SUBMIT, seqnum, devid, UsbIp.DIR_IN, 0).writeTo(buf);
        new CmdSubmit(0, transferBufferLength, 0, 0xFFFFFFFF, 0, setup).writeTo(buf);
        out.write(buf.array());
        out.flush();
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int read = in.read(buf, off, length - off);
            if (read < 0) {
                throw new IOException("EOF after " + off + " of " + length + " bytes");
            }
            off += read;
        }
        return buf;
    }
}
