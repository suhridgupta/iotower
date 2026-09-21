package com.iotower.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact-byte assertions for the M1 struct codecs against expected bytes
 * hand-derived from the documented §4 / usbip_protocol.rst offsets,
 * independently of what the codecs emit. Catches offset/endianness/size
 * regressions that a self-consistent round-trip would not.
 */
class GoldenVectorTest {

    @Test
    void usbIpUsbDeviceMatchesDocumentedLayout() {
        UsbIpUsbDevice in = new UsbIpUsbDevice(
                "/sys/devices/tv/1-1", "1-1", 1, 2, 3,
                0x046d, 0xc294, 0x0100, 0, 0, 0, 1, 1, 1);

        ByteBuffer buf = ByteBuffer.allocate(UsbIpUsbDevice.BYTES).order(ByteOrder.BIG_ENDIAN);
        in.writeTo(buf);
        assertEquals(312, buf.position());
        byte[] bytes = buf.array();

        String path = "/sys/devices/tv/1-1";
        for (int i = 0; i < path.length(); i++) {
            assertEquals((byte) path.charAt(i), bytes[i], "path byte " + i);
        }
        assertEquals((byte) 0, bytes[19], "path NUL pad start");

        String busid = "1-1";
        for (int i = 0; i < busid.length(); i++) {
            assertEquals((byte) busid.charAt(i), bytes[256 + i], "busid byte " + i);
        }
        assertEquals((byte) 0, bytes[259], "busid NUL pad start");

        assertArrayEquals(new byte[] {0x00, 0x00, 0x00, 0x01}, slice(bytes, 288, 4), "busnum");
        assertArrayEquals(new byte[] {0x00, 0x00, 0x00, 0x02}, slice(bytes, 292, 4), "devnum");
        assertArrayEquals(new byte[] {0x00, 0x00, 0x00, 0x03}, slice(bytes, 296, 4), "speed");
        assertArrayEquals(new byte[] {0x04, 0x6d}, slice(bytes, 300, 2), "idVendor");
        assertArrayEquals(new byte[] {(byte) 0xc2, (byte) 0x94}, slice(bytes, 302, 2),
                "idProduct");
        assertArrayEquals(new byte[] {0x01, 0x00}, slice(bytes, 304, 2), "bcdDevice");
        assertEquals((byte) 0x00, bytes[306], "bDeviceClass");
        assertEquals((byte) 0x00, bytes[307], "bDeviceSubClass");
        assertEquals((byte) 0x00, bytes[308], "bDeviceProtocol");
        assertEquals((byte) 0x01, bytes[309], "bConfigurationValue");
        assertEquals((byte) 0x01, bytes[310], "bNumConfigurations");
        assertEquals((byte) 0x01, bytes[311], "bNumInterfaces");
    }

    @Test
    void cmdSubmitMatchesDocumentedLayout() {
        byte[] setup = {(byte) 0x80, 0x06, 0x00, 0x01, 0x00, 0x00, 0x12, 0x00};
        CmdSubmit in = new CmdSubmit(0, 18, 0, 0xFFFFFFFF, 0, setup);

        ByteBuffer buf = ByteBuffer.allocate(CmdSubmit.BYTES).order(ByteOrder.BIG_ENDIAN);
        in.writeTo(buf);

        byte[] expected = {
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x12,
                0x00, 0x00, 0x00, 0x00,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                0x00, 0x00, 0x00, 0x00,
                (byte) 0x80, 0x06, 0x00, 0x01, 0x00, 0x00, 0x12, 0x00
        };
        assertArrayEquals(expected, buf.array());
    }

    @Test
    void retSubmitMatchesDocumentedLayout() {
        RetSubmit in = new RetSubmit(0, 18, 0, 0, 0);

        ByteBuffer buf = ByteBuffer.allocate(RetSubmit.BYTES).order(ByteOrder.BIG_ENDIAN);
        in.writeTo(buf);

        byte[] expected = {
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x12,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
        assertArrayEquals(expected, buf.array());
    }

    @Test
    void cmdUnlinkMatchesDocumentedLayout() {
        CmdUnlink in = new CmdUnlink(42);

        ByteBuffer buf = ByteBuffer.allocate(CmdUnlink.BYTES).order(ByteOrder.BIG_ENDIAN);
        in.writeTo(buf);

        byte[] expected = new byte[28];
        expected[3] = 0x2a;
        assertArrayEquals(expected, buf.array());
    }

    @Test
    void retUnlinkMatchesDocumentedLayout() {
        RetUnlink in = new RetUnlink(0);

        ByteBuffer buf = ByteBuffer.allocate(RetUnlink.BYTES).order(ByteOrder.BIG_ENDIAN);
        in.writeTo(buf);

        byte[] expected = new byte[28];
        assertArrayEquals(expected, buf.array());
    }

    private static byte[] slice(byte[] src, int offset, int len) {
        byte[] out = new byte[len];
        System.arraycopy(src, offset, out, 0, len);
        return out;
    }
}
