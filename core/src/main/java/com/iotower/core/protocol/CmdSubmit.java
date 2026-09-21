package com.iotower.core.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The 28-byte {@code USBIP_CMD_SUBMIT} command block that follows the
 * 20-byte {@link UsbIpHeaderBasic} (§4.2). Network byte order.
 */
public final class CmdSubmit {
    public static final int BYTES = 28;
    private static final int SETUP_LEN = 8;

    public final int transferFlags;
    public final int transferBufferLength;
    public final int startFrame;
    public final int numberOfPackets;
    public final int interval;
    public final byte[] setup;

    public CmdSubmit(int transferFlags, int transferBufferLength, int startFrame,
            int numberOfPackets, int interval, byte[] setup) {
        this.transferFlags = transferFlags;
        this.transferBufferLength = transferBufferLength;
        this.startFrame = startFrame;
        this.numberOfPackets = numberOfPackets;
        this.interval = interval;
        this.setup = Arrays.copyOf(setup, SETUP_LEN);
    }

    public void writeTo(ByteBuffer buf) {
        buf.putInt(transferFlags);
        buf.putInt(transferBufferLength);
        buf.putInt(startFrame);
        buf.putInt(numberOfPackets);
        buf.putInt(interval);
        buf.put(setup, 0, SETUP_LEN);
    }

    public static CmdSubmit readFrom(ByteBuffer buf) {
        int transferFlags = buf.getInt();
        int transferBufferLength = buf.getInt();
        int startFrame = buf.getInt();
        int numberOfPackets = buf.getInt();
        int interval = buf.getInt();
        byte[] setup = new byte[SETUP_LEN];
        buf.get(setup);
        return new CmdSubmit(transferFlags, transferBufferLength, startFrame, numberOfPackets,
                interval, setup);
    }

    @Override public String toString() {
        return String.format(
                "CmdSubmit{flags=0x%x, bufLen=%d, startFrame=%d, numPackets=0x%x, "
                        + "interval=%d, setup=%s}",
                transferFlags, transferBufferLength, startFrame, numberOfPackets, interval,
                Arrays.toString(setup));
    }
}
