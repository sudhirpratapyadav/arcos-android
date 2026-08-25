package com.arcos.demo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.arcos.ArcosListener;
import com.arcos.ArcosRobot;
import com.arcos.RobotInfo;
import com.arcos.RobotState;
import com.arcos.Transport;
import com.arcos.transport.LoggingTransport;
import com.arcos.transport.SimTransport;
import com.arcos.transport.TcpTransport;
import com.arcos.transport.UsbPermission;
import com.arcos.transport.UsbSerialTransport;
import com.hoho.android.usbserial.driver.UsbSerialDriver;

import java.util.List;
import java.util.Locale;

/**
 * Teleop for a Pioneer, over whichever transport is to hand.
 *
 * <p>The UI is built in code rather than XML so the demo can be compiled by the
 * plain javac/d8 pipeline in {@code build.sh}, with no resource compilation step.
 */
public final class MainActivity extends Activity {

    /** Full-stick speed, mm/s. A P3-DX will do far more; this is a sane indoor pace. */
    private static final int MAX_SPEED_MM_S = 500;
    /** Full-stick turn rate, deg/s. */
    private static final int MAX_TURN_DEG_S = 90;
    /** Port for the debug HTTP server. */
    private static final int DEBUG_PORT = 8080;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ArcosRobot robot;
    private LoggingTransport tap;
    private DebugServer debug;
    private TextView status;
    private TextView telemetry;
    private TextView logView;
    private Button connectButton;
    private Button motorButton;
    private EditText hostField;
    private JoystickView joystick;
    private RadarView radar;

    private float forward;
    private float turn;
    private boolean motorsWanted;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        setConnected(false);

