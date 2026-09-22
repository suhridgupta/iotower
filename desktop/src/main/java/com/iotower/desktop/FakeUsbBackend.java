package com.iotower.desktop;

import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * A synthetic USB device for local end-to-end testing. Serves a real captured
 * descriptor dump (a Logitech F310 gamepad, testdata/simple-gamepad-descriptors.bin)
 * so the whole server loop can be validated without hardware (§12: start with a
 * dumb pad). Its {@link #deviceInfo()} identity is kept consistent with that
 * capture.
 *
 * <p>TODO: replay periodic input reports on the interrupt-IN endpoint (M5).
 */
public final class FakeUsbBackend implements UsbBackend {

    private static final String CAPTURE = "simple-gamepad-descriptors.bin";

    /** Walk up from the working dir to find {@code testdata/<name>}. */
    private static Path locateTestData(String name) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("testdata").resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate testdata/" + name
                + " (searched upward from " + Paths.get("").toAbsolutePath()
                + "); capture it per testdata/README.md §1");
    }

    @Override
    public byte[] rawDescriptors() {
        try {
            return Files.readAllBytes(locateTestData(CAPTURE));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read captured descriptors " + CAPTURE, e);
        }
    }

    @Override
    public DeviceInfo deviceInfo() {
        // Matches testdata/simple-gamepad-descriptors.bin (F310, XInput mode).
        return new DeviceInfo(
                0x046d, 0xc21d, 0x4014,   // idVendor, idProduct, bcdDevice
                0xff, 0xff, 0xff,         // bDeviceClass/SubClass/Protocol (vendor-specific)
                1, 1, 1,                  // bConfigurationValue, bNumConfigurations, bNumInterfaces
                /* speed */ 2);           // USB full speed
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
