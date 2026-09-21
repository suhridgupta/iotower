package com.iotower.core.protocol;

import java.nio.ByteBuffer;

/**
 * {@code usbip_header_basic} — the 20-byte header that starts every
 * transfer-phase message (§4.2). Network byte order.
 */
public final class UsbIpHeaderBasic {
    public static final int BYTES = 20;

    public final int command;   // CMD_SUBMIT / CMD_UNLINK / RET_SUBMIT / RET_UNLINK
    public final int seqnum;    // per-URB id; echoed in the matching reply
    public final int devid;     // (busnum << 16) | devnum
    public final int direction; // DIR_OUT / DIR_IN
    public final int ep;        // endpoint number

    public UsbIpHeaderBasic(int command, int seqnum, int devid, int direction, int ep) {
        this.command = command;
        this.seqnum = seqnum;
        this.devid = devid;
        this.direction = direction;
        this.ep = ep;
    }

    public void writeTo(ByteBuffer buf) {
        buf.putInt(command);
        buf.putInt(seqnum);
        buf.putInt(devid);
        buf.putInt(direction);
        buf.putInt(ep);
    }

    public static UsbIpHeaderBasic readFrom(ByteBuffer buf) {
        return new UsbIpHeaderBasic(
                buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt());
    }

    @Override public String toString() {
        return String.format("UsbIpHeaderBasic{cmd=%d, seqnum=%d, devid=0x%08x, dir=%d, ep=%d}",
                command, seqnum, devid, direction, ep);
    }
}
