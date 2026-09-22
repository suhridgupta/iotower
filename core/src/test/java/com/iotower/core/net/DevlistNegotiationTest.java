package com.iotower.core.net;

import com.iotower.core.protocol.OpHeader;
import com.iotower.core.protocol.UsbIp;
import com.iotower.core.protocol.UsbIpUsbDevice;
import com.iotower.core.protocol.UsbIpUsbInterface;
import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.UsbBackend;
import com.iotower.core.usb.UsbTransfer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L1 integration test for the DEVLIST negotiation path (§4, §4.1): proves the
 * end-to-end flow — read header, parse descriptors, build the reply, frame it
 * on the wire — that no existing test covers (M1 tests single struct codecs in
 * isolation; M2 tests the descriptor parser in isolation).
 */
class DevlistNegotiationTest {

    /**
     * A simple one-interface HID gamepad, byte-identical to
     * {@code DescriptorParserTest}'s "Fixture A": device descriptor (18) +
     * configuration descriptor (9, bNumInterfaces=1) + interface 0 descriptor
     * (9: bInterfaceNumber 0, bAlternateSetting 0, bInterfaceClass 0x03 HID,
     * bInterfaceSubClass 0x00, bInterfaceProtocol 0x00) + HID class descriptor
     * (9, type 0x21, proves skip) + interrupt-IN endpoint 0x81 (7).
     *
     * <p>Kept byte-identical to the in-session pass-gate harness (see the M3 PRD).
     */
    private static byte[] fixtureSimpleGamepad() {
        return new byte[] {
                // --- Device descriptor (bDescriptorType 0x01), 18 bytes ---
                0x12,               // bLength = 18
                0x01,               // bDescriptorType = DEVICE
                0x00, 0x02,         // bcdUSB = 0x0200 (LE)
                0x00,               // bDeviceClass
                0x00,               // bDeviceSubClass
                0x00,               // bDeviceProtocol
                0x08,               // bMaxPacketSize0
                0x34, 0x12,         // idVendor = 0x1234 (LE) -- not read by parser
                0x78, 0x56,         // idProduct = 0x5678 (LE) -- not read by parser
                0x00, 0x01,         // bcdDevice = 0x0100 (LE)
                0x00,               // iManufacturer
                0x00,               // iProduct
                0x00,               // iSerialNumber
                0x01,               // bNumConfigurations = 1

                // --- Configuration descriptor (0x02), 9 bytes ---
                0x09,               // bLength = 9
                0x02,               // bDescriptorType = CONFIGURATION
                0x22, 0x00,         // wTotalLength = 34 (LE) = 9+9+9+7
                0x01,               // bNumInterfaces = 1
                0x01,               // bConfigurationValue
                0x00,               // iConfiguration
                (byte) 0x80,        // bmAttributes (bus-powered)
                0x32,               // bMaxPower = 100 mA

                // --- Interface 0 descriptor (0x04), 9 bytes ---
                0x09,               // bLength = 9
                0x04,               // bDescriptorType = INTERFACE
                0x00,               // bInterfaceNumber = 0
                0x00,               // bAlternateSetting
                0x01,               // bNumEndpoints = 1
                0x03,               // bInterfaceClass = HID
                0x00,               // bInterfaceSubClass
                0x00,               // bInterfaceProtocol
                0x00,               // iInterface

                // --- HID class-specific descriptor (0x21), 9 bytes: must be skipped ---
                0x09,               // bLength = 9
                0x21,               // bDescriptorType = HID
                0x11, 0x01,         // bcdHID = 0x0111 (LE)
                0x00,               // bCountryCode
                0x01,               // bNumDescriptors
                0x22,               // bDescriptorType (Report)
                0x34, 0x00,         // wDescriptorLength = 0x0034 (LE)

                // --- Endpoint 0x81 descriptor (0x05), 7 bytes: interrupt IN ---
                0x07,               // bLength = 7
                0x05,               // bDescriptorType = ENDPOINT
                (byte) 0x81,        // bEndpointAddress = 0x81 (EP1 IN)
                0x03,               // bmAttributes = interrupt (0x03)
                0x08, 0x00,         // wMaxPacketSize = 8 (LE)
                0x0A,               // bInterval = 10
        };
    }

