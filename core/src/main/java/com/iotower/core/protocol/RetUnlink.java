package com.iotower.core.protocol;

import java.nio.ByteBuffer;

/**
 * The 28-byte {@code USBIP_RET_UNLINK} command block that follows the
 * 20-byte {@link UsbIpHeaderBasic} (§4.2). Network byte order. {@code status}
 * is signed: 0 on success, else {@code -errno} (typically {@code -ECONNRESET}
 * for a successfully unlinked URB).
 */
public final class RetUnlink {
    public static final int BYTES = 28;
    private static final int PAD_LEN = 24;

    public final int status;

    public RetUnlink(int status) {
        this.status = status;
    }

    public void writeTo(ByteBuffer buf) {
        buf.putInt(status);
        for (int i = 0; i < PAD_LEN; i++) {
            buf.put((byte) 0);
        }
    }

    public static RetUnlink readFrom(ByteBuffer buf) {
        int status = buf.getInt();
        for (int i = 0; i < PAD_LEN; i++) {
            buf.get();
        }
        return new RetUnlink(status);
    }

    @Override public String toString() {
        return String.format("RetUnlink{status=%d}", status);
    }
}
