package com.iotower.core.net;

import com.iotower.core.protocol.UsbIp;
import com.iotower.core.usb.UsbBackend;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The USB/IP server loop (§3, §6). Listens on {@code 0.0.0.0:3240}, one client
 * at a time for v1. Negotiation (DEVLIST/IMPORT) then the transfer phase run on
 * the accepted socket. Backend-agnostic: it is handed a {@link UsbBackend}, so
 * the identical loop runs under Android and under the desktop harness.
 *
 * <p>This skeleton accepts a connection and sets {@code tcpNoDelay} (§6); the
 * protocol handling is filled in over milestones 2–3.
 */
public final class UsbIpServer implements Runnable {

    private final UsbBackend backend;
    private final int port;
    private volatile boolean running;

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
            System.out.println("[iotower] USB/IP server listening on 0.0.0.0:" + port);
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
        // TODO(milestone 2): OP_REQ_DEVLIST / OP_REQ_IMPORT negotiation.
        // TODO(milestone 3): switch to the transfer phase and drive the engine.
        try {
            client.close();
        } catch (IOException ignored) {
        }
    }

    public void stop() {
        running = false;
    }
}
