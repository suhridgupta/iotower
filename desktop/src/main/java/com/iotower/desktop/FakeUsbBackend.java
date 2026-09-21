package com.iotower.desktop;

import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;

/**
 * A synthetic USB device for local end-to-end testing. Advertises a simple
 * gamepad-like identity and (eventually) replays canned input reports, so the
 * whole server loop can be validated without hardware (§12: start with a dumb
 * pad).
 *
 * <p>TODO: serve a captured descriptor blob from {@code testdata/} and feed
 * periodic input reports on the interrupt-IN endpoint.
 */
public final class FakeUsbBackend implements UsbBackend {

    @Override
    public byte[] rawDescriptors() {
        return new byte[0]; // TODO: return a captured descriptor dump.
    }

    @Override
    public DeviceInfo deviceInfo() {
        // Generic gamepad-ish identity for now.
        return new DeviceInfo(
                0x046d, 0xc260, 0x0100,
                0x00, 0x00, 0x00,
                1, 1, 1,
                /* speed */ 2);
    }

    @Override
    public int controlTransfer(int requestType, int request, int value, int index,
                               byte[] buffer, int length, int timeoutMillis) {
        return 0;
    }

    @Override
    public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
        UsbTransfer t = new UsbTransfer(null);
        t.completion.complete(new UsbTransfer.Result(0, new byte[0], 0));
        return t;
    }

    @Override
    public void cancel(UsbTransfer transfer) { }

    @Override
    public void close() { }
}
