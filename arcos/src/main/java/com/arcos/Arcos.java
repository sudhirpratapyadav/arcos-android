package com.arcos;

/**
 * Protocol constants for ARCOS, the serial protocol spoken by the microcontroller
 * in Pioneer / PeopleBot / AmigoBot robots (P3-DX included).
 *
 * <p>Values are taken from ARIA's {@code ArCommands.h}. Only the subset that is
 * meaningful on a differential-drive Pioneer is listed; the full table also covers
 * Seekur lateral motion, MTX safety systems and the MobileSim meta-commands.
 */
public final class Arcos {

    private Arcos() { }

    // ---------- framing ----------

    /** First header byte of every packet, in both directions. */
    public static final int SYNC1 = 0xFA;
    /** Second header byte of every packet, in both directions. */
    public static final int SYNC2 = 0xFB;

    /**
     * Largest legal value of the count byte. ARIA sizes its packet buffer at 265,
     * which is 3 framing bytes plus a count that cannot exceed one unsigned byte.
     */
    public static final int MAX_COUNT = 0xFF;
    /** Largest possible whole frame: sync1, sync2, count, then count more bytes. */
    public static final int MAX_PACKET = 3 + MAX_COUNT;

    // ---------- argument type tags ----------

    /** Argument is a non-negative 16-bit int, little-endian. */
    public static final int ARG_INT = 0x3B;
    /** Argument is the magnitude of a negative 16-bit int, little-endian. */
    public static final int ARG_NINT = 0x1B;
    /** Argument is a length-prefixed byte string. */
    public static final int ARG_STR = 0x2B;

    // ---------- commands (host -> robot) ----------

    /** No argument. Resets the watchdog; must be sent at least every 2 s. */
    public static final int PULSE = 0;
    /** Also command 0: first byte of the connection handshake. */
    public static final int SYNC_0 = 0;
    /** Second handshake step. Doubles as {@link #OPEN} once synced. */
    public static final int SYNC_1 = 1;
    /** Third handshake step; the reply carries name, type and subtype. */
    public static final int SYNC_2 = 2;

    /** Begin the session. Sent once, after the handshake completes. */
    public static final int OPEN = 1;
    /** End the session and stop the robot. */
    public static final int CLOSE = 2;
    /** String: sets the sonar polling sequence. */
    public static final int POLLING = 3;
    /** Int: enable (1) or disable (0) the motors. */
    public static final int ENABLE = 4;
    /** Int: translational acceleration (+) or deceleration (-), mm/s/s. */
    public static final int SETA = 5;
    /** Int: maximum translational velocity, mm/s. */
    public static final int SETV = 6;
    /** Int: reset the odometry origin to (0, 0, 0). */
    public static final int SETO = 7;
    /** Int: translational move, mm. Relative to the current position. */
    public static final int MOVE = 8;
    /** Int: maximum rotational velocity, deg/s. */
    public static final int SETRV = 10;
    /** Int: translational velocity, mm/s. The main teleop command. */
    public static final int VEL = 11;
    /** Int: turn to an absolute heading, 0-359 degrees. */
    public static final int HEAD = 12;
    /** Int: turn relative to the current heading, degrees. */
    public static final int DHEAD = 13;
    /** String: beep. Up to 20 (duration, tone) pairs. */
    public static final int SAY = 15;
    /** Int: request a configuration packet. */
    public static final int CONFIG = 18;
    /** Int: 2 for a continuous stream of encoder packets, 0 to stop. */
    public static final int ENCODER = 19;
    /** Int: rotational velocity, deg/s. Positive is counter-clockwise. */
    public static final int RVEL = 21;
    /** Int: rotational acceleration (+) or deceleration (-), deg/s/s. */
    public static final int SETRA = 23;
    /** Int: enable (1) or disable (0) the sonar array. */
    public static final int SONAR = 28;
    /** Int: decelerate to a stop, obeying the configured deceleration. */
    public static final int STOP = 29;
    /** Int: set the digital output lines. */
    public static final int DIGOUT = 30;
    /**
     * Int: independent wheel velocities. High byte right, low byte left, each a
     * signed count multiplied by the robot's Vel2 divisor.
     */
    public static final int VEL2 = 32;
    /** Int: request IO packets. */
    public static final int IOREQUEST = 40;
    /** Int: stop and flag a stall when the bump ring fires. */
    public static final int BUMPSTALL = 44;
    /** Int: host port baud. 0=9600, 1=19200, 2=38400, 3=57600, 4=115200. */
    public static final int HOSTBAUD = 50;
    /** No argument. Emergency stop; ignores the deceleration limit. */
    public static final int ESTOP = 55;
    /** Int: enable (1) or disable (0) gyro packets. */
    public static final int GYRO = 58;

    // ---------- packet ids (robot -> host) ----------

    /** Standard Server Information Packet. */
    public static final int SIP_STANDARD = 0x32;
    /** Extended Server Information Packet; same layout as {@link #SIP_STANDARD}. */
    public static final int SIP_EXTENDED = 0x33;
    /** Configuration packet, sent in reply to {@link #CONFIG}. */
    public static final int SIP_CONFIG = 0x20;
    /** Encoder packet, sent when {@link #ENCODER} streaming is on. */
    public static final int SIP_ENCODER = 0x90;

    /** Baud code accepted by {@link #HOSTBAUD} for the given rate, or -1. */
    public static int baudCode(int baud) {
        switch (baud) {
            case 9600:   return 0;
            case 19200:  return 1;
            case 38400:  return 2;
            case 57600:  return 3;
            case 115200: return 4;
            default:     return -1;
        }
    }

    /** True when {@code id} is a motion/status SIP this library can parse. */
    public static boolean isSip(int id) {
        return id == SIP_STANDARD || id == SIP_EXTENDED;
    }
}
