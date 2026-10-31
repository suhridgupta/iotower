package com.iotower.desktop;

import com.iotower.core.net.UsbIpServer;

/**
 * Local test harness: runs the real core USB/IP server against TWO fake USB
 * devices, with no Android and no physical hardware (§10). Both fakes share the
 * same descriptor and VID:PID but have distinct busids ("1-1", "1-2") and distinct
 * scripted report cycles. Start it, then on the same machine:
 *
 * <pre>
 *   sudo modprobe vhci-hcd
 *   usbip list -r 127.0.0.1
 *   sudo usbip attach -r 127.0.0.1 -b 1-1
 *   sudo usbip attach -r 127.0.0.1 -b 1-2
 * </pre>
 *
 * This exercises the exact protocol + threading code the Android app runs; only
 * the {@link com.iotower.core.usb.UsbBackend}s differ (§3, §5).
 */
public final class Main {
    public static void main(String[] args) {
        UsbIpServer server = new UsbIpServer();
        for (int i = 0; i < 2; i++) {
            FakeUsbBackend fake = new FakeUsbBackend(1, i + 1, i);
            server.addDevice(fake, fake.busnum(), fake.devnum());
        }
        System.out.println("[iotower-desktop] starting two-fake server (busids 1-1, 1-2); Ctrl-C to stop");
        server.run();
    }
}
