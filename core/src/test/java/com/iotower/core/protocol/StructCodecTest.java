package com.iotower.core.protocol;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Round-trip test for the six M1 transfer-phase / devlist structs (§4).
 * Proves {@code writeTo} then {@code readFrom} reconstructs every field and
 * consumes/produces exactly {@code BYTES} bytes, for each struct.
 */
class StructCodecTest {

    private static Stream<Case> cases() {
        return Stream.of(
                new Case("UsbIpUsbDevice", UsbIpUsbDevice.BYTES, buf -> {
                    UsbIpUsbDevice in = new UsbIpUsbDevice(
                            "/sys/devices/tv/1-1", "1-1", 1, 2, 3,
                            0x046d, 0xc294, 0x0100, 0, 0, 0, 1, 1, 1);
                    in.writeTo(buf);
                    assertEquals(UsbIpUsbDevice.BYTES, buf.position());
                    buf.flip();
                    UsbIpUsbDevice out = UsbIpUsbDevice.readFrom(buf);
                    assertEquals(UsbIpUsbDevice.BYTES, buf.position());
                    assertEquals(in.path, out.path);
                    assertEquals(in.busid, out.busid);
                    assertEquals(in.busnum, out.busnum);
                    assertEquals(in.devnum, out.devnum);
                    assertEquals(in.speed, out.speed);
                    assertEquals(in.idVendor, out.idVendor);
                    assertEquals(in.idProduct, out.idProduct);
                    assertEquals(in.bcdDevice, out.bcdDevice);
                    assertEquals(in.bDeviceClass, out.bDeviceClass);
                    assertEquals(in.bDeviceSubClass, out.bDeviceSubClass);
                    assertEquals(in.bDeviceProtocol, out.bDeviceProtocol);
                    assertEquals(in.bConfigurationValue, out.bConfigurationValue);
                    assertEquals(in.bNumConfigurations, out.bNumConfigurations);
                    assertEquals(in.bNumInterfaces, out.bNumInterfaces);
                }),
                new Case("UsbIpUsbInterface", UsbIpUsbInterface.BYTES, buf -> {
                    UsbIpUsbInterface in = new UsbIpUsbInterface(8, 6, 80, 0);
                    in.writeTo(buf);
                    assertEquals(UsbIpUsbInterface.BYTES, buf.position());
                    buf.flip();
                    UsbIpUsbInterface out = UsbIpUsbInterface.readFrom(buf);
                    assertEquals(UsbIpUsbInterface.BYTES, buf.position());
                    assertEquals(in.bInterfaceClass, out.bInterfaceClass);
                    assertEquals(in.bInterfaceSubClass, out.bInterfaceSubClass);
                    assertEquals(in.bInterfaceProtocol, out.bInterfaceProtocol);
                }),
                new Case("CmdSubmit", CmdSubmit.BYTES, buf -> {
                    byte[] setup = {(byte) 0x80, 0x06, 0x00, 0x01, 0x00, 0x00, 0x12, 0x00};
                    CmdSubmit in = new CmdSubmit(0, 18, 0, 0xFFFFFFFF, 0, setup);
                    in.writeTo(buf);
                    assertEquals(CmdSubmit.BYTES, buf.position());
                    buf.flip();
                    CmdSubmit out = CmdSubmit.readFrom(buf);
                    assertEquals(CmdSubmit.BYTES, buf.position());
                    assertEquals(in.transferFlags, out.transferFlags);
                    assertEquals(in.transferBufferLength, out.transferBufferLength);
                    assertEquals(in.startFrame, out.startFrame);
                    assertEquals(0xFFFFFFFF, out.numberOfPackets);
                    assertEquals(in.interval, out.interval);
                    assertArrayEquals(in.setup, out.setup);
                }),
                new Case("RetSubmit", RetSubmit.BYTES, buf -> {
                    RetSubmit in = new RetSubmit(-32, 0, 0, 0xFFFFFFFF, 0);
                    in.writeTo(buf);
                    assertEquals(RetSubmit.BYTES, buf.position());
                    buf.flip();
                    RetSubmit out = RetSubmit.readFrom(buf);
                    assertEquals(RetSubmit.BYTES, buf.position());
                    assertEquals(-32, out.status);
                    assertEquals(in.actualLength, out.actualLength);
                    assertEquals(in.startFrame, out.startFrame);
                    assertEquals(in.numberOfPackets, out.numberOfPackets);
                    assertEquals(in.errorCount, out.errorCount);
                }),
                new Case("CmdUnlink", CmdUnlink.BYTES, buf -> {
                    CmdUnlink in = new CmdUnlink(42);
                    in.writeTo(buf);
                    assertEquals(CmdUnlink.BYTES, buf.position());
                    buf.flip();
                    CmdUnlink out = CmdUnlink.readFrom(buf);
                    assertEquals(CmdUnlink.BYTES, buf.position());
                    assertEquals(in.unlinkSeqnum, out.unlinkSeqnum);
                }),
                new Case("RetUnlink", RetUnlink.BYTES, buf -> {
                    RetUnlink in = new RetUnlink(-104);
                    in.writeTo(buf);
                    assertEquals(RetUnlink.BYTES, buf.position());
                    buf.flip();
                    RetUnlink out = RetUnlink.readFrom(buf);
                    assertEquals(RetUnlink.BYTES, buf.position());
                    assertEquals(-104, out.status);
                })
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void roundTrip(Case c) {
        ByteBuffer buf = ByteBuffer.allocate(c.bytes).order(ByteOrder.BIG_ENDIAN);
        c.exercise.accept(buf);
    }

    /** A named struct case: exercises write+flip+read+assert against its own buffer. */
    private static final class Case {
        final String name;
        final int bytes;
        final java.util.function.Consumer<ByteBuffer> exercise;

        Case(String name, int bytes, java.util.function.Consumer<ByteBuffer> exercise) {
            this.name = name;
            this.bytes = bytes;
            this.exercise = exercise;
        }

        @Override public String toString() {
            return name;
        }
    }
}
