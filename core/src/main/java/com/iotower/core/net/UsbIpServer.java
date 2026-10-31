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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/**
 * The USB/IP server loop (§3, §6). Listens on {@code 0.0.0.0:3240} and serves
 * <b>several clients concurrently</b>: the kernel opens one TCP connection per
 * {@code usbip attach}, so each accepted connection gets its own handler thread
 * running negotiation (DEVLIST/IMPORT) then the transfer phase. Backend-agnostic:
 * it is handed {@link UsbBackend}s, so the identical loop runs under Android and
 * under the desktop harness.
 *
 * <p>Exports a registry of {@link ExportedDevice}s (§10): {@code OP_REQ_DEVLIST}
 * (§4.1) advertises all of them; {@code OP_REQ_IMPORT} (§4.1) matches the
 * requested busid to the right backend and switches the socket into the transfer
 * phase (§4.2) — unless that busid is unknown or already in its transfer phase
 * ("device in use"). In the transfer phase each connection is only the
 * <em>reader</em> (§5.1): it parses frames off its socket and hands each URB to
 * its own {@link TransferEngine}, which owns the worker pool, seqnum correlation,
 * and that socket's single writer thread. Nothing is shared between connections
 * except the thread-safe registry and imported-busid map.
 */
public final class UsbIpServer implements Runnable {

    private final int port;
    private volatile boolean running;

    /** Registered devices, in registration order (DEVLIST order). */
    private final CopyOnWriteArrayList<ExportedDevice> devices = new CopyOnWriteArrayList<>();
    /** busid -> the client socket currently in its transfer phase for it. */
    private final ConcurrentHashMap<String, Socket> importedBy = new ConcurrentHashMap<>();
    /** Every live client socket, so stop() can close them all. */
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();

    // Testability affordance (production-invisible): lets an L1 test bind an
    // ephemeral port and learn it race-free, and lets stop() actually unblock
    // a server thread parked in accept().
    private volatile ServerSocket serverSocket;
    private final CountDownLatch boundLatch = new CountDownLatch(1);
    private volatile int boundPort = -1;

    /** Empty registry on the standard port; add devices with {@link #addDevice}. */
    public UsbIpServer() {
        this(UsbIp.PORT);
    }

    /** Empty registry on {@code port} (0 = ephemeral); add devices with {@link #addDevice}. */
    public UsbIpServer(int port) {
        this.port = port;
    }

    /** Single-device convenience: registers {@code backend} as {@code (1, 1)} => busid "1-1". */
    public UsbIpServer(UsbBackend backend) {
        this(backend, UsbIp.PORT);
    }

    /** Single-device convenience on {@code port}: registers {@code backend} as {@code (1, 1)}. */
    public UsbIpServer(UsbBackend backend, int port) {
        this(port);
        addDevice(backend, 1, 1);
    }

    /**
     * Registers a device (§10). Idempotent per {@code (busnum, devnum)}: if that
     * address is already registered the existing entry is returned unchanged.
     */
    public synchronized ExportedDevice addDevice(UsbBackend backend, int busnum, int devnum) {
        for (ExportedDevice d : devices) {
            if (d.busnum == busnum && d.devnum == devnum) {
                return d;
            }
        }
        ExportedDevice d = new ExportedDevice(backend, busnum, devnum);
        devices.add(d);
        return d;
    }

