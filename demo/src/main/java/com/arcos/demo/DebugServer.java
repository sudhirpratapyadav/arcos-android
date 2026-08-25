package com.arcos.demo;

import com.arcos.ArcosRobot;
import com.arcos.RobotState;
import com.arcos.transport.LoggingTransport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

/**
 * A small HTTP server so the phone can be inspected and driven from a laptop.
 *
 * <p>Once the phone's USB port is holding the robot cable, it is no longer holding
 * an adb cable. Wireless adb covers installing builds, but it drops, and it cannot
 * answer the question that matters when something is wrong: what bytes actually
 * went down the wire. This exposes that over Wi-Fi, where curl can reach it.
 *
 * <pre>
 *   curl phone:8080/api/state       telemetry as JSON
 *   curl phone:8080/api/log         recent library log lines
 *   curl phone:8080/api/raw         hex of the last few hundred serial exchanges
 *   curl phone:8080/api/drive?v=200&amp;w=0
 *   curl phone:8080/api/estop
 * </pre>
 *
 * <p>Everything is a GET, including the commands, so it can all be driven with a
 * plain curl and no request body. That is a deliberate choice for a debug tool on
 * a trusted network; it would be the wrong choice for anything exposed publicly.
 */
public final class DebugServer {

    /** Log lines retained for /api/log. */
    private static final int LOG_CAPACITY = 500;

    /** Supplies the server with whatever the app currently has. */
    public interface Host {
        ArcosRobot robot();
        LoggingTransport tap();
        void connect(String transport);
        void disconnect();
    }

    private final int port;
    private final Host host;
    private final Deque<String> log = new ArrayDeque<>();
    private final long startedAt = System.currentTimeMillis();

    private ServerSocket server;
    private volatile boolean running;

    public DebugServer(int port, Host host) {
        this.port = port;
        this.host = host;
    }

