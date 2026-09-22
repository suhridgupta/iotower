package com.iotower.core.usb;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * L1 cross-check of {@link DescriptorParser} against a REAL captured descriptor
 * dump (testdata/README.md §1) — the ground truth the M2 milestone asks for,
 * complementing the synthetic fixtures in {@link DescriptorParserTest}. The
 * capture is a Logitech F310 gamepad in XInput mode (046d:c21d, full speed): one
 * vendor-specific interface with an interrupt-IN 0x81 and an interrupt-OUT 0x02,
 * and a 16-byte vendor-specific descriptor that must be skipped.
 */
class RealCaptureTest {

    /** Walk up from the test working dir to find {@code testdata/<name>}. */
    static Path locateTestData(String name) {
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

    @Test
    void parsesRealF310Capture() throws Exception {
        byte[] raw = Files.readAllBytes(locateTestData("simple-gamepad-descriptors.bin"));

        EndpointMap map = DescriptorParser.parse(raw);

        assertEquals(1, map.interfaceCount(), "interface count");
        assertEquals(2, map.size(), "endpoint count");

        EndpointInfo epIn = map.get(0x81);
        assertNotNull(epIn, "0x81 interrupt-IN must be present");
        assertEquals(EndpointInfo.TYPE_INTERRUPT, epIn.type, "0x81 type");
        assertEquals(com.iotower.core.protocol.UsbIp.DIR_IN, epIn.direction, "0x81 direction");
        assertEquals(32, epIn.maxPacketSize, "0x81 maxPacketSize");
        assertEquals(4, epIn.interval, "0x81 interval");
        assertEquals(0, epIn.interfaceNumber, "0x81 interfaceNumber");

        EndpointInfo epOut = map.get(0x02);
        assertNotNull(epOut, "0x02 interrupt-OUT must be present");
        assertEquals(EndpointInfo.TYPE_INTERRUPT, epOut.type, "0x02 type");
        assertEquals(com.iotower.core.protocol.UsbIp.DIR_OUT, epOut.direction, "0x02 direction");
        assertEquals(32, epOut.maxPacketSize, "0x02 maxPacketSize");
        assertEquals(8, epOut.interval, "0x02 interval");
        assertEquals(0, epOut.interfaceNumber, "0x02 interfaceNumber");
    }
}
