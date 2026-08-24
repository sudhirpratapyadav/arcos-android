package com.arcos.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.arcos.RobotState;

/**
 * Sonar returns drawn around the robot, plus a short trail of where it has been.
 *
 * <p>Robot-centric and always pointing up: this shows what the sonar sees right
 * now, not a map. The trail is in the odometry frame, rotated to match.
 */
public final class RadarView extends View {

    /** Angles of the eight front transducers on a Pioneer, degrees from ahead. */
    private static final double[] SONAR_ANGLES = {90, 50, 30, 10, -10, -30, -50, -90};
    /** Furthest range drawn, mm. */
    private static final float RANGE_MM = 3000;
    /** How many odometry samples the trail keeps. */
    private static final int TRAIL = 240;

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] trailX = new float[TRAIL];
    private final float[] trailY = new float[TRAIL];
    private int trailCount;
    private int trailHead;

    private RobotState state;

    public RadarView(Context context) {
        super(context);
        grid.setColor(Color.parseColor("#2a3240"));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(dp(1));
        gridText.setColor(Color.parseColor("#55606f"));
        gridText.setTextSize(dp(9));
        hit.setColor(Color.parseColor("#ff9f43"));
        beam.setColor(Color.parseColor("#2f6f4a"));
        beam.setStyle(Paint.Style.STROKE);
        beam.setStrokeWidth(dp(1));
        body.setColor(Color.parseColor("#4da3ff"));
        trailPaint.setColor(Color.parseColor("#3a5a7a"));
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(dp(1.5f));
    }

    /** Feeds a new sample. Safe to call from the UI thread only. */
    public void update(RobotState s) {
        this.state = s;
        trailX[trailHead] = (float) s.x;
        trailY[trailHead] = (float) s.y;
        trailHead = (trailHead + 1) % TRAIL;
        if (trailCount < TRAIL) {
            trailCount++;
        }
        invalidate();
    }

    /** Clears the trail, for when odometry is reset. */
    public void clearTrail() {
        trailCount = 0;
        trailHead = 0;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dp(10);
        float scale = radius / RANGE_MM;

        for (int r = 1000; r <= 3000; r += 1000) {
            canvas.drawCircle(cx, cy, r * scale, grid);
            canvas.drawText((r / 1000) + "m", cx + dp(3), cy - r * scale + dp(11), gridText);
        }

        RobotState s = state;
        if (s != null) {
            drawTrail(canvas, cx, cy, scale, s);

            for (int i = 0; i < SONAR_ANGLES.length && i < s.sonar.length; i++) {
                int range = s.sonar[i];
                if (range < 0) {
                    continue;
                }
                // Straight ahead is up, so screen angle is measured from -90.
                double a = Math.toRadians(SONAR_ANGLES[i] - 90);
                float d = Math.min(range, RANGE_MM) * scale;
                float px = cx + (float) Math.cos(a) * d;
                float py = cy + (float) Math.sin(a) * d;
                canvas.drawLine(cx, cy, px, py, beam);
                if (range < RANGE_MM) {
                    canvas.drawCircle(px, py, dp(4), hit);
                }
            }
        }

        // The robot, always pointing up.
        Path p = new Path();
        p.moveTo(cx, cy - dp(11));
        p.lineTo(cx - dp(8), cy + dp(8));
        p.lineTo(cx + dp(8), cy + dp(8));
        p.close();
        canvas.drawPath(p, body);
    }

    private void drawTrail(Canvas canvas, float cx, float cy, float scale, RobotState s) {
        if (trailCount < 2) {
            return;
        }
        // Rotate the odometry-frame trail so the robot's heading points up.
        double th = Math.toRadians(s.theta);
        double cos = Math.cos(-th + Math.PI / 2);
        double sin = Math.sin(-th + Math.PI / 2);

        Path path = new Path();
        for (int i = 0; i < trailCount; i++) {
            int idx = (trailHead - trailCount + i + TRAIL * 2) % TRAIL;
            double dx = trailX[idx] - s.x;
            double dy = trailY[idx] - s.y;
            float px = cx + (float) ((dx * cos - dy * sin) * scale);
            float py = cy - (float) ((dx * sin + dy * cos) * scale);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        canvas.drawPath(path, trailPaint);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
