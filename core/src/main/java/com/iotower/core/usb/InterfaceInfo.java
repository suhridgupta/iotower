package com.iotower.core.usb;

/**
 * One interface as recorded from the descriptors (§5): interface number and
 * its class/subclass/protocol triple, used to fill the {@code
 * usbip_usb_interface} blocks in a DEVLIST reply (§4.1). Recorded only for
 * {@code bAlternateSetting == 0} so the list holds exactly one entry per
 * interface (see {@link DescriptorParser}).
 */
public final class InterfaceInfo {
    public final int interfaceNumber;
    public final int bInterfaceClass;
    public final int bInterfaceSubClass;
    public final int bInterfaceProtocol;

    public InterfaceInfo(int interfaceNumber, int bInterfaceClass, int bInterfaceSubClass,
                         int bInterfaceProtocol) {
        this.interfaceNumber = interfaceNumber;
        this.bInterfaceClass = bInterfaceClass;
        this.bInterfaceSubClass = bInterfaceSubClass;
        this.bInterfaceProtocol = bInterfaceProtocol;
    }
}
