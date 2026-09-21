package com.iotower.desktop;

import com.iotower.core.net.UsbIpServer;

/**
 * Local test harness: runs the real core USB/IP server against a fake USB
 * device, with no Android and no physical hardware. Start it, then on the same
 * machine:
 *
 * <pre>
 *   sudo modprobe vhci-hcd
 *   usbip list -r 127.0.0.1
 *   usbip attach -r 127.0.0.1 -b 1-1
 * </pre>
 *
 * This exercises the exact protocol + threading code the Android app runs; only
 * the {@link com.iotower.core.usb.UsbBackend} differs (§3, §5).
 */
public final class Main {
    public static void main(String[] args) {
        FakeUsbBackend backend = new FakeUsbBackend();
        UsbIpServer server = new UsbIpServer(backend);
        System.out.println("[iotower-desktop] starting fake-device server; Ctrl-C to stop");
        server.run();
    }
}
