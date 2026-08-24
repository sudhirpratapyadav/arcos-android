package com.arcos.transport;

import com.arcos.Arcos;
import com.arcos.ArcosPacket;
import com.arcos.PacketFramer;
import com.arcos.RobotParams;
import com.arcos.Transport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A robot that isn't there.
 *
 * <p>Speaks the ARCOS server side well enough to exercise everything above it: the
 * sync handshake, the OPEN/PULSE watchdog, motion commands, and a SIP stream at
 * 10 Hz carrying integrated odometry, wheel velocities, battery, stall flags and
 * ray-cast sonar. Point {@code ArcosRobot} at one of these and the whole stack —
 * including the UI — runs with no cable and no robot.
 *
 * <p>The model is a differential drive in a square room with one obstacle. It is
 * not a physics engine: there is no slip, no inertia beyond a fixed acceleration
 * ramp, and driving into a wall stops the robot rather than damaging it. What it
 * does reproduce faithfully is the protocol and the timing, which is what tends to
 * be wrong in a client.
 *
 * <pre>
 *   ArcosRobot robot = new ArcosRobot(new SimTransport());
 *   robot.connect();
 * </pre>
 */
public final class SimTransport implements Transport {

    /** Half-width of the simulated room, mm. */
    private static final double ROOM_HALF = 3000;
    /** Distance between the driven wheels on a P3-DX, mm. */
    private static final double WHEELBASE = 330;
    /** Translational acceleration ramp, mm/s/s. */
    private static final double TRANS_ACCEL = 600;
    /** Rotational acceleration ramp, deg/s/s. */
    private static final double ROT_ACCEL = 300;
    /** SIP period, matching real firmware. */
    private static final int SIP_PERIOD_MS = 100;
    /** The firmware stops the motors if the host goes quiet for this long. */
    private static final int WATCHDOG_MS = 2000;
    /** Angles of the eight front transducers, degrees from straight ahead. */
    private static final double[] SONAR_ANGLES = {90, 50, 30, 10, -10, -30, -50, -90};
    /** Sonar can't see past this, mm. */
    private static final int SONAR_MAX = 5000;

    private final RobotParams params;
    private final String name;
    private final String type;
    private final String subtype;

    private final PacketFramer framer = new PacketFramer();
    private final Deque<byte[]> outbox = new ArrayDeque<>();
    private final Object lock = new Object();

    private volatile boolean open;
    private Thread ticker;

    // ---------- simulated robot state ----------
    private double x;                 // mm, odometry frame
    private double y;
    private double thetaDeg;
    private double vel;               // mm/s, actual
    private double rotVel;            // deg/s, actual
    private double velTarget;
    private double rotTarget;
    private boolean motorsEnabled;
    private boolean eStop;
    private boolean sonarEnabled;
    private boolean sessionOpen;
    private double battery = 12.8;
    private long lastHostPacketAt;
    private final int[] sonar = new int[16];

    /** A simulated P3-DX. */
    public SimTransport() {
        this(RobotParams.P3DX, "Simulated", "Pioneer", "p3dx");
    }

    /** A simulated robot reporting the given identity, for testing model detection. */
    public SimTransport(RobotParams params, String name, String type, String subtype) {
        this.params = params;
        this.name = name;
        this.type = type;
        this.subtype = subtype;
        java.util.Arrays.fill(sonar, -1);
    }

    /** Places the simulated robot, in mm and degrees. Call before connecting. */
    public SimTransport at(double x, double y, double thetaDeg) {
        this.x = x;
        this.y = y;
        this.thetaDeg = thetaDeg;
        return this;
    }

    // ---------- Transport ----------

    @Override public void open() {
        synchronized (lock) {
            if (open) {
                return;
            }
            open = true;
            outbox.clear();
            framer.reset();
            lastHostPacketAt = System.currentTimeMillis();
        }
        ticker = new Thread(this::tick, "arcos-sim");
        ticker.setDaemon(true);
        ticker.start();
    }

