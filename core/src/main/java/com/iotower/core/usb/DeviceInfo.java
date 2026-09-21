package com.iotower.core.usb;

/** Fields needed to build the exported {@code usbip_usb_device} struct (§4.1). */
public final class DeviceInfo {
    public final int idVendor;
    public final int idProduct;
    public final int bcdDevice;
    public final int deviceClass;
    public final int deviceSubClass;
    public final int deviceProtocol;
    public final int configurationValue;
    public final int numConfigurations;
    public final int numInterfaces;
    public final int speed;

    public DeviceInfo(int idVendor, int idProduct, int bcdDevice,
                      int deviceClass, int deviceSubClass, int deviceProtocol,
                      int configurationValue, int numConfigurations, int numInterfaces,
                      int speed) {
        this.idVendor = idVendor;
        this.idProduct = idProduct;
        this.bcdDevice = bcdDevice;
        this.deviceClass = deviceClass;
        this.deviceSubClass = deviceSubClass;
        this.deviceProtocol = deviceProtocol;
        this.configurationValue = configurationValue;
        this.numConfigurations = numConfigurations;
        this.numInterfaces = numInterfaces;
        this.speed = speed;
    }
}
