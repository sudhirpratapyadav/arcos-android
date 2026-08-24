package com.arcos;

/**
 * Turns a byte stream into packets.
 *
 * <p>A serial line hands over whatever happened to arrive: partial frames, two
 * frames at once, and — after a baud switch or a cable knock — pure noise. The
 * framer buffers bytes and pulls out complete, checksum-valid frames.
 *
 * <p>The important property is that a bad frame cannot hide a good one. Scanning
 * restarts one byte after a failed sync pair rather than discarding everything
 * accumulated, so a truncated packet immediately followed by a valid packet still
 * yields the valid one. Consuming greedily instead loses a real frame every time
 * the link glitches mid-packet.
 *
 * <p>Not thread-safe: use one per stream, from one thread. Public because anyone
 * implementing the other side of the link — a bridge, a simulator, a test — needs
 * exactly this.
 */
public final class PacketFramer {

    /** Room for many packets, so a burst does not force a drop. */
    private static final int CAPACITY = 8192;

    private final byte[] buf = new byte[CAPACITY];
    private int len;
    private String lastError;
    private int dropped;

    /** Appends raw bytes from the transport. */
    public void append(byte[] src, int off, int count) {
        if (count <= 0) {
            return;
        }
        if (count > CAPACITY) {
            // More than the buffer holds in one read: keep only the newest bytes,
            // since the older ones can no longer be part of a frame we will complete.
            off += count - CAPACITY;
            count = CAPACITY;
        }
        if (len + count > CAPACITY) {
            // Make room by dropping the oldest bytes. Anything older than a maximum
            // packet cannot still be the start of a frame we would accept.
            int drop = len + count - CAPACITY;
            System.arraycopy(buf, drop, buf, 0, len - drop);
            len -= drop;
            dropped += drop;
        }
        System.arraycopy(src, off, buf, len, count);
        len += count;
    }

    /**
     * The next complete packet, or null when the buffer holds no full valid frame
     * yet. Call repeatedly until it returns null.
     */
    public ArcosPacket next() {
        int i = 0;
        while (true) {
            // Find a sync pair.
            while (i + 1 < len
                    && !((buf[i] & 0xFF) == Arcos.SYNC1 && (buf[i + 1] & 0xFF) == Arcos.SYNC2)) {
                i++;
            }
            if (i + 2 >= len) {
                // Not enough bytes for even a header; keep what might still start one.
                consume(i);
                return null;
            }
            int count = buf[i + 2] & 0xFF;
            if (count < 3) {
                // No legal packet is this short, so that was not a real header.
                i++;
                continue;
            }
            int total = count + 3;
            if (i + total > len) {
                consume(i);
                return null;                      // wait for the rest of this frame
            }
            try {
                ArcosPacket p = ArcosPacket.parse(buf, i, total);
                consume(i + total);
                return p;
            } catch (ArcosException e) {
                lastError = e.getMessage();
                dropped++;
                i++;                              // resync just past this false start
            }
        }
    }

    /** Drops everything buffered, for use after a baud change. */
    public void reset() {
        len = 0;
    }

    /** Bytes currently buffered but not yet forming a packet. */
    public int buffered() {
        return len;
    }

    /** Why the most recent frame was rejected, or null if none has been. */
    public String lastError() {
        return lastError;
    }

    /** Count of rejected frames and discarded bytes since construction. */
    public int droppedCount() {
        return dropped;
    }

    private void consume(int upTo) {
        if (upTo <= 0) {
            return;
        }
        System.arraycopy(buf, upTo, buf, 0, len - upTo);
        len -= upTo;
    }
}
