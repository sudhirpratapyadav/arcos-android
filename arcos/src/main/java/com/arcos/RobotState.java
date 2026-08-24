package com.arcos;

import java.util.Arrays;

/**
 * A decoded Server Information Packet: everything the robot volunteers about
 * itself, roughly ten times a second, once the session is open.
 *
 * <p>Instances are immutable snapshots. {@link ArcosRobot} hands a fresh one to
 * listeners on each SIP, so holding a reference is safe.
 *
 * <p>Pose is in the odometry frame, which starts wherever the robot was when its
 * controller powered up unless {@link ArcosRobot#resetOdometry} has been called.
 * It drifts, as wheel odometry does; it is not a map position.
 */
public final class RobotState {

    /** X in the odometry frame, mm. */
    public final double x;
    /** Y in the odometry frame, mm. */
    public final double y;
    /** Heading in the odometry frame, degrees, normalised to (-180, 180]. */
    public final double theta;
    /** Left wheel velocity, mm/s. */
    public final double leftVel;
    /** Right wheel velocity, mm/s. */
    public final double rightVel;
    /** Battery voltage. Below about 11 V on a 12 V pack means stop and charge. */
    public final double batteryVoltage;
    /** True when the motors are enabled and will act on velocity commands. */
    public final boolean motorsEnabled;
    /** True when the red emergency-stop button is latched down. */
    public final boolean eStopPressed;
    /** True when the firmware thinks the sonar array is running. */
    public final boolean sonarEnabled;
    /** True when the left wheel is being commanded but is not turning. */
    public final boolean leftStall;
    /** True when the right wheel is being commanded but is not turning. */
    public final boolean rightStall;
    /** Raw flags word, for bits this class does not break out. */
    public final int flags;
    /** Compass heading in degrees, or 0 when no compass is fitted. */
    public final double compass;
    /**
     * Latest sonar reading per transducer, in mm, indexed by sonar number.
     * A value of -1 means that transducer has not reported since connecting.
     * A P3-DX with both arrays has 16.
     */
    public final int[] sonar;
    /** Analog input, 0-255, or -1 when the packet did not carry IO data. */
    public final int analog;
    /** Digital input byte, or -1 when the packet did not carry IO data. */
    public final int digIn;
    /** Digital output byte, or -1 when the packet did not carry IO data. */
    public final int digOut;
    /** {@link System#currentTimeMillis} when the packet was decoded. */
    public final long timestamp;

    RobotState(double x, double y, double theta, double leftVel, double rightVel,
               double batteryVoltage, int flags, int stallValue, double compass,
               int[] sonar, int analog, int digIn, int digOut, long timestamp) {
        this.x = x;
        this.y = y;
        this.theta = theta;
        this.leftVel = leftVel;
        this.rightVel = rightVel;
        this.batteryVoltage = batteryVoltage;
        this.flags = flags;
        this.motorsEnabled = (flags & 0x01) != 0;
        // Bits 1-4 each cover one sonar array; any of them means sonar is running.
        this.sonarEnabled = (flags & 0x1E) != 0;
        this.eStopPressed = (flags & 0x20) != 0;
        this.leftStall = (stallValue & 0x01) != 0;
        this.rightStall = ((stallValue >> 8) & 0x01) != 0;
        this.compass = compass;
        this.sonar = sonar;
        this.analog = analog;
        this.digIn = digIn;
        this.digOut = digOut;
        this.timestamp = timestamp;
    }

    /** Forward velocity, mm/s: the mean of the two wheels. */
    public double velocity() {
        return (leftVel + rightVel) / 2.0;
    }

    /** True when either wheel is stalled. */
    public boolean stalled() {
        return leftStall || rightStall;
    }

    /** Closest sonar return in mm, or {@link Integer#MAX_VALUE} if none have reported. */
    public int closestSonar() {
        int best = Integer.MAX_VALUE;
        for (int r : sonar) {
            if (r >= 0 && r < best) {
                best = r;
            }
        }
        return best;
    }

    @Override public String toString() {
        return String.format(
                "RobotState{x=%.0fmm y=%.0fmm th=%.1f° v=%.0fmm/s batt=%.1fV motors=%s estop=%s sonar=%s}",
                x, y, theta, velocity(), batteryVoltage, motorsEnabled, eStopPressed,
                Arrays.toString(sonar));
    }
}
