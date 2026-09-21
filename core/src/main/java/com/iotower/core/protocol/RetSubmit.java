package com.iotower.core.protocol;

import java.nio.ByteBuffer;

/**
 * The 28-byte {@code USBIP_RET_SUBMIT} command block that follows the
 * 20-byte {@link UsbIpHeaderBasic} (§4.2). Network byte order. {@code status}
 * is signed: 0 on success, else {@code -errno}.
 */
public final class RetSubmit {
    public static final int BYTES = 28;
    private static final int PAD_LEN = 8;

    public final int status;
    public final int actualLength;
    public final int startFrame;
    public final int numberOfPackets;
    public final int errorCount;

    public RetSubmit(int status, int actualLength, int startFrame, int numberOfPackets,
            int errorCount) {
        this.status = status;
        this.actualLength = actualLength;
        this.startFrame = startFrame;
        this.numberOfPackets = numberOfPackets;
        this.errorCount = errorCount;
    }

    public void writeTo(ByteBuffer buf) {
        buf.putInt(status);
        buf.putInt(actualLength);
        buf.putInt(startFrame);
        buf.putInt(numberOfPackets);
        buf.putInt(errorCount);
        for (int i = 0; i < PAD_LEN; i++) {
            buf.put((byte) 0);
        }
    }

    public static RetSubmit readFrom(ByteBuffer buf) {
        int status = buf.getInt();
        int actualLength = buf.getInt();
        int startFrame = buf.getInt();
        int numberOfPackets = buf.getInt();
        int errorCount = buf.getInt();
        for (int i = 0; i < PAD_LEN; i++) {
            buf.get();
        }
        return new RetSubmit(status, actualLength, startFrame, numberOfPackets, errorCount);
    }

    @Override public String toString() {
        return String.format(
                "RetSubmit{status=%d, actualLength=%d, startFrame=%d, numPackets=%d, "
                        + "errorCount=%d}",
                status, actualLength, startFrame, numberOfPackets, errorCount);
    }
}
