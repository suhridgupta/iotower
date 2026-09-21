package com.iotower.core.protocol;

import java.nio.ByteBuffer;

/**
 * The 28-byte {@code USBIP_CMD_UNLINK} command block that follows the
 * 20-byte {@link UsbIpHeaderBasic} (§4.2). Network byte order.
 *
 * <p>{@code unlinkSeqnum} is the seqnum of the SUBMIT being cancelled — not
 * the seqnum of this UNLINK command itself, which lives in the enclosing
 * {@link UsbIpHeaderBasic}.
 */
public final class CmdUnlink {
    public static final int BYTES = 28;
    private static final int PAD_LEN = 24;

    public final int unlinkSeqnum;

    public CmdUnlink(int unlinkSeqnum) {
        this.unlinkSeqnum = unlinkSeqnum;
    }

    public void writeTo(ByteBuffer buf) {
        buf.putInt(unlinkSeqnum);
        for (int i = 0; i < PAD_LEN; i++) {
            buf.put((byte) 0);
        }
    }

    public static CmdUnlink readFrom(ByteBuffer buf) {
        int unlinkSeqnum = buf.getInt();
        for (int i = 0; i < PAD_LEN; i++) {
            buf.get();
        }
        return new CmdUnlink(unlinkSeqnum);
    }

    @Override public String toString() {
        return String.format("CmdUnlink{unlinkSeqnum=%d}", unlinkSeqnum);
    }
}
