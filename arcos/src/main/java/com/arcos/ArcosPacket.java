package com.arcos;

/**
 * One ARCOS frame, either being built for transmission or parsed on arrival.
 *
 * <p>The wire format is:
 *
 * <pre>
 *   FA FB &lt;count&gt; &lt;id&gt; &lt;payload...&gt; &lt;ckHi&gt; &lt;ckLo&gt;
 * </pre>
 *
 * where {@code count} is the number of bytes that follow it, which is the id plus
 * the payload plus the two checksum bytes. Multi-byte integers inside the payload
 * are <em>little-endian</em>, but the checksum in the trailer is big-endian — an
 * inconsistency in the protocol itself, not a mistake here.
 *
 * <p>Instances are not thread-safe. Build one per send, or reuse from a single
 * thread via {@link #reset}.
 */
public final class ArcosPacket {

    /** Offset of the count byte within a frame. */
    private static final int COUNT_INDEX = 2;
    /** Offset of the id byte, which is also where the checksum starts covering. */
    private static final int ID_INDEX = 3;
    /** sync1, sync2, count, id. */
    private static final int HEADER = 4;
    /** The two checksum bytes. */
    private static final int FOOTER = 2;

    private final byte[] buf = new byte[Arcos.MAX_PACKET];
    /** Bytes written so far when building, or the whole frame length when parsed. */
    private int len;
    /** Read cursor, used only when parsing. */
    private int read;
    /**
     * True for a packet that came off the wire. Such a packet already carries its
     * count byte and checksum inside {@link #buf}, so {@link #frame} must hand the
     * bytes back untouched rather than recomputing a trailer over them.
     */
    private boolean parsed;

    private ArcosPacket() { }

    // ---------- building ----------

    /** An empty packet with the given id and no payload yet. */
    public static ArcosPacket build(int id) {
        ArcosPacket p = new ArcosPacket();
        p.reset(id);
        return p;
    }

    /** A command with no argument, such as PULSE or ESTOP. */
    public static ArcosPacket command(int id) {
        return build(id);
    }

    /**
     * A command carrying one signed 16-bit argument — the shape of nearly every
     * motion command. Negative values travel as a magnitude under a different type
     * tag rather than as two's complement.
     */
    public static ArcosPacket commandInt(int id, int value) {
        if (value < Short.MIN_VALUE || value > 0xFFFF) {
            throw new IllegalArgumentException("argument out of range: " + value);
        }
        ArcosPacket p = build(id);
        if (value >= 0) {
            p.putU8(Arcos.ARG_INT);
            p.putU16(value);
        } else {
            p.putU8(Arcos.ARG_NINT);
            p.putU16(-value);
        }
        return p;
    }

    /**
     * A command whose single 16-bit argument is really two independent bytes, as
     * VEL2 uses. Reproduces ARIA's {@code com2Bytes} exactly, including the signed
     * narrowing: a high byte of 0x80 or above makes the value negative, so it goes
     * out under the negative-int tag and the firmware reassembles it. Anything else
     * would disagree with every other ARCOS client on the wire.
     */
    public static ArcosPacket commandTwoBytes(int id, int high, int low) {
        return commandInt(id, (short) (((high & 0xFF) << 8) + (low & 0xFF)));
    }

    /** A command carrying a length-prefixed byte string, such as SAY or POLLING. */
    public static ArcosPacket commandBytes(int id, byte[] value) {
        if (value.length > 0xFF) {
            throw new IllegalArgumentException("string argument too long: " + value.length);
        }
        ArcosPacket p = build(id);
        p.putU8(Arcos.ARG_STR);
        p.putU8(value.length);
        for (byte b : value) {
            p.putU8(b & 0xFF);
        }
        return p;
    }

    /** Clears the payload and re-targets this packet at {@code id}. */
    public ArcosPacket reset(int id) {
        buf[0] = (byte) Arcos.SYNC1;
        buf[1] = (byte) Arcos.SYNC2;
        buf[COUNT_INDEX] = 0;                // filled in by frame()
        buf[ID_INDEX] = (byte) id;
        len = HEADER;
        read = HEADER;
        parsed = false;
        return this;
    }

    /** Appends one byte. */
    public ArcosPacket putU8(int v) {
        require(1);
        buf[len++] = (byte) v;
        return this;
    }

    /** Appends a 16-bit value, little-endian. */
    public ArcosPacket putU16(int v) {
        require(2);
        buf[len++] = (byte) (v & 0xFF);
        buf[len++] = (byte) ((v >> 8) & 0xFF);
        return this;
    }

    /** Appends a signed 16-bit value as two's complement, little-endian. */
    public ArcosPacket putI16(int v) {
        return putU16(v & 0xFFFF);
    }

    /** Appends a NUL-terminated string, the form the robot uses in its SYNC2 reply. */
    public ArcosPacket putStr(String s) {
        for (int i = 0; i < s.length(); i++) {
            putU8(s.charAt(i) & 0xFF);
        }
        return putU8(0);
    }

