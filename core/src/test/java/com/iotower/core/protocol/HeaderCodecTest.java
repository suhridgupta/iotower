package com.iotower.core.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Round-trip and byte-order tests for the wire framing (§4). */
class HeaderCodecTest {

    @Test
    void basicHeaderRoundTrips() {
        UsbIpHeaderBasic in = new UsbIpHeaderBasic(
                UsbIp.CMD_SUBMIT, 42, (1 << 16) | 2, UsbIp.DIR_IN, 0x81);

        ByteBuffer buf = ByteBuffer.allocate(UsbIpHeaderBasic.BYTES).order(ByteOrder.BIG_ENDIAN);
        in.writeTo(buf);
        buf.flip();

        UsbIpHeaderBasic out = UsbIpHeaderBasic.readFrom(buf);
        assertEquals(in.command, out.command);
        assertEquals(in.seqnum, out.seqnum);
        assertEquals(in.devid, out.devid);
        assertEquals(in.direction, out.direction);
        assertEquals(in.ep, out.ep);
    }

    @Test
    void basicHeaderIsBigEndianOnTheWire() {
        UsbIpHeaderBasic in = new UsbIpHeaderBasic(UsbIp.RET_SUBMIT, 1, 0, UsbIp.DIR_OUT, 0);
        ByteBuffer buf = ByteBuffer.allocate(UsbIpHeaderBasic.BYTES).order(ByteOrder.BIG_ENDIAN);
        in.writeTo(buf);
        // command == 3 encoded big-endian: three zero bytes then 0x03.
        assertEquals(0, buf.get(0));
        assertEquals(0, buf.get(1));
        assertEquals(0, buf.get(2));
        assertEquals(3, buf.get(3));
    }

    @Test
    void opHeaderRoundTrips() {
        OpHeader in = new OpHeader(UsbIp.VERSION, UsbIp.OP_REQ_IMPORT, UsbIp.STATUS_OK);
        ByteBuffer buf = ByteBuffer.allocate(OpHeader.BYTES).order(ByteOrder.BIG_ENDIAN);
        in.writeTo(buf);
        buf.flip();

        OpHeader out = OpHeader.readFrom(buf);
        assertEquals(UsbIp.VERSION, out.version);
        assertEquals(UsbIp.OP_REQ_IMPORT, out.code);
        assertEquals(UsbIp.STATUS_OK, out.status);
    }
}
