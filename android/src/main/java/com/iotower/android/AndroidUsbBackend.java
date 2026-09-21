package com.iotower.android;

import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;

/**
 * {@link UsbBackend} backed by the Android USB Host API (§2, §5). This is the
 * only class that touches real USB hardware; everything above it is plain Java.
 *
 * <p>TODO(milestone 1): {@code openDevice} + {@code claimInterface(intf, true)}
 * on every interface; {@code getRawDescriptors()}.
 * <p>TODO(milestone 3): {@code controlTransfer} on ep0; {@code UsbRequest.queue()}
 * / {@code requestWait()} on other endpoints with a completion loop; cancel via
 * {@code UsbRequest.cancel()}.
 */
public final class AndroidUsbBackend implements UsbBackend {
    private final UsbManager usbManager;
    private final UsbDeviceConnection connection;

    public AndroidUsbBackend(UsbManager usbManager, UsbDeviceConnection connection) {
        this.usbManager = usbManager;
        this.connection = connection;
    }

    @Override
    public byte[] rawDescriptors() {
        return connection.getRawDescriptors();
    }

    @Override
    public DeviceInfo deviceInfo() {
        throw new UnsupportedOperationException("TODO(milestone 2)");
    }

    @Override
    public int controlTransfer(int requestType, int request, int value, int index,
                               byte[] buffer, int length, int timeoutMillis) {
        return connection.controlTransfer(requestType, request, value, index,
                buffer, length, timeoutMillis);
    }

    @Override
    public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
        throw new UnsupportedOperationException("TODO(milestone 3)");
    }

    @Override
    public void cancel(UsbTransfer transfer) {
        throw new UnsupportedOperationException("TODO(milestone 3)");
    }

    @Override
    public void close() {
        connection.close();
    }
}
