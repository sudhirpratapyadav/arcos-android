package com.arcos.transport;

import com.arcos.Transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * ARCOS over a TCP socket.
 *
 * <p>Two uses. MobileSim, the official simulator, listens on port 8101 and speaks
 * the same protocol, so pointing this at a laptop gives a full robot to develop
 * against. The same transport also reaches a serial-to-Ethernet bridge or an
 * onboard PC running a passthrough, which is how a phone can drive a robot over
 * Wi-Fi instead of a cable.
 *
 * <p>There is no baud rate on a socket, so the library's baud probing and
 * auto-switching quietly do nothing here.
 */
public final class TcpTransport implements Transport {

    /** The port MobileSim listens on. */
    public static final int MOBILESIM_PORT = 8101;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean open;

    /** MobileSim on {@code host}, using its default port. */
    public TcpTransport(String host) {
        this(host, MOBILESIM_PORT, 5000);
    }

    public TcpTransport(String host, int port) {
        this(host, port, 5000);
    }

    public TcpTransport(String host, int port, int connectTimeoutMs) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override public void open() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        // Motion commands are tiny and latency matters more than packing them.
        s.setTcpNoDelay(true);
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        open = true;
    }

    @Override public int read(byte[] dst, int off, int len, int timeoutMs) throws IOException {
        Socket s = socket;
        InputStream stream = in;
        if (!open || s == null || stream == null) {
            throw new IOException("socket is closed");
        }
        s.setSoTimeout(Math.max(1, timeoutMs));
        try {
            int n = stream.read(dst, off, len);
            if (n < 0) {
                throw new IOException("robot closed the connection");
            }
            return n;
        } catch (SocketTimeoutException e) {
            return 0;                       // quiet, not gone
        }
    }

    @Override public void write(byte[] data) throws IOException {
        OutputStream stream = out;
        if (!open || stream == null) {
            throw new IOException("socket is closed");
        }
        stream.write(data);
        stream.flush();
    }

    @Override public boolean isOpen() {
        return open;
    }

    @Override public String name() {
        return "tcp " + host + ":" + port;
    }

    @Override public void close() {
        open = false;
        Socket s = socket;
        socket = null;
        in = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Already gone.
            }
        }
    }
}
