package com.iotower.core.protocol;

import java.nio.ByteBuffer;

/**
 * The 8-byte common header prefixing every negotiation request/reply (§4.1):
 * {@code u16 version, u16 code, u32 status}. Network byte order.
 */
public final class OpHeader {
    public static final int BYTES = 8;

    public final int version;
    public final int code;
    public final int status;

    public OpHeader(int version, int code, int status) {
        this.version = version;
        this.code = code;
        this.status = status;
    }

    public void writeTo(ByteBuffer buf) {
        buf.putShort((short) version);
        buf.putShort((short) code);
        buf.putInt(status);
    }

    public static OpHeader readFrom(ByteBuffer buf) {
        int version = buf.getShort() & 0xFFFF;
        int code = buf.getShort() & 0xFFFF;
        int status = buf.getInt();
        return new OpHeader(version, code, status);
    }

    @Override public String toString() {
        return String.format("OpHeader{version=0x%04x, code=0x%04x, status=%d}", version, code, status);
    }
}