    @Override public int read(byte[] dst, int off, int len, int timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 0);
        synchronized (lock) {
            while (true) {
                if (!open) {
                    throw new IOException("simulator closed");
                }
                if (!outbox.isEmpty()) {
                    int written = 0;
                    while (!outbox.isEmpty() && written < len) {
                        byte[] head = outbox.peekFirst();
                        int n = Math.min(head.length, len - written);
                        System.arraycopy(head, 0, dst, off + written, n);
                        written += n;
                        if (n == head.length) {
                            outbox.removeFirst();
                        } else {
                            byte[] rest = new byte[head.length - n];
                            System.arraycopy(head, n, rest, 0, rest.length);
                            outbox.removeFirst();
                            outbox.addFirst(rest);
                        }
                    }
                    return written;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return 0;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
        }
    }

    @Override public void write(byte[] data) throws IOException {
        if (!open) {
            throw new IOException("simulator closed");
        }
        framer.append(data, 0, data.length);
        ArcosPacket p;
        while ((p = framer.next()) != null) {
            // The ticker thread reads the same fields, so command handling and the
            // model step take turns rather than interleaving.
            synchronized (lock) {
                handle(p);
            }
        }
    }

    @Override public boolean isOpen() {
        return open;
    }

    @Override public String name() {
        return "simulator (" + subtype + ")";
    }

    @Override public void close() {
        synchronized (lock) {
            open = false;
            lock.notifyAll();
        }
    }

    @Override public void flushInput() {
        synchronized (lock) {
            outbox.clear();
        }
    }

    // ---------- the server side of ARCOS ----------

    private void handle(ArcosPacket p) {
        lastHostPacketAt = System.currentTimeMillis();
        int id = p.id();

        // Every command but the bare sync/pulse carries a type tag then a value.
        int arg = 0;
        boolean hasArg = false;
        if (p.remaining() >= 3) {
            int tag = p.u8();
            if (tag == Arcos.ARG_INT) {
                arg = p.u16();
                hasArg = true;
            } else if (tag == Arcos.ARG_NINT) {
                arg = -p.u16();
                hasArg = true;
            }
        }

        switch (id) {
            case Arcos.SYNC_0:
                // Also PULSE. Before the session opens it is a handshake step and
                // must be echoed; afterwards it is just the watchdog being fed.
                if (!sessionOpen) {
                    emit(ArcosPacket.command(Arcos.SYNC_0));
                }
                break;

            case Arcos.SYNC_1:                       // also OPEN, once synced
                if (sessionOpen || hasArg) {
                    sessionOpen = true;              // OPEN carries an argument
                } else {
                    emit(ArcosPacket.command(Arcos.SYNC_1));
                }
                break;

            case Arcos.SYNC_2:                       // also CLOSE, once synced
                if (hasArg) {
                    sessionOpen = false;
                    setTargets(0, 0);
                } else {
                    ArcosPacket reply = ArcosPacket.build(Arcos.SYNC_2);
                    reply.putStr(name);
                    reply.putStr(type);
                    reply.putStr(subtype);
                    emit(reply);
                }
                break;

            case Arcos.ENABLE:
                motorsEnabled = arg != 0;
                if (!motorsEnabled) {
                    setTargets(0, 0);
                }
                break;

            case Arcos.VEL:
                setTargets(arg, rotTarget);
                break;

            case Arcos.RVEL:
                setTargets(velTarget, arg);
                break;

            case Arcos.VEL2: {
                // High byte right wheel, low byte left, each scaled by the divisor.
                int right = (byte) ((arg >> 8) & 0xFF);
                int left = (byte) (arg & 0xFF);
                double rv = right * params.vel2Divisor;
                double lv = left * params.vel2Divisor;
                setTargets((lv + rv) / 2.0,
                        Math.toDegrees((rv - lv) / WHEELBASE));
                break;
            }

            case Arcos.STOP:
                setTargets(0, 0);
                break;

            case Arcos.ESTOP:
                eStop = true;
                vel = 0;
                rotVel = 0;
                setTargets(0, 0);
                motorsEnabled = false;
                break;

            case Arcos.SETO:
                x = 0;
                y = 0;
                thetaDeg = 0;
                break;

            case Arcos.SONAR:
                sonarEnabled = arg != 0;
                if (!sonarEnabled) {
                    java.util.Arrays.fill(sonar, -1);
                }
                break;

            case Arcos.MOVE:
            case Arcos.HEAD:
            case Arcos.DHEAD:
            case Arcos.SETV:
            case Arcos.SETRV:
            case Arcos.SETA:
            case Arcos.SETRA:
            case Arcos.HOSTBAUD:
            case Arcos.SAY:
            default:
                // Accepted and ignored: the closed-loop motion commands and the
                // configuration setters do not change what this model reports.
                break;
        }
    }

    private void setTargets(double mmPerSec, double degPerSec) {
        velTarget = mmPerSec;
        rotTarget = degPerSec;
    }

    private void emit(ArcosPacket p) {
        synchronized (lock) {
            outbox.addLast(p.frame());
            lock.notifyAll();
        }
    }

    // ---------- the model ----------

    private void tick() {
        long next = System.currentTimeMillis();
        while (open) {
            next += SIP_PERIOD_MS;
            synchronized (lock) {
                step(SIP_PERIOD_MS / 1000.0);
                if (sessionOpen) {
                    emit(buildSip());
                }
            }
            long sleep = next - System.currentTimeMillis();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                next = System.currentTimeMillis();
            }
        }
    }

