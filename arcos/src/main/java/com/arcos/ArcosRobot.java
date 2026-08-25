package com.arcos;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A live session with an ARCOS robot: handshake, watchdog, odometry, and the
 * motion commands you actually want to send.
 *
 * <p>Typical use:
 *
 * <pre>
 *   ArcosRobot robot = new ArcosRobot(new UsbSerialTransport(context));
 *   robot.addListener(new ArcosListener() {
 *       &#64;Override public void onConnected(RobotInfo i) { ... }
 *       &#64;Override public void onState(RobotState s) { ... }
 *   });
 *   robot.connect();
 *   robot.enableMotors(true);
 *   robot.drive(300, 0);      // 300 mm/s forward
 * </pre>
 *
 * <p>All the control methods are safe to call from any thread and return
 * immediately: they record a setpoint that a background thread transmits on the
 * next cycle. Nothing here blocks on the robot.
 *
 * <h2>The watchdog, and why there are two of them</h2>
 *
 * <p>ARCOS stops the motors if it hears nothing for two seconds. This class sends
 * a PULSE every cycle, which keeps the robot happy but also means a frozen or
 * backgrounded app would leave the robot driving at its last setpoint. Android
 * freezes background apps routinely, so a software equivalent is enabled by
 * default: if no control method is called for {@link #setCommandTimeout} ms
 * (2000 by default), the setpoints are zeroed. Pass 0 to turn that off and get
 * ARIA's behaviour, where a setpoint holds until changed.
 */
public final class ArcosRobot {

    /** Where the session is in its lifecycle. */
    public enum State {
        /** No thread running, transport closed. */
        DISCONNECTED,
        /** Handshake in progress. */
        CONNECTING,
        /** Session open; SIPs are arriving. */
        CONNECTED
    }

    /** Bauds to probe when the robot does not answer at the transport's current rate. */
    private static final int[] PROBE_BAUDS = {9600, 38400, 115200, 19200, 57600};

    /** Handshake reply wait, per attempt. ARIA uses the same figure. */
    private static final int SYNC_TIMEOUT_MS = 1000;
    /** Attempts per handshake step before giving up on this baud. */
    private static final int SYNC_ATTEMPTS = 3;
    /** Settling time after changing an adapter's line rate. */
    private static final int BAUD_SETTLE_MS = 120;
    /** How long to listen for unsolicited traffic when probing a rate. */
    private static final int LISTEN_MS = 250;
    /** Re-send unchanged setpoints this often, so one dropped frame is not forever. */
    private static final int RESEND_EVERY_CYCLES = 5;

    private final Transport transport;
    private final List<ArcosListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    // ---------- configuration ----------
    private volatile int cycleMs = 100;
    private volatile int commandTimeoutMs = 2000;
    private volatile boolean autoSwitchBaud = true;
    private volatile boolean probeBauds = true;
    private volatile boolean sonarOnConnect = true;
    private volatile boolean autoDetectParams = true;
    private volatile RobotParams params;

    // ---------- session ----------
    private volatile State state = State.DISCONNECTED;
    private volatile Thread thread;
    private volatile boolean running;
    private volatile RobotInfo info;
    private volatile RobotState lastState;

    // ---------- setpoints, guarded by lock ----------
    private double transVel;
    private double rotVel;
    private double leftVel;
    private double rightVel;
    private boolean useWheelVelocities;
    private boolean setpointsDirty;
    private long lastCommandAt;
    private boolean timedOutLogged;
    private final Deque<ArcosPacket> outbox = new ArrayDeque<>();

    // ---------- odometry accumulation ----------
    /**
     * The robot reports x and y as a 15-bit counter that wraps, not as an absolute
     * position, so they have to be accumulated as deltas. Reading them directly is
     * wrong the moment the robot reverses past its origin: x of -1 arrives as
     * 0x7FFF and reads as +15.9 m. Observed on real hardware, not theorised.
     */
    private int lastRawX;
    private int lastRawY;
    private boolean poseOriginPending = true;
    private double poseX;
    private double poseY;
    /**
     * SETO has been sent but the robot's counter has not zeroed yet. The command
     * takes a cycle or two to land, and the jump to zero looks exactly like a large
     * backwards delta, so the pose is held at the origin until the robot confirms.
     */
    private volatile boolean resetPending;
    private volatile long resetDeadline;

    // ---------- frame reader ----------
    private final byte[] rxBuf = new byte[4096];
    private final PacketFramer framer = new PacketFramer();
    private int lastDropped;

    /** A session over {@code transport}, assuming P3-DX conversion factors. */
    public ArcosRobot(Transport transport) {
        this(transport, RobotParams.P3DX);
    }

    /**
     * A session over {@code transport} with explicit conversion factors. The factors
     * are replaced by the ones matching the robot's reported subtype unless
     * {@link #setAutoDetectParams} is turned off.
     */
    public ArcosRobot(Transport transport, RobotParams params) {
        if (transport == null) {
            throw new IllegalArgumentException("transport is null");
        }
        this.transport = transport;
        this.params = params;
    }

    // ---------- listeners ----------

    public void addListener(ArcosListener l) {
        listeners.add(l);
    }

    public void removeListener(ArcosListener l) {
        listeners.remove(l);
    }

    // ---------- configuration ----------

    /** Cycle period in ms. 100 matches ARIA and the robot's own SIP rate. */
    public ArcosRobot setCycleTime(int ms) {
        this.cycleMs = Math.max(20, ms);
        return this;
    }

    /**
     * Zero the setpoints if no control method is called for this long. 0 disables
     * it. See the class notes on why this defaults to on.
     */
    public ArcosRobot setCommandTimeout(int ms) {
        this.commandTimeoutMs = Math.max(0, ms);
        return this;
    }

    /**
     * Whether to raise the link to the model's preferred baud after connecting
     * (38400 for a P3-DX). Worth leaving on: at 9600 a SIP carrying sonar nearly
     * saturates the line.
     */
    public ArcosRobot setAutoSwitchBaud(boolean enabled) {
        this.autoSwitchBaud = enabled;
        return this;
    }

    /**
     * Whether to retry the handshake at other bauds when the robot stays silent.
     * Usually the reason a P3-DX ignores you is that it was left at 38400.
     */
    public ArcosRobot setProbeBauds(boolean enabled) {
        this.probeBauds = enabled;
        return this;
    }

    /** Whether to switch the sonar array on once connected. */
    public ArcosRobot setSonarOnConnect(boolean enabled) {
        this.sonarOnConnect = enabled;
        return this;
    }

    /** Whether to adopt conversion factors matching the reported subtype. */
    public ArcosRobot setAutoDetectParams(boolean enabled) {
        this.autoDetectParams = enabled;
        return this;
    }

    // ---------- lifecycle ----------

    /**
     * Opens the transport and starts the session on a background thread. Returns
     * immediately; watch {@link ArcosListener#onConnected} for the result.
     */
    public synchronized void connect() {
        if (running) {
            return;
        }
        running = true;
        poseOriginPending = true;
        state = State.CONNECTING;
        thread = new Thread(this::run, "arcos-cycle");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Connects and waits for the handshake to finish.
     *
     * @return true if the session opened within {@code timeoutMs}
     */
    public boolean connectBlocking(int timeoutMs) throws InterruptedException {
        connect();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            State s = state;
            if (s == State.CONNECTED) {
                return true;
            }
            if (s == State.DISCONNECTED) {
                return false;
            }
            Thread.sleep(20);
        }
        return state == State.CONNECTED;
    }

    /**
     * Stops the robot, closes the session and waits briefly for the thread to end.
     * Safe to call when already disconnected.
     */
    public void disconnect() {
        Thread t;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            t = thread;
        }
        // Unblock a read that is sitting in its timeout.
        transport.close();
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public State state() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    /** What the robot reported on connect, or null before the handshake finishes. */
    public RobotInfo info() {
        return info;
    }

    /** The most recent SIP, or null if none has arrived yet. */
    public RobotState lastState() {
        return lastState;
    }

    /** Conversion factors currently in use. */
    public RobotParams params() {
        return params;
    }

    /** The transport this session runs over. */
    public Transport transport() {
        return transport;
    }

    // ---------- control ----------

    /**
     * The joystick call: forward speed in mm/s and turn rate in deg/s, applied
     * together. Positive rotation is counter-clockwise, as ARCOS reports it.
     * Both are clamped to the model's limits.
     */
    public void drive(double mmPerSec, double degPerSec) {
        synchronized (lock) {
            transVel = clamp(mmPerSec, params.maxVelocity);
            rotVel = clamp(degPerSec, params.maxRotVelocity);
            useWheelVelocities = false;
            markCommanded();
        }
    }

    /** Forward speed in mm/s, leaving the turn rate alone. */
    public void setVelocity(double mmPerSec) {
        synchronized (lock) {
            transVel = clamp(mmPerSec, params.maxVelocity);
            useWheelVelocities = false;
            markCommanded();
        }
    }

    /** Turn rate in deg/s, leaving the forward speed alone. */
    public void setRotVelocity(double degPerSec) {
        synchronized (lock) {
            rotVel = clamp(degPerSec, params.maxRotVelocity);
            useWheelVelocities = false;
            markCommanded();
        }
    }

    /**
     * Independent wheel speeds in mm/s, sent as VEL2. Resolution is coarse: each
     * wheel travels as a signed byte scaled by the model's Vel2 divisor, so on a
     * P3-DX the step is 20 mm/s and the range is about &plusmn;2540 mm/s.
     */
    public void setWheelVelocities(double leftMmPerSec, double rightMmPerSec) {
        synchronized (lock) {
            leftVel = clamp(leftMmPerSec, params.maxVelocity);
            rightVel = clamp(rightMmPerSec, params.maxVelocity);
            useWheelVelocities = true;
            markCommanded();
        }
    }

    /** Decelerates to a stop under the configured deceleration. */
    public void stop() {
        synchronized (lock) {
            transVel = 0;
            rotVel = 0;
            leftVel = 0;
            rightVel = 0;
            useWheelVelocities = false;
            setpointsDirty = true;
            markCommanded();
            outbox.add(ArcosPacket.commandInt(Arcos.STOP, 1));
        }
    }

    /**
     * Emergency stop: ignores the deceleration limit and halts as hard as the
     * hardware allows. Also zeroes the setpoints so nothing resumes on the next
     * cycle.
     */
    public void eStop() {
        synchronized (lock) {
            transVel = 0;
            rotVel = 0;
            leftVel = 0;
            rightVel = 0;
            useWheelVelocities = false;
            setpointsDirty = true;
            markCommanded();
            outbox.addFirst(ArcosPacket.command(Arcos.ESTOP));
        }
    }

    /**
     * Enables or disables the motors. They come up disabled, and the robot also
     * disables them whenever the red button is pressed — so after an emergency stop
     * this must be called again.
     */
    public void enableMotors(boolean enabled) {
        send(ArcosPacket.commandInt(Arcos.ENABLE, enabled ? 1 : 0));
    }

    /** Switches the sonar array on or off. */
    public void setSonarEnabled(boolean enabled) {
        send(ArcosPacket.commandInt(Arcos.SONAR, enabled ? 1 : 0));
    }

    /**
     * Resets the odometry origin, so the pose returns to (0, 0, 0). Zeroes the
     * robot's own counter and re-origins the local accumulator, which would
     * otherwise report one large bogus delta as the counter jumps to zero.
     */
    public void resetOdometry() {
        resetDeadline = System.currentTimeMillis() + 1000;
        resetPending = true;
        poseOriginPending = true;
        send(ArcosPacket.commandInt(Arcos.SETO, 0));
    }

    /** Drives a fixed distance in mm, using the robot's own closed loop. */
    public void move(int mm) {
        send(ArcosPacket.commandInt(Arcos.MOVE, mm));
    }

    /** Turns to an absolute odometry heading, in degrees. */
    public void turnTo(int degrees) {
        send(ArcosPacket.commandInt(Arcos.HEAD, degrees));
    }

    /** Turns by an angle relative to the current heading, in degrees. */
    public void turnBy(int degrees) {
        send(ArcosPacket.commandInt(Arcos.DHEAD, degrees));
    }

    /** Maximum translational speed the robot will obey, mm/s. */
    public void setMaxVelocity(int mmPerSec) {
        send(ArcosPacket.commandInt(Arcos.SETV, mmPerSec));
    }

    /** Maximum rotational speed the robot will obey, deg/s. */
    public void setMaxRotVelocity(int degPerSec) {
        send(ArcosPacket.commandInt(Arcos.SETRV, degPerSec));
    }

    /** Translational acceleration when positive, deceleration when negative, mm/s/s. */
    public void setAcceleration(int mmPerSecPerSec) {
        send(ArcosPacket.commandInt(Arcos.SETA, mmPerSecPerSec));
    }

    /** Rotational acceleration when positive, deceleration when negative, deg/s/s. */
    public void setRotAcceleration(int degPerSecPerSec) {
        send(ArcosPacket.commandInt(Arcos.SETRA, degPerSecPerSec));
    }

    /**
     * Beeps. Each pair is a duration in 20 ms units and a tone, up to 20 pairs —
     * the argument is passed through to SAY unchanged.
     */
    public void say(byte[] durationTonePairs) {
        send(ArcosPacket.commandBytes(Arcos.SAY, durationTonePairs));
    }

    /** A short beep, for acknowledging a connection. */
    public void beep() {
        say(new byte[] {(byte) 5, (byte) 20});
    }

    /** Escape hatch: queue any ARCOS command with no argument. */
    public void sendCommand(int id) {
        send(ArcosPacket.command(id));
    }

    /** Escape hatch: queue any ARCOS command with one integer argument. */
    public void sendCommand(int id, int value) {
        send(ArcosPacket.commandInt(id, value));
    }

    private void send(ArcosPacket p) {
        synchronized (lock) {
            outbox.add(p);
        }
    }

    /** Must hold {@link #lock}. */
    private void markCommanded() {
        setpointsDirty = true;
        lastCommandAt = System.currentTimeMillis();
        timedOutLogged = false;
    }

    private static double clamp(double v, double limit) {
        if (v > limit) {
            return limit;
        }
        if (v < -limit) {
            return -limit;
        }
        return v;
    }

    // ---------- the cycle thread ----------

    private void run() {
        String reason = "disconnected";
        try {
            transport.open();
            log("transport open: " + transport.name());
            handshake();
            afterConnect();
            state = State.CONNECTED;
            fire(l -> l.onConnected(info));
            cycle();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reason = "interrupted";
        } catch (Throwable t) {
            reason = t.getMessage() == null ? t.toString() : t.getMessage();
            if (running) {
                fire(l -> l.onError(t));
            }
        } finally {
            running = false;
            state = State.DISCONNECTED;
            try {
                // Best effort: tell the robot we are going, so it stops rather than
                // coasting until its own watchdog fires.
                if (transport.isOpen()) {
                    transport.write(ArcosPacket.commandInt(Arcos.STOP, 1).frame());
                    transport.write(ArcosPacket.commandInt(Arcos.CLOSE, 1).frame());
                }
            } catch (IOException ignored) {
                // The link is already gone; nothing useful to do.
            }
            transport.close();
            final String r = reason;
            fire(l -> l.onDisconnected(r));
        }
    }

    /**
     * The SYNC0 / SYNC1 / SYNC2 exchange. Each step echoes its own command number
     * back; the SYNC2 reply carries the robot's name, type and subtype.
     */
    private void handshake() throws IOException, InterruptedException {
        int[] bauds = {transport.baudRate()};
        if (probeBauds && transport.supportsBaudRate()) {
            bauds = PROBE_BAUDS;
        }

        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt == 1) {
                // Nothing answered anywhere. Before giving up, assume the
                // controller is wedged part-way through a previous handshake and
                // shake it loose — see clearStaleSession().
                log("no response at any baud; clearing a possible stale session");
                clearStaleSession(bauds);
            }
            last = trySyncAcrossBauds(bauds, last);
            if (last == null) {
                return;
            }
        }
        throw new ArcosException(
                "robot did not respond to the sync handshake"
                        + (last == null ? "" : " (" + last.getMessage() + ")"),
                last);
    }

    /**
     * Runs the handshake at each candidate baud. Returns null on success, or the
     * last failure to report.
     */
    private IOException trySyncAcrossBauds(int[] bauds, IOException last)
            throws IOException, InterruptedException {
        for (int baud : bauds) {
            if (!running) {
                throw new ArcosException("cancelled");
            }
            if (baud > 0 && transport.supportsBaudRate() && transport.baudRate() != baud) {
                transport.setBaudRate(baud);
                log("trying " + baud + " baud");
            }
            try {
                transport.flushInput();
                drainReader();
                ArcosPacket sync2 = trySync();
                readIdentity(sync2);
                return null;
            } catch (ArcosException e) {
                last = e;
                log("no reply at " + (baud > 0 ? baud + " baud" : "current rate"));
            }
        }
        return last;
    }

    /**
     * Broadcasts CLOSE at every candidate baud.
     *
     * <p>ARCOS has a small state machine: once it has answered SYNC2 it waits for
     * OPEN and stops replying to further sync attempts. A client that exits
     * mid-handshake — a crash, or just closing the port — leaves the controller
     * stuck there, silent at every rate, and no amount of retrying the handshake
     * recovers it. CLOSE is the only way back, and since the wedged rate is
     * unknown it has to go to all of them.
     */
    private void clearStaleSession(int[] bauds) throws InterruptedException {
        byte[] close = ArcosPacket.commandInt(Arcos.CLOSE, 1).frame();
        int heardAt = 0;
        for (int baud : bauds) {
            if (!running) {
                return;
            }
            try {
                if (baud > 0 && transport.supportsBaudRate()) {
                    transport.setBaudRate(baud);
                    transport.flushInput();
                    // Adapters need a moment for a new rate to take effect. Writing
                    // immediately sends the frame at the old rate, so the robot
                    // never sees a valid CLOSE and the sweep quietly does nothing.
                    Thread.sleep(BAUD_SETTLE_MS);
                }

                // A robot left open streams status packets unprompted. Hearing any
                // is direct evidence of its rate, which beats sweeping blind — and
                // it is the state a stale session leaves behind.
                boolean traffic = hearsTraffic(LISTEN_MS);

                transport.write(close);
                Thread.sleep(150);
                transport.write(close);
                Thread.sleep(150);

                if (traffic) {
                    heardAt = baud;
                    log("robot was streaming at " + baud + " baud; sent CLOSE");
                }
                transport.flushInput();
                drainReader();
            } catch (IOException e) {
                // This baud may not even be supported by the adapter; keep going.
            }
        }
        if (heardAt > 0 && transport.supportsBaudRate()) {
            // Start the retry where the robot actually was, rather than walking
            // the list again from the top.
            try {
                transport.setBaudRate(heardAt);
                Thread.sleep(BAUD_SETTLE_MS);
                transport.flushInput();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
        drainReader();
        Thread.sleep(300);
    }

    /** True if anything arrives within {@code ms}. Used to find a live rate. */
    private boolean hearsTraffic(int ms) throws IOException {
        byte[] sink = new byte[256];
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (transport.read(sink, 0, sink.length, 50) > 0) {
                return true;
            }
        }
        return false;
    }

    /** One full handshake attempt at the current baud. Returns the SYNC2 reply. */
    private ArcosPacket trySync() throws IOException, InterruptedException {
        ArcosPacket reply = null;
        for (int step = Arcos.SYNC_0; step <= Arcos.SYNC_2; step++) {
            reply = null;
            for (int attempt = 0; attempt < SYNC_ATTEMPTS && reply == null; attempt++) {
                if (!running) {
                    throw new ArcosException("cancelled");
                }
                transport.write(ArcosPacket.command(step).frame());
                long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
                ArcosPacket p;
                while ((p = readPacket(deadline)) != null) {
                    if (p.id() == step) {
                        reply = p;
                        break;
                    }
                    if (p.id() == 50) {
                        // A session is already open from a previous run. Close it and
                        // start the handshake over rather than fighting it.
                        log("robot already open; closing previous session");
                        transport.write(ArcosPacket.commandInt(Arcos.CLOSE, 1).frame());
                        Thread.sleep(100);
                        transport.flushInput();
                        drainReader();
                        throw new ArcosException("stale session closed, retrying");
                    }
                }
            }
            if (reply == null) {
                throw new ArcosException("no reply to SYNC" + step);
            }
            log("SYNC" + step + " ok");
        }
        return reply;
    }

    /** Pulls name, type and subtype out of the SYNC2 reply and picks conversion factors. */
    private void readIdentity(ArcosPacket sync2) {
        String name = sync2.str();
        String type = sync2.str();
        String subtype = sync2.str().toLowerCase();
        boolean known = RobotParams.isKnownSubtype(subtype);
        if (autoDetectParams && known) {
            params = RobotParams.forSubtype(subtype);
        }
        info = new RobotInfo(name, type, subtype, params, known);
        log("robot: " + info);
        if (autoDetectParams && !known && !subtype.isEmpty()) {
            log("unknown subtype '" + subtype + "'; odometry uses "
                    + params.subtype + " factors and may be scaled wrong");
        }
    }

    /** OPEN the session, raise the baud, and apply the connect-time options. */
    private void afterConnect() throws IOException, InterruptedException {
        transport.write(ArcosPacket.commandInt(Arcos.OPEN, 1).frame());
        Thread.sleep(cycleMs);

        if (autoSwitchBaud && transport.supportsBaudRate() && params.switchToBaud > 0
                && transport.baudRate() > 0 && transport.baudRate() < params.switchToBaud) {
            int code = Arcos.baudCode(params.switchToBaud);
            if (code >= 0) {
                log("switching link to " + params.switchToBaud + " baud");
                transport.write(ArcosPacket.commandInt(Arcos.HOSTBAUD, code).frame());
                // The robot changes rate as soon as it has sent the last byte, so give
                // it a moment before following, then throw away the garbage that the
                // rate mismatch produced.
                Thread.sleep(100);
                transport.setBaudRate(params.switchToBaud);
                Thread.sleep(100);
                transport.flushInput();
                drainReader();
            }
        }

        if (sonarOnConnect) {
            transport.write(ArcosPacket.commandInt(Arcos.SONAR, 1).frame());
        }
        synchronized (lock) {
            lastCommandAt = System.currentTimeMillis();
        }
    }

    /** The steady-state loop: drain input, feed the watchdog, transmit setpoints. */
    private void cycle() throws IOException, InterruptedException {
        int sinceResend = 0;
        while (running) {
            long start = System.currentTimeMillis();
            long deadline = start + cycleMs;

            ArcosPacket p;
            while ((p = readPacket(deadline)) != null) {
                if (Arcos.isSip(p.id())) {
                    RobotState s = decodeSip(p);
                    lastState = s;
                    fire(l -> l.onState(s));
                }
            }

            transport.write(ArcosPacket.command(Arcos.PULSE).frame());

            applyCommandTimeout();

            boolean resend = ++sinceResend >= RESEND_EVERY_CYCLES;
            ArcosPacket[] toSend;
            synchronized (lock) {
                if (setpointsDirty || resend) {
                    setpointsDirty = false;
                    sinceResend = 0;
                    if (useWheelVelocities) {
                        int left = (int) Math.round(leftVel / params.vel2Divisor);
                        int right = (int) Math.round(rightVel / params.vel2Divisor);
                        outbox.add(ArcosPacket.commandTwoBytes(
                                Arcos.VEL2, clampByte(right), clampByte(left)));
                    } else {
                        outbox.add(ArcosPacket.commandInt(Arcos.VEL, (int) Math.round(transVel)));
                        outbox.add(ArcosPacket.commandInt(Arcos.RVEL, (int) Math.round(rotVel)));
                    }
                }
                toSend = outbox.toArray(new ArcosPacket[0]);
                outbox.clear();
            }
            for (ArcosPacket q : toSend) {
                transport.write(q.frame());
            }

            long sleep = deadline - System.currentTimeMillis();
            if (sleep > 0) {
                Thread.sleep(sleep);
            }
        }
    }

    /** Zeroes the setpoints when the app has gone quiet. See the class notes. */
    private void applyCommandTimeout() {
        int timeout = commandTimeoutMs;
        if (timeout <= 0) {
            return;
        }
        synchronized (lock) {
            boolean moving = transVel != 0 || rotVel != 0 || leftVel != 0 || rightVel != 0;
            if (!moving || System.currentTimeMillis() - lastCommandAt <= timeout) {
                return;
            }
            transVel = 0;
            rotVel = 0;
            leftVel = 0;
            rightVel = 0;
            setpointsDirty = true;
            if (!timedOutLogged) {
                timedOutLogged = true;
                log("no command for " + timeout + " ms; setpoints zeroed");
            }
        }
    }

    private static int clampByte(int v) {
        if (v > 127) {
            return 127;
        }
        if (v < -128) {
            return -128;
        }
        return v & 0xFF;
    }

    // ---------- SIP decoding ----------

    /**
     * Decodes a Server Information Packet. The field order is fixed by the
     * firmware; the trailing IO block is only present on some configurations, so
     * everything past the sonar array is read defensively.
     */
    RobotState decodeSip(ArcosPacket p) {
        // The top bit of each position word is a firmware flag, not part of the value.
        int rawX = p.u16() & 0x7FFF;
        int rawY = p.u16() & 0x7FFF;
        int rawTh = p.i16();

        if (poseOriginPending || resetPending) {
            // Take this packet as the origin. Pose is therefore measured from the
            // moment of connecting, or from the last resetOdometry().
            lastRawX = rawX;
            lastRawY = rawY;
            poseX = 0;
            poseY = 0;
            poseOriginPending = false;
            // Stop re-origining as soon as the robot's own counter reads zero,
            // or once waiting for that has gone on too long.
            if (resetPending && ((rawX == 0 && rawY == 0)
                    || System.currentTimeMillis() > resetDeadline)) {
                resetPending = false;
            }
        } else {
            poseX += unwrap(rawX - lastRawX) * params.distConvFactor;
            poseY += unwrap(rawY - lastRawY) * params.distConvFactor;
            lastRawX = rawX;
            lastRawY = rawY;
        }
        double x = poseX;
        double y = poseY;

        // Heading needs no unwrapping: it is an angle, so the raw counter's wrap
        // at one revolution is exactly the behaviour wanted.
        double theta = normaliseDegrees(Math.toDegrees(rawTh * params.angleConvFactor));

        double left = p.i16() * params.velConvFactor;
        double right = p.i16() * params.velConvFactor;
        double battery = p.u8() * 0.1;
        int stall = p.u16();
        p.i16();                              // control heading setpoint, not exposed
        int flags = p.u16();
        double compass = p.u8() * 2.0;

        int[] sonar = lastSonar();
        int readings = p.u8();
        for (int i = 0; i < readings && p.remaining() >= 3; i++) {
            int which = p.u8();
            int range = (int) Math.round(p.u16() * params.rangeConvFactor);
            if (which >= 0 && which < sonar.length) {
                sonar[which] = range;
            }
        }

        int analog = -1;
        int digIn = -1;
        int digOut = -1;
        if (p.remaining() >= 5) {
            p.u16();                          // selected analog port
            analog = p.u8();
            digIn = p.u8();
            digOut = p.u8();
        }

        return new RobotState(x, y, theta, left, right, battery, flags, stall, compass,
                sonar, analog, digIn, digOut, System.currentTimeMillis());
    }

    /**
     * A sonar array carrying forward the previous readings. Each SIP reports only
     * the transducers that fired since the last one, so starting from scratch would
     * make most of the array read as unknown on every packet.
     */
    private int[] lastSonar() {
        RobotState prev = lastState;
        if (prev == null) {
            int[] fresh = new int[16];
            Arrays.fill(fresh, -1);
            return fresh;
        }
        return Arrays.copyOf(prev.sonar, prev.sonar.length);
    }

    /**
     * Shortest-path delta between two samples of the 15-bit position counter. A
     * jump larger than a quarter of the range is a wrap, not real motion — at the
     * robot's top speed a single cycle covers a few hundred counts, nowhere near
     * this threshold.
     */
    private static int unwrap(int delta) {
        if (delta > 0x1000) {
            return delta - 0x8000;
        }
        if (delta < -0x1000) {
            return delta + 0x8000;
        }
        return delta;
    }

    private static double normaliseDegrees(double deg) {
        double d = deg % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d <= -180.0) {
            d += 360.0;
        }
        return d;
    }

    // ---------- frame reading ----------

    /**
     * Next valid packet, or null once {@code deadline} passes. Framing, checksum
     * checks and resynchronisation all live in {@link PacketFramer}.
     */
    private ArcosPacket readPacket(long deadline) throws IOException {
        while (true) {
            ArcosPacket p = framer.next();
            if (p != null) {
                return p;
            }
            if (framer.lastError() != null && framer.droppedCount() != lastDropped) {
                lastDropped = framer.droppedCount();
                log("dropped frame: " + framer.lastError());
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0 || !running) {
                return null;
            }
            int n = transport.read(rxBuf, 0, rxBuf.length, (int) Math.min(remaining, 50));
            if (n > 0) {
                framer.append(rxBuf, 0, n);
            }
        }
    }

    /** Discards any half-assembled frame and buffered bytes. */
    private void drainReader() {
        framer.reset();
    }

    // ---------- listener plumbing ----------

    private interface Dispatch {
        void to(ArcosListener l);
    }

    private void fire(Dispatch d) {
        for (ArcosListener l : listeners) {
            try {
                d.to(l);
            } catch (Throwable t) {
                // A broken listener must not take the session down with it.
            }
        }
    }

    private void log(String message) {
        fire(l -> l.onLog(message));
    }
}
