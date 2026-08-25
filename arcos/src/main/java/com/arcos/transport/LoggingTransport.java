package com.arcos.transport;

import com.arcos.Transport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Wraps another transport and keeps a ring buffer of the bytes crossing it.
 *
 * <p>When a robot will not talk, the question is always the same: is anything
 * going out, and is anything coming back? On a phone there is no serial monitor
 * to answer that, so the transport keeps its own record.
 *
 * <pre>
 *   LoggingTransport tap = new LoggingTransport(new UsbSerialTransport(ctx));
 *   ArcosRobot robot = new ArcosRobot(tap);
 *   ...
 *   for (String line : tap.recent()) Log.d("arcos", line);
 * </pre>
 *
 * <p>Cheap enough to leave on: it stores hex strings for the last few hundred
 * exchanges and nothing else.
 */
public final class LoggingTransport implements Transport {

    /** Exchanges retained. A connected robot produces about 20 a second. */
    private static final int DEFAULT_CAPACITY = 400;
    /** Bytes of any single transfer that get recorded. */
    private static final int MAX_BYTES_LOGGED = 64;

    private final Transport delegate;
    private final int capacity;
    private final Deque<String> entries = new ArrayDeque<>();
    private final long startedAt = System.currentTimeMillis();

    private long bytesOut;
    private long bytesIn;

    public LoggingTransport(Transport delegate) {
        this(delegate, DEFAULT_CAPACITY);
    }

    public LoggingTransport(Transport delegate, int capacity) {
        this.delegate = delegate;
        this.capacity = capacity;
    }

    /** The captured exchanges, oldest first. */
    public List<String> recent() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    /** Total bytes written since construction. */
    public long bytesOut() {
        return bytesOut;
    }

    /** Total bytes read since construction. */
    public long bytesIn() {
        return bytesIn;
    }

    /** Discards the captured history, keeping the byte totals. */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private void record(String direction, byte[] data, int off, int len) {
        StringBuilder sb = new StringBuilder(32 + len * 3);
        sb.append(String.format("%7d ", System.currentTimeMillis() - startedAt));
        sb.append(direction).append(' ');
        sb.append('(').append(len).append(") ");
        int shown = Math.min(len, MAX_BYTES_LOGGED);
        for (int i = 0; i < shown; i++) {
            sb.append(String.format("%02X ", data[off + i]));
        }
        if (shown < len) {
            sb.append("... +").append(len - shown);
        }
        synchronized (entries) {
            entries.addLast(sb.toString().trim());
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
    }

    @Override public void open() throws IOException {
        delegate.open();
        record("--", new byte[0], 0, 0);
    }

    @Override public int read(byte[] dst, int off, int len, int timeoutMs) throws IOException {
        int n = delegate.read(dst, off, len, timeoutMs);
        if (n > 0) {
            bytesIn += n;
            record("RX", dst, off, n);
        }
        return n;
    }

    @Override public void write(byte[] data) throws IOException {
        bytesOut += data.length;
        record("TX", data, 0, data.length);
        delegate.write(data);
    }

    @Override public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override public String name() {
        return delegate.name();
    }

    @Override public void setBaudRate(int baud) throws IOException {
        record("--", new byte[0], 0, 0);
        delegate.setBaudRate(baud);
    }

    @Override public boolean supportsBaudRate() {
        return delegate.supportsBaudRate();
    }

    @Override public int baudRate() {
        return delegate.baudRate();
    }

    @Override public void flushInput() throws IOException {
        delegate.flushInput();
    }

    @Override public void close() {
        delegate.close();
    }
}
