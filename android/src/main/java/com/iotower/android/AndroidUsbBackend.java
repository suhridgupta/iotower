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
 * <p>The M6 spike ({@link HostApiSpike}) already proved the open +
 * {@code claimInterface(intf, forceClaim=true)} + interrupt-IN read path on the
 * real TV, standalone and without the network. M7 fleshes out this class against
 * the {@link UsbBackend} contract so the proven {@code UsbIpServer} runs behind
 * it inside {@link ServerService}.
 *
 * <p>TODO(M7): {@code deviceInfo()} from the parsed descriptors;
 * {@code submit} — {@code UsbRequest.queue()} / {@code requestWait()} on
 * interrupt/bulk endpoints with a completion loop keyed by seqnum (§5.1); and
 * {@code cancel} via {@code UsbRequest.cancel()}. ({@code controlTransfer} on
 * ep0 and {@code rawDescriptors()} are already wired.)
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
