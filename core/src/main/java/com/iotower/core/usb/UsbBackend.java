package com.iotower.core.usb;

/**
 * The one seam between the device-agnostic USB/IP engine and the actual USB
 * transport. Everything above this interface — protocol framing, routing, the
 * concurrency engine, the socket server — is plain Java and testable off-device;
 * only the implementation touches real hardware (architecture.md §5).
 *
 * <p>Implementations: {@code AndroidUsbBackend} (Host API, in the {@code
 * :android} module) and {@code FakeUsbBackend} (in {@code :desktop}) for local
 * end-to-end testing with no hardware.
 */
public interface UsbBackend {

    /** Raw descriptors, as from {@code UsbDeviceConnection.getRawDescriptors()}. */
    byte[] rawDescriptors();

    /** Identity for the exported {@code usbip_usb_device} struct (§4.1). */
    DeviceInfo deviceInfo();

    /**
     * Synchronous control transfer on endpoint 0 (§5). Returns bytes transferred,
     * or a negative {@code errno}-style value on failure.
     */
    int controlTransfer(int requestType, int request, int value, int index,
                        byte[] buffer, int length, int timeoutMillis);

    /**
     * Submit a transfer on a non-zero endpoint (interrupt/bulk, IN or OUT).
     * Completes asynchronously; the returned {@link UsbTransfer} is correlated
     * back to its USB/IP seqnum by the engine (§5.1).
     */
    UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length);

    /** Cancel an in-flight transfer (CMD_UNLINK → {@code UsbRequest.cancel()}). */
    void cancel(UsbTransfer transfer);

    /** Release the device and any claimed interfaces. */
    void close();
}
