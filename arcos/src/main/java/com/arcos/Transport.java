package com.arcos;

import java.io.Closeable;
import java.io.IOException;

/**
 * A byte pipe to the robot's microcontroller. Everything above this interface is
 * plain protocol logic, so the same {@link ArcosRobot} drives a USB-serial cable,
 * a TCP socket to MobileSim, or the built-in simulator.
 *
 * <p>Implementations are used from a single background thread, except for
 * {@link #close}, which may arrive from any thread and must interrupt a blocked
 * {@link #read}.
 */
public interface Transport extends Closeable {

    /**
     * Opens the underlying device. Called once, before any read or write.
     *
     * @throws IOException if the device is missing, busy, or refuses permission
     */
    void open() throws IOException;

    /**
     * Reads whatever bytes are available, blocking up to {@code timeoutMs}.
     *
     * @return the number of bytes placed in {@code dst}, or 0 if the timeout
     *         elapsed with nothing to read. Never returns -1; a closed pipe should
     *         throw instead, so the caller can tell "quiet" from "gone".
     */
    int read(byte[] dst, int off, int len, int timeoutMs) throws IOException;

    /** Writes a whole frame. Should block until the bytes are handed to the device. */
    void write(byte[] data) throws IOException;

    /** True between a successful {@link #open} and a {@link #close}. */
    boolean isOpen();

    /** Human-readable description for logs and UI, e.g. {@code "USB CP2102 @38400"}. */
    String name();

    /**
     * Changes the line rate, for transports where that means something. The default
     * does nothing, which is correct for sockets and for the simulator.
     *
     * <p>Called only after the robot has been told to switch, so an implementation
     * that cannot comply should throw rather than silently stay at the old rate.
     */
    default void setBaudRate(int baud) throws IOException {
        // Not a serial line; nothing to change.
    }

    /** True when {@link #setBaudRate} is meaningful on this transport. */
    default boolean supportsBaudRate() {
        return false;
    }

    /** Current line rate, or 0 when the transport has no such notion. */
    default int baudRate() {
        return 0;
    }

    /** Discards anything already buffered on the input side. */
    default void flushInput() throws IOException {
        // Best effort; implementations with a real buffer should override.
    }

    @Override void close();
}
