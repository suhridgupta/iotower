package com.iotower.core.net;

import com.iotower.core.protocol.CmdSubmit;
import com.iotower.core.protocol.CmdUnlink;
import com.iotower.core.protocol.OpHeader;
import com.iotower.core.protocol.RetSubmit;
import com.iotower.core.protocol.RetUnlink;
import com.iotower.core.protocol.UsbIp;
import com.iotower.core.protocol.UsbIpHeaderBasic;
import com.iotower.core.protocol.UsbIpUsbDevice;
import com.iotower.core.protocol.UsbIpUsbInterface;
import com.iotower.core.usb.DescriptorParser;
import com.iotower.core.usb.DeviceInfo;
import com.iotower.core.usb.EndpointMap;
import com.iotower.core.usb.InterfaceInfo;
import com.iotower.core.usb.UsbBackend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * The USB/IP server loop (§3, §6). Listens on {@code 0.0.0.0:3240}, one client
 * at a time for v1. Negotiation (DEVLIST/IMPORT) then the transfer phase run on
 * the accepted socket. Backend-agnostic: it is handed a {@link UsbBackend}, so
 * the identical loop runs under Android and under the desktop harness.
 *
 * <p>Answers {@code OP_REQ_DEVLIST} (§4.1) with the one exported device this
 * backend serves, and {@code OP_REQ_IMPORT} (§4.1) by switching the socket into
 * the transfer phase (§4.2), where control (ep0) transfers are served
 * synchronously against {@link UsbBackend#controlTransfer}. The transfer-phase
 * loop here is a deliberately provisional, single-threaded stand-in for the
 * milestone 5 asynchronous engine (§5.1) — correct only because enumeration
 * control traffic is strictly serial.
 */
public final class UsbIpServer implements Runnable {

    // Invented identity for the exported device (§4.1: "you invent busid /
    // busnum / devnum yourself and reuse them consistently"). Defined once
    // here so M4 IMPORT reuses the exact same values.
    static final String EXPORTED_BUSID = "1-1";
    static final int EXPORTED_BUSNUM = 1;
    static final int EXPORTED_DEVNUM = 1;
    static final String EXPORTED_PATH = "/sys/devices/tv/1-1";
    /** §4.2 transfer-phase devid, echoed unconditionally (one device, v1). */
    static final int EXPORTED_DEVID = (EXPORTED_BUSNUM << 16) | EXPORTED_DEVNUM;

    /** Timeout passed to the synchronous ep0 {@code controlTransfer} calls (§5.1's stand-in). */
    private static final int CONTROL_TIMEOUT_MS = 5000;

    /** {@code -EPIPE} — provisional stub status for non-control SUBMITs (§4/Approach §4). */
    private static final int STATUS_EPIPE = -32;

    private final UsbBackend backend;
    private final int port;
    private volatile boolean running;

    // Testability affordance (production-invisible): lets an L1 test bind an
    // ephemeral port and learn it race-free, and lets stop() actually unblock
    // a server thread parked in accept().
    private volatile ServerSocket serverSocket;
    private final CountDownLatch boundLatch = new CountDownLatch(1);
    private volatile int boundPort = -1;

    public UsbIpServer(UsbBackend backend) {
        this(backend, UsbIp.PORT);
    }

    public UsbIpServer(UsbBackend backend, int port) {
        this.backend = backend;
        this.port = port;
    }

    @Override
    public void run() {
        running = true;
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("0.0.0.0", port));
            serverSocket = server;
            boundPort = server.getLocalPort();
            boundLatch.countDown();
            System.out.println("[iotower] USB/IP server listening on 0.0.0.0:" + boundPort);
            while (running) {
                Socket client = server.accept();
                client.setTcpNoDelay(true); // §6: disable Nagle — critical for input latency
                System.out.println("[iotower] client connected: " + client.getRemoteSocketAddress());
                handle(client);
            }
        } catch (IOException e) {
            if (running) e.printStackTrace();
        }
    }

    private void handle(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            byte[] headerBytes = new byte[OpHeader.BYTES];
            if (!readFully(in, headerBytes)) {
                // Client closed before sending a full header — nothing to do.
                return;
            }
            OpHeader header = OpHeader.readFrom(
                    ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN));

            switch (header.code) {
                case UsbIp.OP_REQ_DEVLIST:
                    handleDevlist(out);
                    break;
                case UsbIp.OP_REQ_IMPORT:
                    handleImport(in, out);
                    break;
                default:
                    System.out.println("[iotower] unexpected op code: 0x"
                            + Integer.toHexString(header.code));
                    break;
            }
        } catch (IOException | RuntimeException e) {
            // Invariant 6: a long-running server must never die mid-session — a bad
            // connection closes, the accept loop keeps going.
            System.out.println("[iotower] error handling connection: " + e);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void handleDevlist(OutputStream out) throws IOException {
        byte[] rawDescriptors = backend.rawDescriptors();
        DeviceInfo deviceInfo = backend.deviceInfo();
        if (rawDescriptors == null || deviceInfo == null) {
            System.out.println("[iotower] backend has no descriptors/deviceInfo; closing");
            return;
        }

        EndpointMap map = DescriptorParser.parse(rawDescriptors);
        List<InterfaceInfo> interfaces = map.interfaces();
        int n = deviceInfo.numInterfaces;

        int size = OpHeader.BYTES + 4 + UsbIpUsbDevice.BYTES + n * UsbIpUsbInterface.BYTES;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_DEVLIST, UsbIp.STATUS_OK).writeTo(buf);
        buf.putInt(1); // exported-device count — v1 always advertises exactly one device.

        buildExportedDevice(deviceInfo, n).writeTo(buf);

        for (int i = 0; i < n; i++) {
            int cls = 0, sub = 0, proto = 0;
            if (i < interfaces.size()) {
                InterfaceInfo info = interfaces.get(i);
                cls = info.bInterfaceClass;
                sub = info.bInterfaceSubClass;
                proto = info.bInterfaceProtocol;
            }
            new UsbIpUsbInterface(cls, sub, proto, 0).writeTo(buf);
        }

        out.write(buf.array(), 0, buf.position());
        out.flush();
    }

    /**
     * Builds the {@code usbip_usb_device} struct shared verbatim by DEVLIST and
     * IMPORT (§4.1), from the invented identity constants and the backend's
     * {@link DeviceInfo}, so the two replies can never drift.
     */
    private static UsbIpUsbDevice buildExportedDevice(DeviceInfo deviceInfo, int numInterfaces) {
        return new UsbIpUsbDevice(
                EXPORTED_PATH, EXPORTED_BUSID, EXPORTED_BUSNUM, EXPORTED_DEVNUM,
                deviceInfo.speed, deviceInfo.idVendor, deviceInfo.idProduct, deviceInfo.bcdDevice,
                deviceInfo.deviceClass, deviceInfo.deviceSubClass, deviceInfo.deviceProtocol,
                deviceInfo.configurationValue, deviceInfo.numConfigurations, numInterfaces);
    }

    /**
     * {@code OP_REQ_IMPORT} (§4.1): read the 32-byte busid, match against the
     * one exported device, reply, and on success switch the same socket into
     * the transfer phase (§4.2) — does not close here; the caller's
     * {@code finally} does, once {@link #runTransferPhase} returns.
     */
    private void handleImport(InputStream in, OutputStream out) throws IOException {
        byte[] busidBytes = new byte[32];
        if (!readFully(in, busidBytes)) {
            System.out.println("[iotower] OP_REQ_IMPORT: truncated busid; closing");
            return;
        }
        String busid = nulTrim(busidBytes);

        if (!EXPORTED_BUSID.equals(busid)) {
            System.out.println("[iotower] OP_REQ_IMPORT: unknown busid '" + busid + "'; rejecting");
            ByteBuffer buf = ByteBuffer.allocate(OpHeader.BYTES).order(ByteOrder.BIG_ENDIAN);
            new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_IMPORT, /* status */ 1).writeTo(buf);
            out.write(buf.array());
            out.flush();
            return;
        }

        byte[] rawDescriptors = backend.rawDescriptors();
        DeviceInfo deviceInfo = backend.deviceInfo();
        if (rawDescriptors == null || deviceInfo == null) {
            System.out.println("[iotower] backend has no descriptors/deviceInfo; closing");
            return;
        }

        ByteBuffer buf = ByteBuffer.allocate(OpHeader.BYTES + UsbIpUsbDevice.BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_IMPORT, UsbIp.STATUS_OK).writeTo(buf);
        buildExportedDevice(deviceInfo, deviceInfo.numInterfaces).writeTo(buf);
        out.write(buf.array());
        out.flush();

        runTransferPhase(in, out);
    }

    /**
     * The transfer phase (§4.2), entered once after a successful IMPORT and run
     * until the client detaches (EOF) or the stream desyncs. Reads 20-byte
     * {@code usbip_header_basic} + 28-byte command blocks and routes by
     * {@code (command, ep, direction)}.
     *
     * <p>This is a deliberately single-threaded, strictly synchronous
     * submit -> controlTransfer -> ret loop (Approach §3): correct only
     * because enumeration control traffic is strictly serial. It is
     * provisional and will be replaced by {@code TransferEngine} (§5.1) in
     * milestone 5 — it intentionally does not queue real interrupt/bulk
     * transfers or cancel real in-flight ones.
     */
    private void runTransferPhase(InputStream in, OutputStream out) throws IOException {
        byte[] basicBytes = new byte[UsbIpHeaderBasic.BYTES];
        byte[] blockBytes = new byte[28]; // CmdSubmit.BYTES == CmdUnlink.BYTES == 28

        while (true) {
            if (!readFully(in, basicBytes)) {
                // EOF: client detached cleanly.
                return;
            }
            UsbIpHeaderBasic basic = UsbIpHeaderBasic.readFrom(
                    ByteBuffer.wrap(basicBytes).order(ByteOrder.BIG_ENDIAN));

            if (!readFully(in, blockBytes)) {
                System.out.println("[iotower] transfer phase: truncated command block; closing");
                return;
            }

            if (basic.command == UsbIp.CMD_SUBMIT) {
                CmdSubmit cmd = CmdSubmit.readFrom(
                        ByteBuffer.wrap(blockBytes).order(ByteOrder.BIG_ENDIAN));

                byte[] outPayload = null;
                if (basic.direction == UsbIp.DIR_OUT && cmd.transferBufferLength > 0) {
                    outPayload = new byte[cmd.transferBufferLength];
                    if (!readFully(in, outPayload)) {
                        System.out.println("[iotower] transfer phase: truncated OUT payload; closing");
                        return;
                    }
                }

                if (basic.ep == 0) {
                    handleControlSubmit(out, basic, cmd, outPayload);
                } else {
                    // Provisional stub (Approach §4): keeps the stream framed without
                    // building the M5 async engine. Not real interrupt/bulk transport.
                    writeRetSubmit(out, basic.seqnum, basic.direction, basic.ep,
                            STATUS_EPIPE, null, 0);
                }
            } else if (basic.command == UsbIp.CMD_UNLINK) {
                // CmdUnlink is decoded for completeness/symmetry; M4 does not act on
                // unlinkSeqnum (no async engine to cancel in — provisional ack only).
                CmdUnlink.readFrom(ByteBuffer.wrap(blockBytes).order(ByteOrder.BIG_ENDIAN));

                ByteBuffer reply = ByteBuffer.allocate(UsbIpHeaderBasic.BYTES + RetUnlink.BYTES)
                        .order(ByteOrder.BIG_ENDIAN);
                new UsbIpHeaderBasic(UsbIp.RET_UNLINK, basic.seqnum, EXPORTED_DEVID,
                        basic.direction, basic.ep).writeTo(reply);
                new RetUnlink(0).writeTo(reply);
                out.write(reply.array());
                out.flush();
            } else {
                System.out.println("[iotower] transfer phase: unexpected command "
                        + basic.command + "; closing (desynced stream)");
                return;
            }
        }
    }

    /** Decodes and serves an ep0 {@code CMD_SUBMIT} via {@link UsbBackend#controlTransfer}. */
    private void handleControlSubmit(OutputStream out, UsbIpHeaderBasic basic, CmdSubmit cmd,
            byte[] outPayload) throws IOException {
        int bmRequestType = cmd.setup[0] & 0xFF;
        int bRequest = cmd.setup[1] & 0xFF;
        // Setup packet wValue/wIndex/wLength are little-endian (USB spec) — the one
        // mixed-endianness spot in core/net; every enclosing USB/IP frame is big-endian.
        int wValue = decodeSetupLe16(cmd.setup, 2);
        int wIndex = decodeSetupLe16(cmd.setup, 4);
        int wLength = decodeSetupLe16(cmd.setup, 6);

        byte[] buffer;
        if (basic.direction == UsbIp.DIR_IN) {
            buffer = new byte[cmd.transferBufferLength];
        } else {
            buffer = outPayload != null ? outPayload : new byte[0];
        }

        int ret = backend.controlTransfer(bmRequestType, bRequest, wValue, wIndex,
                buffer, cmd.transferBufferLength, CONTROL_TIMEOUT_MS);

        int status = ret < 0 ? ret : 0;
        int actualLength = ret < 0 ? 0 : ret;
        byte[] inData = (basic.direction == UsbIp.DIR_IN && actualLength > 0) ? buffer : null;

        writeRetSubmit(out, basic.seqnum, basic.direction, basic.ep, status, inData, actualLength);
    }

    /** Decodes a little-endian u16 out of the 8-byte USB setup packet at {@code offset}. */
    private static int decodeSetupLe16(byte[] setup, int offset) {
        return (setup[offset] & 0xFF) | ((setup[offset + 1] & 0xFF) << 8);
    }

    /**
     * Writes a {@code USBIP_RET_SUBMIT} (§4.2): {@code usbip_header_basic} +
     * {@code RetSubmit} +, for an IN transfer with data, the {@code actualLength}
     * payload bytes.
     */
    private static void writeRetSubmit(OutputStream out, int seqnum, int direction, int ep,
            int status, byte[] data, int actualLength) throws IOException {
        boolean withPayload = direction == UsbIp.DIR_IN && actualLength > 0 && data != null;
        int size = UsbIpHeaderBasic.BYTES + RetSubmit.BYTES + (withPayload ? actualLength : 0);
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        new UsbIpHeaderBasic(UsbIp.RET_SUBMIT, seqnum, EXPORTED_DEVID, direction, ep)
                .writeTo(buf);
        new RetSubmit(status, actualLength, 0, 0, 0).writeTo(buf);
        if (withPayload) {
            buf.put(data, 0, actualLength);
        }

        out.write(buf.array(), 0, buf.position());
        out.flush();
    }

    /** Reads until {@code dst} is full or returns false on EOF before that. */
    private static boolean readFully(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int read = in.read(dst, off, dst.length - off);
            if (read < 0) {
                return false;
            }
            off += read;
        }
        return true;
    }

    /** NUL-trims a fixed-width ASCII byte array to a String (busid on the wire). */
    private static String nulTrim(byte[] bytes) {
        int len = 0;
        while (len < bytes.length && bytes[len] != 0) {
            len++;
        }
        return new String(bytes, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * Awaits the server binding its socket and returns the bound port (the OS-
     * assigned port when constructed with port 0), or -1 on timeout. Lets a test
     * learn the ephemeral port race-free.
     */
    public int awaitBoundPort(long timeoutMillis) throws InterruptedException {
        boundLatch.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        return boundPort;
    }

    public void stop() {
        running = false;
        ServerSocket server = serverSocket;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }
}