    private static final class FakeBackend implements UsbBackend {
        @Override
        public byte[] rawDescriptors() {
            return fixtureSimpleGamepad();
        }

        @Override
        public DeviceInfo deviceInfo() {
            // idVendor 0x046d, idProduct 0xc294, bcdDevice 0x0100, class/sub/proto 0,
            // bConfigurationValue 1, bNumConfigurations 1, bNumInterfaces 1, speed 3.
            return new DeviceInfo(0x046d, 0xc294, 0x0100, 0, 0, 0, 1, 1, 1, 3);
        }

        @Override
        public int controlTransfer(int requestType, int request, int value, int index,
                                   byte[] buffer, int length, int timeoutMillis) {
            throw new UnsupportedOperationException("not used by M3 DEVLIST");
        }

        @Override
        public UsbTransfer submit(int endpointAddress, int direction, byte[] buffer, int length) {
            throw new UnsupportedOperationException("not used by M3 DEVLIST");
        }

        @Override
        public void cancel(UsbTransfer transfer) {
            throw new UnsupportedOperationException("not used by M3 DEVLIST");
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("not used by M3 DEVLIST");
        }
    }

    @Test
    void devlistReturnsExportedDevice() throws Exception {
        UsbIpServer server = new UsbIpServer(new FakeBackend(), 0);
        Thread serverThread = new Thread(server, "usbip-server-test");
        serverThread.setDaemon(true);
        serverThread.start();

        try {
            int port = server.awaitBoundPort(2000);

            try (Socket socket = new Socket("127.0.0.1", port)) {
                OutputStream out = socket.getOutputStream();
                InputStream in = socket.getInputStream();

                ByteBuffer request = ByteBuffer.allocate(OpHeader.BYTES).order(ByteOrder.BIG_ENDIAN);
                new OpHeader(UsbIp.VERSION, UsbIp.OP_REQ_DEVLIST, 0).writeTo(request);
                out.write(request.array());
                out.flush();

                byte[] headerBytes = readFully(in, OpHeader.BYTES);
                OpHeader header = OpHeader.readFrom(
                        ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN));
                assertEquals(0x0111, header.version, "reply version");
                assertEquals(UsbIp.OP_REP_DEVLIST, header.code, "reply code");
                assertEquals(0, header.status, "reply status");

                byte[] countBytes = readFully(in, 4);
                int count = ByteBuffer.wrap(countBytes).order(ByteOrder.BIG_ENDIAN).getInt();
                assertEquals(1, count, "exported device count");

                byte[] deviceBytes = readFully(in, UsbIpUsbDevice.BYTES);
                UsbIpUsbDevice device = UsbIpUsbDevice.readFrom(
                        ByteBuffer.wrap(deviceBytes).order(ByteOrder.BIG_ENDIAN));
                assertEquals("1-1", device.busid, "busid");
                assertEquals(1, device.busnum, "busnum");
                assertEquals(1, device.devnum, "devnum");
                assertEquals(0x046d, device.idVendor, "idVendor");
                assertEquals(0xc294, device.idProduct, "idProduct");
                assertEquals(1, device.bNumInterfaces, "bNumInterfaces");
                assertEquals("/sys/devices/tv/1-1", device.path, "path");

                byte[] ifaceBytes = readFully(in, UsbIpUsbInterface.BYTES);
                UsbIpUsbInterface iface = UsbIpUsbInterface.readFrom(
                        ByteBuffer.wrap(ifaceBytes).order(ByteOrder.BIG_ENDIAN));
                assertEquals(0x03, iface.bInterfaceClass, "bInterfaceClass");
                assertEquals(0, iface.bInterfaceSubClass, "bInterfaceSubClass");
                assertEquals(0, iface.bInterfaceProtocol, "bInterfaceProtocol");

                assertEquals(-1, in.read(), "server must close the connection after the reply");
            }
        } finally {
            server.stop();
            serverThread.join(2000);
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int off = 0;
        while (off < length) {
            int read = in.read(buf, off, length - off);
            if (read < 0) {
                throw new IOException("EOF after " + off + " of " + length + " bytes");
            }
            off += read;
        }
        return buf;
    }
}