    /** Appends a line to the buffer served by /api/log. */
    public void log(String line) {
        synchronized (log) {
            log.addLast(String.format(Locale.US, "%7d %s",
                    System.currentTimeMillis() - startedAt, line));
            while (log.size() > LOG_CAPACITY) {
                log.removeFirst();
            }
        }
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::run, "arcos-debug-http");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // Shutting down anyway.
        }
    }

    /** Best-guess Wi-Fi address, for showing the user where to point curl. */
    public static String localAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface iface : Collections.list(ifaces)) {
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr.getHostAddress() != null && addr.getHostAddress().indexOf(':') < 0) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to the placeholder.
        }
        return "?";
    }

    private void run() {
        try {
            server = new ServerSocket(port);
            log("debug server on " + localAddress() + ":" + port);
            while (running) {
                Socket s = server.accept();
                // One request per connection, handled inline: this serves one
                // developer with curl, not a crowd.
                try {
                    handle(s);
                } catch (Exception e) {
                    log("request failed: " + e);
                } finally {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // Client already gone.
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                log("debug server stopped: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String requestLine = in.readLine();
        if (requestLine == null) {
            return;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            return;
        }
        String target = parts[1];
        String path = target;
        Map<String, String> query = new HashMap<>();
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    query.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
            }
        }

        String body;
        String type = "text/plain";
        switch (path) {
            case "/api/state":   body = stateJson(); type = "application/json"; break;
            case "/api/log":     body = joined(logLines()); break;
            case "/api/raw":     body = rawDump(); break;
            case "/api/drive":   body = drive(query); break;
            case "/api/stop":    body = simple(r -> r.stop(), "stopped"); break;
            case "/api/estop":   body = simple(r -> r.eStop(), "emergency stop sent"); break;
            case "/api/reset":   body = simple(r -> r.resetOdometry(), "odometry reset"); break;
            case "/api/motors":  body = motors(query); break;
            case "/api/connect": host.connect(query.get("t")); body = "connecting\n"; break;
            case "/api/disconnect": host.disconnect(); body = "disconnecting\n"; break;
            case "/":            body = index(); type = "text/html"; break;
            default:             body = "no such endpoint: " + path + "\n"; break;
        }

        byte[] bytes = body.getBytes("UTF-8");
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(bytes);
        out.flush();
    }

    private interface Action {
        void on(ArcosRobot robot);
    }

    private String simple(Action a, String ok) {
        ArcosRobot r = host.robot();
        if (r == null || !r.isConnected()) {
            return "not connected\n";
        }
        a.on(r);
        return ok + "\n";
    }

    private String drive(Map<String, String> query) {
        ArcosRobot r = host.robot();
        if (r == null || !r.isConnected()) {
            return "not connected\n";
        }
        double v = parse(query.get("v"), 0);
        double w = parse(query.get("w"), 0);
        r.drive(v, w);
        return String.format(Locale.US, "drive v=%.0f mm/s w=%.0f deg/s%n", v, w);
    }

    private String motors(Map<String, String> query) {
        ArcosRobot r = host.robot();
        if (r == null || !r.isConnected()) {
            return "not connected\n";
        }
        boolean on = !"0".equals(query.get("on"));
        r.enableMotors(on);
        return "motors " + (on ? "enabled" : "disabled") + "\n";
    }

    private static double parse(String s, double fallback) {
        try {
            return s == null ? fallback : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String stateJson() {
        ArcosRobot r = host.robot();
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"connected\":").append(r != null && r.isConnected());
        if (r != null) {
            sb.append(",\"state\":\"").append(r.state()).append('"');
            if (r.info() != null) {
                sb.append(",\"name\":\"").append(r.info().name).append('"');
                sb.append(",\"subtype\":\"").append(r.info().subtype).append('"');
                sb.append(",\"paramsRecognised\":").append(r.info().paramsRecognised);
            }
            sb.append(",\"transport\":\"").append(r.transport().name()).append('"');
            RobotState s = r.lastState();
            if (s != null) {
                sb.append(String.format(Locale.US,
                        ",\"x\":%.1f,\"y\":%.1f,\"theta\":%.2f,\"velocity\":%.1f"
                                + ",\"leftVel\":%.1f,\"rightVel\":%.1f,\"battery\":%.2f"
                                + ",\"motors\":%s,\"estop\":%s,\"stalled\":%s,\"ageMs\":%d",
                        s.x, s.y, s.theta, s.velocity(), s.leftVel, s.rightVel,
                        s.batteryVoltage, s.motorsEnabled, s.eStopPressed, s.stalled(),
                        System.currentTimeMillis() - s.timestamp));
                sb.append(",\"sonar\":[");
                for (int i = 0; i < s.sonar.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(s.sonar[i]);
                }
                sb.append(']');
            }
        }
        LoggingTransport tap = host.tap();
        if (tap != null) {
            sb.append(",\"bytesOut\":").append(tap.bytesOut());
            sb.append(",\"bytesIn\":").append(tap.bytesIn());
        }
        return sb.append("}\n").toString();
    }

    private List<String> logLines() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    private String rawDump() {
        LoggingTransport tap = host.tap();
        if (tap == null) {
            return "no transport tap — connect first\n";
        }
        List<String> lines = tap.recent();
        if (lines.isEmpty()) {
            return "no traffic captured\n";
        }
        return "bytes out=" + tap.bytesOut() + " in=" + tap.bytesIn() + "\n"
                + joined(lines);
    }

    private static String joined(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private String index() {
        return "<!doctype html><meta name=viewport content='width=device-width'>"
                + "<style>body{font:14px monospace;background:#0d1117;color:#e6edf3;padding:16px}"
                + "a{color:#4da3ff;display:block;padding:3px 0}</style>"
                + "<h3>arcos debug</h3><pre>" + stateJson() + "</pre>"
                + "<a href='/api/state'>/api/state</a>"
                + "<a href='/api/log'>/api/log</a>"
                + "<a href='/api/raw'>/api/raw</a>"
                + "<a href='/api/drive?v=100&w=0'>/api/drive?v=100&amp;w=0</a>"
                + "<a href='/api/stop'>/api/stop</a>"
                + "<a href='/api/estop'>/api/estop</a>"
                + "<a href='/api/motors?on=1'>/api/motors?on=1</a>";
    }
}
