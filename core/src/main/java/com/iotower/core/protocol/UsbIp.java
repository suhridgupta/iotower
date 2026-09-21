package com.iotower.core.protocol;

/**
 * USB/IP wire constants. See architecture.md §4 and the kernel's
 * {@code Documentation/usb/usbip_protocol.rst} (authoritative). All multi-byte
 * fields are network byte order (big-endian).
 */
public final class UsbIp {
    private UsbIp() {}

    /** Protocol version reported in the negotiation header. */
    public static final int VERSION = 0x0111;

    // Negotiation op codes (§4.1)
    public static final int OP_REQ_DEVLIST = 0x8005;
    public static final int OP_REP_DEVLIST = 0x0005;
    public static final int OP_REQ_IMPORT  = 0x8003;
    public static final int OP_REP_IMPORT  = 0x0003;

    // Transfer-phase commands (§4.2)
    public static final int CMD_SUBMIT = 1;
    public static final int CMD_UNLINK = 2;
    public static final int RET_SUBMIT = 3;
    public static final int RET_UNLINK = 4;

    // Direction
    public static final int DIR_OUT = 0;
    public static final int DIR_IN  = 1;

    public static final int STATUS_OK = 0;

    /** Registered USB/IP TCP port. */
    public static final int PORT = 3240;
}