        // With the robot on the USB port there is no adb cable, so the app has to
        // be inspectable over the network instead.
        debug = new DebugServer(DEBUG_PORT, new DebugServer.Host() {
            @Override public ArcosRobot robot() { return robot; }
            @Override public LoggingTransport tap() { return tap; }
            @Override public void connect(String which) {
                ui.post(() -> {
                    if ("sim".equals(which)) {
                        MainActivity.this.connect(new SimTransport());
                    } else if (which != null && which.startsWith("tcp:")) {
                        MainActivity.this.connect(new TcpTransport(which.substring(4)));
                    } else {
                        connectUsb();
                    }
                });
            }
            @Override public void disconnect() {
                ui.post(MainActivity.this::disconnect);
            }
        });
        debug.start();
        appendLog("debug http on " + DebugServer.localAddress() + ":" + DEBUG_PORT);
    }

    @Override protected void onPause() {
        super.onPause();
        // Leaving the app must not leave the robot driving. The library's command
        // timeout would catch this within 2 s anyway; doing it here is immediate.
        if (joystick != null) {
            joystick.release();
        }
        if (robot != null && robot.isConnected()) {
            robot.stop();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (robot != null) {
            robot.disconnect();
        }
        if (debug != null) {
            debug.stop();
        }
    }

    // ---------- UI ----------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0d1117"));
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        status = label("Disconnected", 16, "#e6edf3");
        root.addView(status);

        telemetry = label("--", 13, "#8b949e");
        telemetry.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(telemetry, marginTop(dp(4)));

        // Transport picker.
        LinearLayout transports = new LinearLayout(this);
        transports.setOrientation(LinearLayout.HORIZONTAL);
        transports.addView(button("USB", v -> connectUsb()), weighted());
        transports.addView(button("Simulator", v -> connect(new SimTransport())), weighted());
        transports.addView(button("TCP", v -> connectTcp()), weighted());
        root.addView(transports, marginTop(dp(10)));

        hostField = new EditText(this);
        hostField.setHint("MobileSim host, e.g. 192.168.1.20");
        hostField.setInputType(InputType.TYPE_CLASS_TEXT);
        hostField.setTextColor(Color.parseColor("#e6edf3"));
        hostField.setHintTextColor(Color.parseColor("#57606a"));
        hostField.setTextSize(13);
        root.addView(hostField, marginTop(dp(4)));

        radar = new RadarView(this);
        LinearLayout.LayoutParams radarParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        radarParams.topMargin = dp(10);
        root.addView(radar, radarParams);

        // Controls.
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        joystick = new JoystickView(this);
        joystick.setListener(this::onStick);
        LinearLayout.LayoutParams stickParams = new LinearLayout.LayoutParams(dp(190), dp(190));
        controls.addView(joystick, stickParams);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setPadding(dp(10), 0, 0, 0);

        connectButton = button("Disconnect", v -> disconnect());
        motorButton = button("Motors: off", v -> toggleMotors());
        Button estop = button("E-STOP", v -> {
            joystick.release();
            if (robot != null) {
                robot.eStop();
            }
            toast("Emergency stop sent");
        });
        estop.setBackgroundColor(Color.parseColor("#8b2c2c"));
        Button reset = button("Reset odometry", v -> {
            if (robot != null) {
                robot.resetOdometry();
                radar.clearTrail();
            }
        });

        buttons.addView(motorButton);
        buttons.addView(estop, marginTop(dp(6)));
        buttons.addView(reset, marginTop(dp(6)));
        buttons.addView(connectButton, marginTop(dp(6)));
        controls.addView(buttons, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(controls, marginTop(dp(10)));

        logView = label("", 11, "#6e7681");
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logView);
        LinearLayout.LayoutParams logParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90));
        logParams.topMargin = dp(8);
        root.addView(logScroll, logParams);

        return root;
    }

    // ---------- connecting ----------

    private void connectUsb() {
        List<UsbSerialDriver> drivers = UsbSerialTransport.available(this);
        if (drivers.isEmpty()) {
            toast("No USB-serial adapter found. Check the OTG cable.");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbPermission.request(this, driver.getDevice(), granted -> {
            if (!granted) {
                toast("USB permission denied");
                return;
            }
            connect(new UsbSerialTransport(this, driver, UsbSerialTransport.DEFAULT_BAUD));
        });
    }

    private void connectTcp() {
        String host = hostField.getText().toString().trim();
        if (host.isEmpty()) {
            toast("Enter a host for the TCP transport");
            return;
        }
        connect(new TcpTransport(host));
    }

    private void connect(Transport transport) {
        disconnect();
        appendLog("connecting over " + transport.name());

        tap = new LoggingTransport(transport);
        robot = new ArcosRobot(tap);
        robot.addListener(new ArcosListener() {
            @Override public void onConnected(RobotInfo info) {
                ui.post(() -> {
                    setConnected(true);
                    status.setText("Connected - " + info.name + " (" + info.subtype + ")");
                    if (!info.paramsRecognised) {
                        appendLog("unknown model; odometry scale may be off");
                    }
                });
            }

            @Override public void onState(RobotState s) {
                ui.post(() -> showState(s));
            }

            @Override public void onDisconnected(String reason) {
                ui.post(() -> {
                    setConnected(false);
                    status.setText("Disconnected - " + reason);
                });
            }

            @Override public void onError(Throwable t) {
                ui.post(() -> appendLog("error: " + t.getMessage()));
            }

            @Override public void onLog(String message) {
                ui.post(() -> appendLog(message));
            }
        });
        robot.connect();
        status.setText("Connecting...");
    }

    private void disconnect() {
        if (robot != null) {
            robot.disconnect();
            robot = null;
        }
        motorsWanted = false;
        setConnected(false);
    }

    // ---------- control ----------

    private void onStick(float fwd, float trn) {
        forward = fwd;
        turn = trn;
        ArcosRobot r = robot;
        if (r != null && r.isConnected()) {
            // Called continuously while the finger moves, which also keeps the
            // library's command timeout from firing.
            r.drive(fwd * MAX_SPEED_MM_S, trn * MAX_TURN_DEG_S);
        }
    }

    private void toggleMotors() {
        ArcosRobot r = robot;
        if (r == null || !r.isConnected()) {
            toast("Not connected");
            return;
        }
        motorsWanted = !motorsWanted;
        r.enableMotors(motorsWanted);
        motorButton.setText(motorsWanted ? "Motors: on" : "Motors: off");
    }

    private void showState(RobotState s) {
        radar.update(s);
        telemetry.setText(String.format(Locale.US,
                "x %6.0f mm   y %6.0f mm   th %6.1f°%n"
                        + "v %5.0f mm/s   batt %.1f V   sonar %s%n"
                        + "motors %-3s  estop %-3s  stall %s",
                s.x, s.y, s.theta,
                s.velocity(), s.batteryVoltage,
                s.closestSonar() == Integer.MAX_VALUE ? "--" : (s.closestSonar() + " mm"),
                s.motorsEnabled ? "on" : "off",
                s.eStopPressed ? "YES" : "no",
                s.stalled() ? "YES" : "no"));

        // Keep the button honest: the robot drops the motors on its own after an
        // emergency stop, so the label must follow the robot, not our intent.
        if (motorsWanted != s.motorsEnabled) {
            motorsWanted = s.motorsEnabled;
            motorButton.setText(motorsWanted ? "Motors: on" : "Motors: off");
        }
    }

    private void setConnected(boolean connected) {
        connectButton.setText(connected ? "Disconnect" : "Not connected");
        connectButton.setEnabled(connected);
        if (!connected) {
            motorButton.setText("Motors: off");
            telemetry.setText("--");
        }
    }

    // ---------- small helpers ----------

    private final StringBuilder logBuffer = new StringBuilder();

    private void appendLog(String message) {
        if (debug != null) {
            debug.log(message);
        }
        logBuffer.append(message).append('\n');
        // Keep the buffer from growing without bound over a long session.
        if (logBuffer.length() > 4000) {
            logBuffer.delete(0, logBuffer.length() - 3000);
        }
        logView.setText(logBuffer);
    }

    private TextView label(String text, int sizeSp, String colour) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(Color.parseColor(colour));
        return t;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setOnClickListener(onClick);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = px;
        return p;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        appendLog(message);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
