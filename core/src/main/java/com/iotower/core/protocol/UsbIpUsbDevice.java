package com.iotower.core.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * {@code usbip_usb_device} — the 312-byte device descriptor block used in
 * {@code OP_REP_DEVLIST} / {@code OP_REP_IMPORT} (§4.1). Network byte order.
 *
 * <p>{@code path} and {@code busid} are fixed-width ASCII char arrays on the
 * wire (256 and 32 bytes respectively), NUL-padded. On write, a value longer
 * than the field width is truncated to fit; on read, trailing NUL bytes are
 * trimmed and the result returned as a {@code String}.
 */
public final class UsbIpUsbDevice {
    public static final int BYTES = 312;

    private static final int PATH_LEN = 256;
    private static final int BUSID_LEN = 32;

    public final String path;
    public final String busid;
    public final int busnum;
    public final int devnum;
    public final int speed;
    public final int idVendor;
    public final int idProduct;
    public final int bcdDevice;
    public final int bDeviceClass;
    public final int bDeviceSubClass;
    public final int bDeviceProtocol;
    public final int bConfigurationValue;
    public final int bNumConfigurations;
    public final int bNumInterfaces;

    public UsbIpUsbDevice(String path, String busid, int busnum, int devnum, int speed,
            int idVendor, int idProduct, int bcdDevice, int bDeviceClass, int bDeviceSubClass,
            int bDeviceProtocol, int bConfigurationValue, int bNumConfigurations,
            int bNumInterfaces) {
        this.path = path;
        this.busid = busid;
        this.busnum = busnum;
        this.devnum = devnum;
        this.speed = speed;
        this.idVendor = idVendor;
        this.idProduct = idProduct;
        this.bcdDevice = bcdDevice;
        this.bDeviceClass = bDeviceClass;
        this.bDeviceSubClass = bDeviceSubClass;
        this.bDeviceProtocol = bDeviceProtocol;
        this.bConfigurationValue = bConfigurationValue;
        this.bNumConfigurations = bNumConfigurations;
        this.bNumInterfaces = bNumInterfaces;
    }

    public void writeTo(ByteBuffer buf) {
        putFixedAscii(buf, path, PATH_LEN);
        putFixedAscii(buf, busid, BUSID_LEN);
        buf.putInt(busnum);
        buf.putInt(devnum);
        buf.putInt(speed);
        buf.putShort((short) idVendor);
        buf.putShort((short) idProduct);
        buf.putShort((short) bcdDevice);
        buf.put((byte) bDeviceClass);
        buf.put((byte) bDeviceSubClass);
        buf.put((byte) bDeviceProtocol);
        buf.put((byte) bConfigurationValue);
        buf.put((byte) bNumConfigurations);
        buf.put((byte) bNumInterfaces);
    }

    public static UsbIpUsbDevice readFrom(ByteBuffer buf) {
        String path = getFixedAscii(buf, PATH_LEN);
        String busid = getFixedAscii(buf, BUSID_LEN);
        int busnum = buf.getInt();
        int devnum = buf.getInt();
        int speed = buf.getInt();
        int idVendor = buf.getShort() & 0xFFFF;
        int idProduct = buf.getShort() & 0xFFFF;
        int bcdDevice = buf.getShort() & 0xFFFF;
        int bDeviceClass = buf.get() & 0xFF;
        int bDeviceSubClass = buf.get() & 0xFF;
        int bDeviceProtocol = buf.get() & 0xFF;
        int bConfigurationValue = buf.get() & 0xFF;
        int bNumConfigurations = buf.get() & 0xFF;
        int bNumInterfaces = buf.get() & 0xFF;
        return new UsbIpUsbDevice(path, busid, busnum, devnum, speed, idVendor, idProduct,
                bcdDevice, bDeviceClass, bDeviceSubClass, bDeviceProtocol, bConfigurationValue,
                bNumConfigurations, bNumInterfaces);
    }

    private static void putFixedAscii(ByteBuffer buf, String s, int width) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(bytes.length, width);
        buf.put(bytes, 0, n);
        for (int i = n; i < width; i++) {
            buf.put((byte) 0);
        }
    }

    private static String getFixedAscii(ByteBuffer buf, int width) {
        byte[] bytes = new byte[width];
        buf.get(bytes);
        int len = 0;
        while (len < width && bytes[len] != 0) {
            len++;
        }
        return new String(bytes, 0, len, StandardCharsets.US_ASCII);
    }

    @Override public String toString() {
        return String.format(
                "UsbIpUsbDevice{path=%s, busid=%s, busnum=%d, devnum=%d, speed=%d, "
                        + "idVendor=0x%04x, idProduct=0x%04x, bcdDevice=0x%04x, class=%d, "
                        + "subClass=%d, protocol=%d, config=%d, numConfigs=%d, numIfaces=%d}",
                path, busid, busnum, devnum, speed, idVendor, idProduct, bcdDevice,
                bDeviceClass, bDeviceSubClass, bDeviceProtocol, bConfigurationValue,
                bNumConfigurations, bNumInterfaces);
    }
}
