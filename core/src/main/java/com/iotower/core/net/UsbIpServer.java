package com.iotower.core.net;

import com.iotower.core.engine.TransferEngine;
import com.iotower.core.protocol.CmdSubmit;
import com.iotower.core.protocol.CmdUnlink;
import com.iotower.core.protocol.OpHeader;
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
 * the transfer phase (§4.2). In the transfer phase this class is only the
 * <em>reader</em> (§5.1): it parses frames off the socket and hands each URB to
 * a {@link TransferEngine}, which owns the worker pool, seqnum correlation, and
 * the single writer thread. Backend-agnostic: the identical loop runs under
 * Android and under the desktop harness.
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
     * The transfer phase (§4.2, §5.1), entered once after a successful IMPORT.
     * This method is the <em>reader</em> thread: it parses 20-byte
     * {@code usbip_header_basic} + 28-byte command blocks (and any OUT payload)
     * off the socket and hands each URB to a {@link TransferEngine}, which owns
     * the worker pool, the seqnum-keyed correlation, cancellation, and the
     * single writer thread that serializes every reply on the wire. The reader
     * itself never writes to the socket and never blocks on a transfer.
     *
     * <p>Runs until the client detaches (EOF) or the stream desyncs; the engine
     * is always torn down in the {@code finally} so no threads leak across
     * client reconnects. The backend is <em>not</em> closed here — it outlives a
     * single connection.
     */
    private void runTransferPhase(InputStream in, OutputStream out) throws IOException {
        byte[] basicBytes = new byte[UsbIpHeaderBasic.BYTES];
        byte[] blockBytes = new byte[28]; // CmdSubmit.BYTES == CmdUnlink.BYTES == 28

        TransferEngine engine = new TransferEngine(backend, out, EXPORTED_DEVID);
        engine.start();
        try {
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
                            System.out.println(
                                    "[iotower] transfer phase: truncated OUT payload; closing");
                            return;
                        }
                    }
                    engine.submit(basic, cmd, outPayload);
                } else if (basic.command == UsbIp.CMD_UNLINK) {
                    CmdUnlink cmd = CmdUnlink.readFrom(
                            ByteBuffer.wrap(blockBytes).order(ByteOrder.BIG_ENDIAN));
                    engine.unlink(basic, cmd);
                } else {
                    System.out.println("[iotower] transfer phase: unexpected command "
                            + basic.command + "; closing (desynced stream)");
                    return;
                }
            }
        } finally {
            engine.shutdown();
        }
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
