package com.iotower.core.net;

import com.iotower.core.usb.UsbBackend;

/**
 * One device the server exports: a {@link UsbBackend} plus its invented wire
 * identity (§4.1: "you invent busid / busnum / devnum yourself and reuse them
 * consistently"). The single source of truth for a device's identity, so DEVLIST
 * and IMPORT can never drift. Identity is derived purely from the device's bus
 * address {@code (busnum, devnum)} — never from VID/PID (Invariant 1).
 *
 * <p>{@code (busnum=1, devnum=1)} yields busid {@code "1-1"} and devid
 * {@code (1<<16)|1}, the v1 single-device identity.
 */
public final class ExportedDevice {
    public final UsbBackend backend;
    public final int busnum;
    public final int devnum;
    /** {@code "<busnum>-<devnum>"}, no zero padding (stock usbip convention). */
    public final String busid;
    public final String path;
    /** §4.2 transfer-phase devid: {@code (busnum << 16) | devnum}. */
    public final int devid;

    public ExportedDevice(UsbBackend backend, int busnum, int devnum) {
        this.backend = backend;
        this.busnum = busnum;
        this.devnum = devnum;
        this.busid = busnum + "-" + devnum;
        this.path = "/sys/devices/tv/" + busid;
        this.devid = (busnum << 16) | devnum;
    }
}
