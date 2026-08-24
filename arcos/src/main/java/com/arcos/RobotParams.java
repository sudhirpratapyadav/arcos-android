package com.arcos;

/**
 * Per-model conversion factors. The microcontroller reports distances, angles and
 * velocities in its own encoder units; these turn them into millimetres, radians
 * and mm/s. The numbers come from the {@code params/*.p} files shipped with ARIA.
 *
 * <p>Using the wrong model here does not break the connection — it silently scales
 * the odometry and the commanded speeds, which is a nastier failure. Pick the model
 * that matches the robot, or read it from the subtype the robot reports on connect
 * via {@link #forSubtype}.
 */
public final class RobotParams {

    /** Model subtype string as the robot reports it, e.g. {@code "p3dx"}. */
    public final String subtype;
    /** Millimetres per encoder distance unit. */
    public final double distConvFactor;
    /** Radians per angular unit. */
    public final double angleConvFactor;
    /** mm/s per reported velocity unit. */
    public final double velConvFactor;
    /** Millimetres per sonar range unit. */
    public final double rangeConvFactor;
    /** Divisor applied to each VEL2 wheel byte. */
    public final double vel2Divisor;
    /** Absolute ceiling on translational velocity, mm/s. */
    public final int maxVelocity;
    /** Absolute ceiling on rotational velocity, deg/s. */
    public final int maxRotVelocity;
    /** Body radius, mm. Useful to callers doing obstacle avoidance. */
    public final int robotRadius;
    /** Baud to switch the host link to after connecting, or 0 to stay put. */
    public final int switchToBaud;

    public RobotParams(String subtype, double distConvFactor, double angleConvFactor,
                       double velConvFactor, double rangeConvFactor, double vel2Divisor,
                       int maxVelocity, int maxRotVelocity, int robotRadius,
                       int switchToBaud) {
        this.subtype = subtype;
        this.distConvFactor = distConvFactor;
        this.angleConvFactor = angleConvFactor;
        this.velConvFactor = velConvFactor;
        this.rangeConvFactor = rangeConvFactor;
        this.vel2Divisor = vel2Divisor;
        this.maxVelocity = maxVelocity;
        this.maxRotVelocity = maxRotVelocity;
        this.robotRadius = robotRadius;
        this.switchToBaud = switchToBaud;
    }

    /** Pioneer 3-DX. The default, and the model this library was written against. */
    public static final RobotParams P3DX = new RobotParams(
            "p3dx", 0.485, 0.001534, 1.0, 1.0, 20.0, 2200, 500, 250, 38400);

    /** Pioneer 3-AT, the four-wheel outdoor chassis. */
    public static final RobotParams P3AT = new RobotParams(
            "p3at", 0.465, 0.001534, 1.0, 1.0, 20.0, 2200, 500, 267, 38400);

    /** Pioneer 2-DX, the earlier H8-based model. */
    public static final RobotParams P2DX = new RobotParams(
            "p2dx", 0.969, 0.001534, 1.0, 1.0, 20.0, 1800, 360, 220, 9600);

    /** PeopleBot, a P3-DX chassis with a taller mast. */
    public static final RobotParams PEOPLEBOT = new RobotParams(
            "peoplebot", 0.485, 0.001534, 1.0, 1.0, 20.0, 2200, 500, 250, 38400);

    /** AmigoBot, the small desktop platform. */
    public static final RobotParams AMIGO = new RobotParams(
            "amigo", 1.0, 0.001534, 0.6154, 1.0, 20.0, 1000, 300, 180, 0);

    private static final RobotParams[] KNOWN = {P3DX, P3AT, P2DX, PEOPLEBOT, AMIGO};

    /**
     * Parameters for the subtype string the robot reported, falling back to
     * {@link #P3DX}. The comparison is case-insensitive because firmware versions
     * differ on capitalisation.
     */
    public static RobotParams forSubtype(String reported) {
        if (reported != null) {
            String s = reported.trim().toLowerCase();
            for (RobotParams p : KNOWN) {
                if (p.subtype.equals(s)) {
                    return p;
                }
            }
        }
        return P3DX;
    }

    /** True when {@link #forSubtype} would have to guess for this string. */
    public static boolean isKnownSubtype(String reported) {
        if (reported == null) {
            return false;
        }
        String s = reported.trim().toLowerCase();
        for (RobotParams p : KNOWN) {
            if (p.subtype.equals(s)) {
                return true;
            }
        }
        return false;
    }

    @Override public String toString() {
        return "RobotParams{" + subtype + "}";
    }
}
