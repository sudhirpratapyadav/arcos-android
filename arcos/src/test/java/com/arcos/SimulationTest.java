package com.arcos;

import com.arcos.transport.SimTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the whole stack against {@link SimTransport}: handshake, session open,
 * SIP stream, motion commands and both watchdogs. Runs on a desktop JVM in a few
 * seconds, with no robot and no phone.
 */
public final class SimulationTest {

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        testConnectAndDrive();
        testCommandTimeout();
        testEStop();
        testUnknownSubtype();

        System.out.println();
        if (failures == 0) {
            System.out.println("PASS - " + checks + " checks");
        } else {
            System.out.println("FAIL - " + failures + " of " + checks + " checks failed");
            System.exit(1);
        }
    }

    // ---------- connect, then actually move ----------

    private static void testConnectAndDrive() throws Exception {
        System.out.println("== connect and drive");

        SimTransport sim = new SimTransport();
        ArcosRobot robot = new ArcosRobot(sim);
        robot.setCommandTimeout(0);          // exercised separately below

        AtomicReference<RobotInfo> connected = new AtomicReference<>();
        AtomicInteger states = new AtomicInteger();
        List<String> log = new ArrayList<>();
        robot.addListener(new ArcosListener() {
            @Override public void onConnected(RobotInfo i) { connected.set(i); }
            @Override public void onState(RobotState s) { states.incrementAndGet(); }
            @Override public void onLog(String m) { synchronized (log) { log.add(m); } }
            @Override public void onError(Throwable t) {
                synchronized (log) { log.add("ERROR " + t); }
            }
        });

        check("handshake completes", "true", String.valueOf(robot.connectBlocking(5000)));
        RobotInfo info = connected.get();
        check("reports a subtype", "p3dx", info == null ? "(none)" : info.subtype);
        check("params recognised", "true", info == null ? "false" : String.valueOf(info.paramsRecognised));

        // SIPs should be arriving at about 10 Hz.
        Thread.sleep(600);
        check("SIP stream running", "true", String.valueOf(states.get() >= 3));

        RobotState idle = robot.lastState();
        check("motors start disabled", "false",
                idle == null ? "(no state)" : String.valueOf(idle.motorsEnabled));

        // Velocity commands must do nothing until the motors are enabled.
        robot.drive(300, 0);
        Thread.sleep(500);
        double movedWhileDisabled = Math.abs(robot.lastState().x);
        check("no motion while disabled", "true", String.valueOf(movedWhileDisabled < 1.0));

        robot.enableMotors(true);
        Thread.sleep(300);
        check("motors report enabled", "true",
                String.valueOf(robot.lastState().motorsEnabled));

        robot.drive(300, 0);
        Thread.sleep(1500);
        RobotState moving = robot.lastState();
        check("drove forward", "true", String.valueOf(moving.x > 200));
        check("reports forward velocity", "true", String.valueOf(moving.velocity() > 200));
        check("stayed on the X axis", "true", String.valueOf(Math.abs(moving.y) < 5));

        // Turning in place should change heading without much translation.
        double xBefore = moving.x;
        robot.drive(0, 45);
        Thread.sleep(1500);
        RobotState turned = robot.lastState();
        check("heading changed", "true", String.valueOf(Math.abs(turned.theta) > 20));
        check("barely translated while turning", "true",
                String.valueOf(Math.abs(turned.x - xBefore) < 120));

        // Sonar is switched on at connect, so readings should have arrived.
        check("sonar reporting", "true",
                String.valueOf(robot.lastState().closestSonar() < 6000));

        robot.drive(0, 0);
        Thread.sleep(400);
        check("stops on zero command", "true",
                String.valueOf(Math.abs(robot.lastState().velocity()) < 20));

        // Odometry reset should zero the pose.
        robot.resetOdometry();
        Thread.sleep(400);
        RobotState reset = robot.lastState();
        check("odometry reset", "true",
                String.valueOf(Math.abs(reset.x) < 5 && Math.abs(reset.y) < 5));

        robot.disconnect();
        Thread.sleep(200);
        check("disconnects cleanly", "DISCONNECTED", robot.state().toString());

        synchronized (log) {
            for (String m : log) {
                if (m.startsWith("ERROR")) {
                    fail("no errors during session", m);
                }
            }
        }
    }

    // ---------- the software watchdog ----------

    private static void testCommandTimeout() throws Exception {
        System.out.println();
        System.out.println("== command timeout");

        SimTransport sim = new SimTransport();
        ArcosRobot robot = new ArcosRobot(sim);
        robot.setCommandTimeout(500);        // shorter than the 2 s default, to keep the test quick
        check("connects", "true", String.valueOf(robot.connectBlocking(5000)));

        robot.enableMotors(true);
        Thread.sleep(200);
        robot.drive(400, 0);
        Thread.sleep(600);
        check("moving before timeout", "true",
                String.valueOf(robot.lastState().velocity() > 100));

        // Stop talking. The setpoints should zero themselves without the app doing
        // anything, which is the whole point of the timeout.
        Thread.sleep(1500);
        check("stopped after timeout", "true",
                String.valueOf(Math.abs(robot.lastState().velocity()) < 20));

        robot.disconnect();
    }

    // ---------- emergency stop ----------

    private static void testEStop() throws Exception {
        System.out.println();
        System.out.println("== emergency stop");

        SimTransport sim = new SimTransport();
        ArcosRobot robot = new ArcosRobot(sim);
        robot.setCommandTimeout(0);
        robot.connectBlocking(5000);
        robot.enableMotors(true);
        Thread.sleep(200);
        robot.drive(500, 0);
        Thread.sleep(800);
        check("moving before estop", "true",
                String.valueOf(robot.lastState().velocity() > 100));

        robot.eStop();
        Thread.sleep(500);
        RobotState s = robot.lastState();
        check("estop reported", "true", String.valueOf(s.eStopPressed));
        check("halted", "true", String.valueOf(Math.abs(s.velocity()) < 20));
        check("motors disabled by estop", "false", String.valueOf(s.motorsEnabled));

        // A commanded velocity must not restart it while the button is latched.
        robot.drive(500, 0);
        Thread.sleep(600);
        check("stays halted under estop", "true",
                String.valueOf(Math.abs(robot.lastState().velocity()) < 20));

        robot.disconnect();
    }

    // ---------- an unfamiliar robot ----------

    private static void testUnknownSubtype() throws Exception {
        System.out.println();
        System.out.println("== unknown subtype");

        SimTransport sim = new SimTransport(RobotParams.P3DX, "Rover", "Pioneer", "p9zz");
        ArcosRobot robot = new ArcosRobot(sim);
        AtomicReference<RobotInfo> info = new AtomicReference<>();
        robot.addListener(new ArcosListener() {
            @Override public void onConnected(RobotInfo i) { info.set(i); }
        });
        check("still connects", "true", String.valueOf(robot.connectBlocking(5000)));
        RobotInfo i = info.get();
        check("subtype passed through", "p9zz", i == null ? "(none)" : i.subtype);
        check("flagged as unrecognised", "false",
                i == null ? "true" : String.valueOf(i.paramsRecognised));
        check("falls back to p3dx factors", "p3dx", robot.params().subtype);
        robot.disconnect();
    }

    // ---------- harness ----------

    private static void check(String label, String expected, String actual) {
        checks++;
        if (expected.equals(actual)) {
            System.out.println("  ok    " + label);
        } else {
            failures++;
            System.out.println("  FAIL  " + label);
            System.out.println("          expected: " + expected);
            System.out.println("          actual:   " + actual);
        }
    }

    private static void fail(String label, String why) {
        checks++;
        failures++;
        System.out.println("  FAIL  " + label + " - " + why);
    }
}
