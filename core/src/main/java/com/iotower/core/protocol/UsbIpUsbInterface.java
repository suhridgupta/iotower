package com.iotower.core.protocol;

import java.nio.ByteBuffer;

/**
 * {@code usbip_usb_interface} — the 4-byte per-interface block that follows a
 * {@link UsbIpUsbDevice} in a devlist reply (§4.1). Network byte order.
 */
public final class UsbIpUsbInterface {
    public static final int BYTES = 4;

    public final int bInterfaceClass;
    public final int bInterfaceSubClass;
    public final int bInterfaceProtocol;
    public final int padding;

    public UsbIpUsbInterface(int bInterfaceClass, int bInterfaceSubClass,
            int bInterfaceProtocol, int padding) {
        this.bInterfaceClass = bInterfaceClass;
        this.bInterfaceSubClass = bInterfaceSubClass;
        this.bInterfaceProtocol = bInterfaceProtocol;
        this.padding = padding;
    }

    public void writeTo(ByteBuffer buf) {
        buf.put((byte) bInterfaceClass);
        buf.put((byte) bInterfaceSubClass);
        buf.put((byte) bInterfaceProtocol);
        buf.put((byte) 0);
    }

    public static UsbIpUsbInterface readFrom(ByteBuffer buf) {
        int bInterfaceClass = buf.get() & 0xFF;
        int bInterfaceSubClass = buf.get() & 0xFF;
        int bInterfaceProtocol = buf.get() & 0xFF;
        int padding = buf.get() & 0xFF;
        return new UsbIpUsbInterface(bInterfaceClass, bInterfaceSubClass, bInterfaceProtocol,
                padding);
    }

    @Override public String toString() {
        return String.format("UsbIpUsbInterface{class=%d, subClass=%d, protocol=%d}",
                bInterfaceClass, bInterfaceSubClass, bInterfaceProtocol);
    }
}
