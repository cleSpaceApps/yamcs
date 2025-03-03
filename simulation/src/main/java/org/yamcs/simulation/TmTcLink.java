package org.yamcs.simulation;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractExecutionThreadService;

/**
 * TCP link that can be used both for TM and TC
 */
public class TmTcLink extends AbstractExecutionThreadService {

    private static final Logger log = LoggerFactory.getLogger(TmTcLink.class);
    final String name;
    private Simulator simulator;
    int port;
    volatile boolean connected;
    Socket socket;
    ServerSocket serverSocket;
    DataInputStream inputStream;

    private int maxTcLength = Simulator.DEFAULT_MAX_LENGTH;
    private int MIN_TC_LENGTH = 16;

    private BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(100);

    public TmTcLink(String name, Simulator simulator, int port) {
        this.name = name;
        this.simulator = simulator;
        this.port = port;
    }

    public void sendPacket(byte[] packet) {
        try {
            if (connected) {
                queue.put(packet);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void run() throws Exception {
        while (isRunning()) {
            if (!connected) {
                connect();
            }
            if (!connected) {
                continue;
            }
            try {
                byte[] p = queue.poll(1, TimeUnit.SECONDS);
                if (p != null) {
                    socket.getOutputStream().write(p);
                }
            } catch (IOException e1) {
                log.error("Error while sending " + name + " packet", e1);
                connect();
            }

            try {
                if (socket.getInputStream().available() > 0) {
                    CCSDSPacket tc = readPacket(inputStream);
                    if (tc != null) {
                        simulator.processTc(tc);
                    } else {
                        connected = false;
                    }
                }
            } catch (IOException e1) {
                log.error("Error while receiving packet on " + name + " socket", e1);
                connect();
            }
        }
    }

    void connect() {
        // Check for previous connection, used for loss of server.
        if (socket != null) {
            try {
                connected = false;
                inputStream.close();
                socket.close();
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            log.debug("Waiting for {} connection from server on port {}", name, port);
            serverSocket = new ServerSocket(port);
            socket = serverSocket.accept();
            inputStream = new DataInputStream(socket.getInputStream());
            connected = true;
            log.debug("Connected: {}:{}", socket.getInetAddress(), socket.getPort());
        } catch (Exception e) {
            if (isRunning()) {
                e.printStackTrace();
            }
        }
    }

    public void ackPacketSend(CCSDSPacket packet) {
        if (connected) {
            try {
                packet.writeTo(socket.getOutputStream());
            } catch (IOException e1) {
                log.error("Error while sending {} packet", name, e1);
                connect();
            }
        }
    }

    CCSDSPacket readPacket(DataInputStream dIn) {
        try {
            byte hdr[] = new byte[6];
            dIn.readFully(hdr);
            int remaining = ((hdr[4] & 0xFF) << 8) + (hdr[5] & 0xFF) + 1;
            if (remaining < MIN_TC_LENGTH - 6) {
                throw new IOException("Command too short: " + (remaining + 6) + " minimum required " + MIN_TC_LENGTH);
            }
            if (remaining > maxTcLength - 6) {
                throw new IOException(
                        "Remaining packet length too big: " + remaining + " maximum allowed is " + (maxTcLength - 6));
            }
            byte[] b = new byte[6 + remaining];
            System.arraycopy(hdr, 0, b, 0, 6);
            dIn.readFully(b, 6, remaining);
            CCSDSPacket packet = new CCSDSPacket(ByteBuffer.wrap(b));
            return packet;
        } catch (EOFException e) {
            log.error(name + " Connection lost");
            connected = false;
        } catch (Exception e) {
            connected = false;
            log.error("Error reading command " + e.getMessage(), e);
        }
        return null;
    }

    @Override
    protected void triggerShutdown() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // ignore error
            }
        }
    }

}
