package com.iotower.core.usb;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * L1 tests for {@link DescriptorParser}. Fixtures are synthetic, hand-built
 * byte[] literals following the USB 2.0 §9.5/§9.6 descriptor layouts — no bound
 * USB device or root is available in this environment to capture a real dump
 * (see testdata/README.md §1 and the M2 PRD's Risks section). The parser is
 * device-agnostic, so a spec-accurate synthetic descriptor exercises the same
 * code paths a real capture would.
 */
class DescriptorParserTest {

    /**
     * Fixture A — a simple one-interface HID gamepad.
     *
     * Device descriptor (18 bytes) + Configuration descriptor (9 bytes) +
     * Interface 0 descriptor (9 bytes) + HID class descriptor (9 bytes, proves
     * class-specific descriptors are skipped) + Endpoint 0x81 interrupt-IN
     * descriptor (7 bytes). Config wTotalLength = 9 + 9 + 9 + 7 = 34.
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

    /**
     * Fixture B — a composite device with two interfaces.
     *
     * Device descriptor (18) + Configuration (9, bNumInterfaces=2) +
     * Interface 0 / HID (9) + HID class descriptor (9) + Endpoint 0x81
     * interrupt-IN (7) + Endpoint 0x01 interrupt-OUT (7) + Interface 1 /
     * vendor-specific (9) + Endpoint 0x82 bulk-IN (7).
     * Config wTotalLength = 9+9+9+7+7+9+7 = 57.
     */
    private static byte[] fixtureCompositeDevice() {
        return new byte[] {
                // --- Device descriptor (0x01), 18 bytes ---
                0x12,               // bLength = 18
                0x01,               // bDescriptorType = DEVICE
                0x00, 0x02,         // bcdUSB = 0x0200 (LE)
                0x00,               // bDeviceClass
                0x00,               // bDeviceSubClass
                0x00,               // bDeviceProtocol
                0x08,               // bMaxPacketSize0
                (byte) 0xAB, 0x04,  // idVendor = 0x04AB (LE) -- not read by parser
                0x01, 0x02,         // idProduct = 0x0201 (LE) -- not read by parser
                0x00, 0x01,         // bcdDevice = 0x0100 (LE)
                0x00,               // iManufacturer
                0x00,               // iProduct
                0x00,               // iSerialNumber
                0x01,               // bNumConfigurations = 1

                // --- Configuration descriptor (0x02), 9 bytes ---
                0x09,               // bLength = 9
                0x02,               // bDescriptorType = CONFIGURATION
                0x39, 0x00,         // wTotalLength = 57 (LE) = 9+9+9+7+7+9+7
                0x02,               // bNumInterfaces = 2
                0x01,               // bConfigurationValue
                0x00,               // iConfiguration
                (byte) 0x80,        // bmAttributes (bus-powered)
                0x32,               // bMaxPower = 100 mA

                // --- Interface 0 descriptor (0x04), 9 bytes: HID ---
                0x09,               // bLength = 9
                0x04,               // bDescriptorType = INTERFACE
                0x00,               // bInterfaceNumber = 0
                0x00,               // bAlternateSetting
                0x02,               // bNumEndpoints = 2
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

                // --- Endpoint 0x81 descriptor (0x05), 7 bytes: interrupt IN, iface 0 ---
                0x07,               // bLength = 7
                0x05,               // bDescriptorType = ENDPOINT
                (byte) 0x81,        // bEndpointAddress = 0x81 (EP1 IN)
                0x03,               // bmAttributes = interrupt (0x03)
                0x10, 0x00,         // wMaxPacketSize = 16 (LE)
                0x02,               // bInterval = 2

                // --- Endpoint 0x01 descriptor (0x05), 7 bytes: interrupt OUT, iface 0 ---
                0x07,               // bLength = 7
                0x05,               // bDescriptorType = ENDPOINT
                0x01,               // bEndpointAddress = 0x01 (EP1 OUT)
                0x03,               // bmAttributes = interrupt (0x03)
                0x10, 0x00,         // wMaxPacketSize = 16 (LE)
                0x02,               // bInterval = 2

                // --- Interface 1 descriptor (0x04), 9 bytes: vendor-specific ---
                0x09,               // bLength = 9
                0x04,               // bDescriptorType = INTERFACE
                0x01,               // bInterfaceNumber = 1
                0x00,               // bAlternateSetting
                0x01,               // bNumEndpoints = 1
                (byte) 0xFF,        // bInterfaceClass = vendor-specific
                0x00,               // bInterfaceSubClass
                0x00,               // bInterfaceProtocol
                0x00,               // iInterface

                // --- Endpoint 0x82 descriptor (0x05), 7 bytes: bulk IN, iface 1 ---
                0x07,               // bLength = 7
                0x05,               // bDescriptorType = ENDPOINT
                (byte) 0x82,        // bEndpointAddress = 0x82 (EP2 IN)
                0x02,               // bmAttributes = bulk (0x02)
                0x40, 0x00,         // wMaxPacketSize = 64 (LE)
                0x00,               // bInterval = 0
        };
    }

    @Test
    void parsesSimpleGamepad() {
        EndpointMap map = DescriptorParser.parse(fixtureSimpleGamepad());

        assertEquals(1, map.size(), "endpoint count");
        assertEquals(1, map.interfaceCount(), "interface count");

        EndpointInfo ep81 = map.get(0x81);
        assertNotNull(ep81, "0x81 must be present");
        assertEquals(EndpointInfo.TYPE_INTERRUPT, ep81.type, "0x81 type");
        assertEquals(com.iotower.core.protocol.UsbIp.DIR_IN, ep81.direction, "0x81 direction");
        assertEquals(8, ep81.maxPacketSize, "0x81 maxPacketSize");
        assertEquals(10, ep81.interval, "0x81 interval");
        assertEquals(0, ep81.interfaceNumber, "0x81 interfaceNumber");
    }

    @Test
    void parsesCompositeDevice() {
        EndpointMap map = DescriptorParser.parse(fixtureCompositeDevice());

        assertEquals(3, map.size(), "endpoint count");
        assertEquals(2, map.interfaceCount(), "interface count");

        EndpointInfo ep81 = map.get(0x81);
        assertNotNull(ep81, "0x81 must be present");
        assertEquals(EndpointInfo.TYPE_INTERRUPT, ep81.type, "0x81 type");
        assertEquals(com.iotower.core.protocol.UsbIp.DIR_IN, ep81.direction, "0x81 direction");
        assertEquals(16, ep81.maxPacketSize, "0x81 maxPacketSize");
        assertEquals(2, ep81.interval, "0x81 interval");
        assertEquals(0, ep81.interfaceNumber, "0x81 interfaceNumber");

        EndpointInfo ep01 = map.get(0x01);
        assertNotNull(ep01, "0x01 must be present");
        assertEquals(EndpointInfo.TYPE_INTERRUPT, ep01.type, "0x01 type");
        assertEquals(com.iotower.core.protocol.UsbIp.DIR_OUT, ep01.direction, "0x01 direction");
        assertEquals(16, ep01.maxPacketSize, "0x01 maxPacketSize");
        assertEquals(2, ep01.interval, "0x01 interval");
        assertEquals(0, ep01.interfaceNumber, "0x01 interfaceNumber");

        EndpointInfo ep82 = map.get(0x82);
        assertNotNull(ep82, "0x82 must be present");
        assertEquals(EndpointInfo.TYPE_BULK, ep82.type, "0x82 type");
        assertEquals(com.iotower.core.protocol.UsbIp.DIR_IN, ep82.direction, "0x82 direction");
        assertEquals(64, ep82.maxPacketSize, "0x82 maxPacketSize");
        assertEquals(0, ep82.interval, "0x82 interval");
        assertEquals(1, ep82.interfaceNumber, "0x82 interfaceNumber");
    }

    @Test
    void rejectsMalformedDescriptors() {
        // (a) bLength == 0 would otherwise spin forever.
        byte[] zeroLength = {
                0x00,               // bLength = 0 (invalid)
                0x04,               // bDescriptorType = INTERFACE
        };
        assertThrows(IllegalArgumentException.class, () -> DescriptorParser.parse(zeroLength));

        // (b) a descriptor whose bLength runs past the end of the buffer
        // (truncated capture).
        byte[] truncated = {
                0x09,               // bLength = 9 (claims 9 bytes)
                0x02,               // bDescriptorType = CONFIGURATION
                0x00,               // ...but only 1 more byte actually follows
        };
        assertThrows(IllegalArgumentException.class, () -> DescriptorParser.parse(truncated));

        // (c) null input.
        assertThrows(IllegalArgumentException.class, () -> DescriptorParser.parse(null));
    }

    @Test
    void parseDeviceInfoFromRealCapture() throws Exception {
        byte[] raw = Files.readAllBytes(
                RealCaptureTest.locateTestData("simple-gamepad-descriptors.bin"));

        DeviceInfo info = DescriptorParser.parseDeviceInfo(raw, /* speed */ 2);

        assertEquals(0x046d, info.idVendor, "idVendor");
        assertEquals(0xc21d, info.idProduct, "idProduct");
        assertEquals(0x4014, info.bcdDevice, "bcdDevice");
        assertEquals(0xff, info.deviceClass, "deviceClass");
        assertEquals(1, info.numConfigurations, "numConfigurations");
        assertEquals(1, info.numInterfaces, "numInterfaces");
        assertEquals(1, info.configurationValue, "configurationValue");
        assertEquals(2, info.speed, "speed round-trips");
    }
}
