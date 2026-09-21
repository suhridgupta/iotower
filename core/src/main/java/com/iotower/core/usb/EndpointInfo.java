package com.iotower.core.usb;

/**
 * One endpoint as recorded from the descriptors (§5): address, type, direction,
 * max packet size, polling interval. URBs are routed by {@code (address,
 * direction)} against the {@link EndpointMap}, never by device identity.
 */
public final class EndpointInfo {
    public static final int TYPE_CONTROL = 0;
    public static final int TYPE_ISO = 1;
    public static final int TYPE_BULK = 2;
    public static final int TYPE_INTERRUPT = 3;

    public final int address;      // includes direction bit (0x80 = IN)
    public final int type;
    public final int direction;    // UsbIp.DIR_IN / DIR_OUT
    public final int maxPacketSize;
    public final int interval;

    public EndpointInfo(int address, int type, int direction, int maxPacketSize, int interval) {
        this.address = address;
        this.type = type;
        this.direction = direction;
        this.maxPacketSize = maxPacketSize;
        this.interval = interval;
    }
}