    private void step(double dt) {
        // The real firmware cuts the motors when the host stops talking.
        if (System.currentTimeMillis() - lastHostPacketAt > WATCHDOG_MS) {
            setTargets(0, 0);
        }
        boolean driveable = motorsEnabled && !eStop;
        double vTarget = driveable ? velTarget : 0;
        double wTarget = driveable ? rotTarget : 0;

        vel = ramp(vel, vTarget, TRANS_ACCEL * dt);
        rotVel = ramp(rotVel, wTarget, ROT_ACCEL * dt);

        double thetaRad = Math.toRadians(thetaDeg);
        double nx = x + vel * dt * Math.cos(thetaRad);
        double ny = y + vel * dt * Math.sin(thetaRad);

        // Walls are solid: the robot stops rather than passing through.
        if (Math.abs(nx) < ROOM_HALF - params.robotRadius
                && Math.abs(ny) < ROOM_HALF - params.robotRadius) {
            x = nx;
            y = ny;
        } else {
            vel = 0;
        }
        thetaDeg = wrap180(thetaDeg + rotVel * dt);

        // A 12 V pack sags under load; enough to make a battery readout move.
        battery = Math.max(10.5, battery - (0.00002 + Math.abs(vel) * 0.0000002));

        if (sonarEnabled) {
            for (int i = 0; i < SONAR_ANGLES.length; i++) {
                sonar[i] = castRay(Math.toRadians(thetaDeg + SONAR_ANGLES[i]));
            }
        }
    }

    private static double ramp(double current, double target, double maxStep) {
        double delta = target - current;
        if (Math.abs(delta) <= maxStep) {
            return target;
        }
        return current + Math.signum(delta) * maxStep;
    }

    private static double wrap180(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d <= -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** Distance to the nearest surface along a ray, mm, or {@link #SONAR_MAX}. */
    private int castRay(double angleRad) {
        double dx = Math.cos(angleRad);
        double dy = Math.sin(angleRad);
        double best = SONAR_MAX;
        best = Math.min(best, distanceToBox(dx, dy, -ROOM_HALF, -ROOM_HALF, ROOM_HALF, ROOM_HALF, true));
        // One pillar to make the readings interesting.
        best = Math.min(best, distanceToBox(dx, dy, 800, 800, 1400, 1400, false));
        return (int) Math.round(Math.max(0, Math.min(best, SONAR_MAX)));
    }

    /**
     * Ray/box intersection. {@code fromInside} casts to the inner faces, which is
     * what the room walls need; otherwise it is an ordinary slab test against an
     * obstacle the robot is outside of.
     */
    private double distanceToBox(double dx, double dy,
                                 double minX, double minY, double maxX, double maxY,
                                 boolean fromInside) {
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;

        double[][] axes = {{dx, x, minX, maxX}, {dy, y, minY, maxY}};
        for (double[] a : axes) {
            double d = a[0];
            double o = a[1];
            double lo = a[2];
            double hi = a[3];
            if (Math.abs(d) < 1e-9) {
                if (o < lo || o > hi) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            double t1 = (lo - o) / d;
            double t2 = (hi - o) / d;
            if (t1 > t2) {
                double t = t1;
                t1 = t2;
                t2 = t;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
        }
        if (fromInside) {
            return tMax >= 0 ? tMax : Double.POSITIVE_INFINITY;
        }
        if (tMax < tMin || tMax < 0) {
            return Double.POSITIVE_INFINITY;
        }
        return tMin >= 0 ? tMin : Double.POSITIVE_INFINITY;
    }

    /** Builds a SIP in exactly the layout the real firmware uses. */
    private ArcosPacket buildSip() {
        ArcosPacket p = ArcosPacket.build(Arcos.SIP_STANDARD);
        p.putU16((int) Math.round(x / params.distConvFactor) & 0x7FFF);
        p.putU16((int) Math.round(y / params.distConvFactor) & 0x7FFF);
        p.putI16((int) Math.round(Math.toRadians(thetaDeg) / params.angleConvFactor));

        double halfTurn = Math.toRadians(rotVel) * WHEELBASE / 2.0;
        p.putI16((int) Math.round((vel - halfTurn) / params.velConvFactor));
        p.putI16((int) Math.round((vel + halfTurn) / params.velConvFactor));

        p.putU8((int) Math.round(battery * 10));
        p.putU16(0);                                    // stall and bumpers: never
        p.putI16((int) Math.round(Math.toRadians(thetaDeg) / params.angleConvFactor));

        int flags = 0;
        if (motorsEnabled) {
            flags |= 0x01;
        }
        if (sonarEnabled) {
            flags |= 0x02;
        }
        if (eStop) {
            flags |= 0x20;
        }
        p.putU16(flags);
        p.putU8(0);                                     // no compass fitted

        // Real firmware reports only the transducers that fired this cycle; sending
        // a couple at a time is what a client has to cope with.
        int count = sonarEnabled ? 4 : 0;
        p.putU8(count);
        for (int i = 0; i < count; i++) {
            int which = (sipCounter * 4 + i) % SONAR_ANGLES.length;
            p.putU8(which);
            p.putU16(Math.max(0, sonar[which]));
        }
        sipCounter++;
        return p;
    }

    private int sipCounter;

    /** Current simulated pose, for tests and for drawing the robot on a map. */
    public double[] pose() {
        synchronized (lock) {
            return new double[] {x, y, thetaDeg};
        }
    }

    /** Releases the emergency stop, as letting the button back up would. */
    public void releaseEStop() {
        synchronized (lock) {
            eStop = false;
        }
    }
}