    /**
     * Unregisters {@code busid}; a client currently importing it has its
     * connection closed (its reader unblocks and its engine shuts down). The
     * caller releases the backend. Returns whether the busid was registered.
     */
    public synchronized boolean removeDevice(String busid) {
        boolean removed = false;
        for (ExportedDevice d : devices) {
            if (d.busid.equals(busid)) {
                removed = devices.remove(d);
                break;
            }
        }
        Socket importer = importedBy.get(busid);
        if (importer != null) {
            closeQuietly(importer);
        }
        return removed;
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
                activeClients.add(client);
                Thread t = new Thread(() -> handle(client), "usbip-client-" + client.getPort());
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) e.printStackTrace();
        }
    }

    private void handle(Socket client) {
        try {
            if (!running) {
                // stop() ran between accept() and registering the socket, so it may
                // have missed it; don't start serving it.
                return;
            }
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
                    handleImport(client, in, out);
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
            closeQuietly(client);
            activeClients.remove(client);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    /** One describable device's DEVLIST payload, resolved up front so count and body agree. */
    private static final class DevlistEntry {
        final ExportedDevice device;
        final DeviceInfo info;
        final List<InterfaceInfo> interfaces;

        DevlistEntry(ExportedDevice device, DeviceInfo info, List<InterfaceInfo> interfaces) {
            this.device = device;
            this.info = info;
            this.interfaces = interfaces;
        }
    }

    private void handleDevlist(OutputStream out) throws IOException {
        // Invariant 6: a device whose backend yields no descriptors/deviceInfo is
        // skipped (not fatal); the count reflects only successfully-described devices.
        List<DevlistEntry> entries = new ArrayList<>();
        for (ExportedDevice d : devices) {
            try {
                byte[] rawDescriptors = d.backend.rawDescriptors();
                DeviceInfo deviceInfo = d.backend.deviceInfo();
                if (rawDescriptors == null || deviceInfo == null) {
                    System.out.println("[iotower] " + d.busid
                            + ": no descriptors/deviceInfo; omitting from DEVLIST");
                    continue;
                }
                EndpointMap map = DescriptorParser.parse(rawDescriptors);
                entries.add(new DevlistEntry(d, deviceInfo, map.interfaces()));
            } catch (RuntimeException e) {
                System.out.println("[iotower] " + d.busid + ": cannot describe (" + e
                        + "); omitting from DEVLIST");
            }
        }

        int size = OpHeader.BYTES + 4;
        for (DevlistEntry e : entries) {
            size += UsbIpUsbDevice.BYTES + e.info.numInterfaces * UsbIpUsbInterface.BYTES;
        }
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);

        new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_DEVLIST, UsbIp.STATUS_OK).writeTo(buf);
        buf.putInt(entries.size()); // exported-device count (N, possibly 0)

        for (DevlistEntry e : entries) {
            int n = e.info.numInterfaces;
            buildExportedDevice(e.device, e.info, n).writeTo(buf);
            for (int i = 0; i < n; i++) {
                int cls = 0, sub = 0, proto = 0;
                if (i < e.interfaces.size()) {
                    InterfaceInfo info = e.interfaces.get(i);
                    cls = info.bInterfaceClass;
                    sub = info.bInterfaceSubClass;
                    proto = info.bInterfaceProtocol;
                }
                new UsbIpUsbInterface(cls, sub, proto, 0).writeTo(buf);
            }
        }

        out.write(buf.array(), 0, buf.position());
        out.flush();
    }

    /**
     * Builds the {@code usbip_usb_device} struct shared verbatim by DEVLIST and
     * IMPORT (§4.1), from the device's invented identity and the backend's
     * {@link DeviceInfo}, so the two replies can never drift.
     */
    private static UsbIpUsbDevice buildExportedDevice(
            ExportedDevice d, DeviceInfo deviceInfo, int numInterfaces) {
        return new UsbIpUsbDevice(
                d.path, d.busid, d.busnum, d.devnum,
                deviceInfo.speed, deviceInfo.idVendor, deviceInfo.idProduct, deviceInfo.bcdDevice,
                deviceInfo.deviceClass, deviceInfo.deviceSubClass, deviceInfo.deviceProtocol,
                deviceInfo.configurationValue, deviceInfo.numConfigurations, numInterfaces);
    }

    private static void rejectImport(OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(OpHeader.BYTES).order(ByteOrder.BIG_ENDIAN);
        new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_IMPORT, /* status */ 1).writeTo(buf);
        out.write(buf.array());
        out.flush();
    }

    private ExportedDevice findDevice(String busid) {
        for (ExportedDevice d : devices) {
            if (d.busid.equals(busid)) {
                return d;
            }
        }
        return null;
    }

    /**
     * {@code OP_REQ_IMPORT} (§4.1): read the 32-byte busid, match it against the
     * registry, reply, and on success switch the same socket into the transfer
     * phase (§4.2) for <em>that</em> device — does not close here; the caller's
     * {@code finally} does, once {@link #runTransferPhase} returns. Unknown busid
     * and busid already in its transfer phase ("device in use") are rejected.
     */
    private void handleImport(Socket client, InputStream in, OutputStream out) throws IOException {
        byte[] busidBytes = new byte[32];
        if (!readFully(in, busidBytes)) {
            System.out.println("[iotower] OP_REQ_IMPORT: truncated busid; closing");
            return;
        }
        String busid = nulTrim(busidBytes);

        ExportedDevice device = findDevice(busid);
        if (device == null) {
            System.out.println("[iotower] OP_REQ_IMPORT: unknown busid '" + busid + "'; rejecting");
            rejectImport(out);
            return;
        }

        byte[] rawDescriptors = device.backend.rawDescriptors();
        DeviceInfo deviceInfo = device.backend.deviceInfo();
        if (rawDescriptors == null || deviceInfo == null) {
            System.out.println("[iotower] " + busid + ": no descriptors/deviceInfo; closing");
            return;
        }

        // Claim the busid for this connection; a second claimant is "device in use".
        if (importedBy.putIfAbsent(busid, client) != null) {
            System.out.println("[iotower] OP_REQ_IMPORT: busid '" + busid
                    + "' already in use; rejecting");
            rejectImport(out);
            return;
        }
        try {
            ByteBuffer buf = ByteBuffer.allocate(OpHeader.BYTES + UsbIpUsbDevice.BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            new OpHeader(UsbIp.VERSION, UsbIp.OP_REP_IMPORT, UsbIp.STATUS_OK).writeTo(buf);
            buildExportedDevice(device, deviceInfo, deviceInfo.numInterfaces).writeTo(buf);
            out.write(buf.array());
            out.flush();

            runTransferPhase(in, out, device);
        } finally {
            // Free the busid so a re-attach of the same device works.
            importedBy.remove(busid, client);
        }
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
    private void runTransferPhase(InputStream in, OutputStream out, ExportedDevice device)
            throws IOException {
        byte[] basicBytes = new byte[UsbIpHeaderBasic.BYTES];
        byte[] blockBytes = new byte[28]; // CmdSubmit.BYTES == CmdUnlink.BYTES == 28

        TransferEngine engine = new TransferEngine(device.backend, out, device.devid);
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
        // Unblock every handler thread parked in a read on an attached client; each
        // one's normal unwinding (engine shutdown, client close) does the rest.
        for (Socket client : activeClients) {
            closeQuietly(client);
        }
    }
}