    private void require(int n) {
        if (parsed) {
            throw new IllegalStateException("cannot append to a received packet");
        }
        // Leave room for the checksum so a packet can never be built that cannot
        // be finalized.
        if (len + n + FOOTER > buf.length) {
            throw new IllegalStateException("packet would overflow at " + (len + n) + " bytes");
        }
    }

    /**
     * The complete frame, with the count byte and checksum filled in. Returns a
     * fresh array each call, so the caller may hand it straight to a transport.
     */
    public byte[] frame() {
        if (parsed) {
            byte[] asReceived = new byte[len];
            System.arraycopy(buf, 0, asReceived, 0, len);
            return asReceived;
        }
        int payload = len - HEADER;
        buf[COUNT_INDEX] = (byte) (payload + 1 + FOOTER);   // id + payload + checksum
        int ck = checksum(buf, 0);
        byte[] out = new byte[len + FOOTER];
        System.arraycopy(buf, 0, out, 0, len);
        out[len] = (byte) ((ck >> 8) & 0xFF);
        out[len + 1] = (byte) (ck & 0xFF);
        return out;
    }

    // ---------- parsing ----------

    /**
     * Validates and wraps a complete frame. The frame must start at {@code off}
     * with the two sync bytes and run exactly {@code length} bytes.
     *
     * @throws ArcosException if the framing, length or checksum does not hold up
     */
    public static ArcosPacket parse(byte[] src, int off, int length) throws ArcosException {
        if (length < HEADER + FOOTER) {
            throw new ArcosException("frame too short: " + length + " bytes");
        }
        if ((src[off] & 0xFF) != Arcos.SYNC1 || (src[off + 1] & 0xFF) != Arcos.SYNC2) {
            throw new ArcosException("bad sync bytes");
        }
        int count = src[off + COUNT_INDEX] & 0xFF;
        if (count + 3 != length) {
            throw new ArcosException("count " + count + " disagrees with frame length " + length);
        }
        ArcosPacket p = new ArcosPacket();
        System.arraycopy(src, off, p.buf, 0, length);
        p.len = length;
        p.read = ID_INDEX + 1;
        p.parsed = true;

        int want = ((p.buf[length - 2] & 0xFF) << 8) | (p.buf[length - 1] & 0xFF);
        int got = checksum(p.buf, 0);
        if (want != got) {
            throw new ArcosException(String.format(
                    "checksum mismatch: frame says 0x%04X, computed 0x%04X", want, got));
        }
        return p;
    }

    /** The packet id — a command number outbound, a SIP type inbound. */
    public int id() {
        return buf[ID_INDEX] & 0xFF;
    }

    /** Payload bytes not yet consumed by the readers. */
    public int remaining() {
        return (len - FOOTER) - read;
    }

    /** Next payload byte, unsigned. */
    public int u8() {
        checkAvailable(1);
        return buf[read++] & 0xFF;
    }

    /** Next payload byte, signed. */
    public int i8() {
        checkAvailable(1);
        return buf[read++];
    }

    /** Next 16-bit payload value, unsigned, little-endian. */
    public int u16() {
        checkAvailable(2);
        int v = (buf[read] & 0xFF) | ((buf[read + 1] & 0xFF) << 8);
        read += 2;
        return v;
    }

    /** Next 16-bit payload value, signed, little-endian. */
    public int i16() {
        return (short) u16();
    }

    /** Next NUL-terminated string, as sent in the robot's SYNC2 reply. */
    public String str() {
        StringBuilder sb = new StringBuilder();
        while (remaining() > 0) {
            int c = u8();
            if (c == 0) {
                break;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    private void checkAvailable(int n) {
        if (remaining() < n) {
            throw new IllegalStateException(
                    "read past end of payload: wanted " + n + ", have " + remaining());
        }
    }

    // ---------- checksum ----------

    /**
     * The ARCOS checksum over the frame beginning at {@code off}: payload bytes are
     * summed as big-endian 16-bit pairs, and a lone trailing byte is XORed in.
     * Mirrors {@code ArRobotPacket::calcCheckSum} in ARIA.
     *
     * <p>The count byte must already be set, since it decides how much is covered.
     */
    public static int checksum(byte[] frame, int off) {
        int c = 0;
        int i = off + ID_INDEX;
        int n = (frame[off + COUNT_INDEX] & 0xFF) - FOOTER;
        while (n > 1) {
            c += ((frame[i] & 0xFF) << 8) | (frame[i + 1] & 0xFF);
            c &= 0xFFFF;
            n -= 2;
            i += 2;
        }
        if (n > 0) {
            c ^= frame[i] & 0xFF;
        }
        return c & 0xFFFF;
    }

    /** Hex dump of the finalized frame, for logging. */
    public String toHex() {
        byte[] f = frame();
        StringBuilder sb = new StringBuilder(f.length * 3);
        for (byte b : f) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    @Override public String toString() {
        return "ArcosPacket{id=" + id() + ", " + (len - HEADER) + " payload bytes}";
    }
}
