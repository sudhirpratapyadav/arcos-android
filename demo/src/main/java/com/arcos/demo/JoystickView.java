package com.arcos.demo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * A thumb stick. Reports its position as two values in -1..1, and springs back to
 * centre when released.
 *
 * <p>The release behaviour is the safety-relevant part: lifting your finger must
 * command zero, not hold the last value.
 */
public final class JoystickView extends View {

    /** Called on the UI thread whenever the stick moves. */
    public interface Listener {
        /**
         * @param forward  -1 (full back) to 1 (full ahead)
         * @param turn     -1 (full right) to 1 (full left)
         */
        void onMove(float forward, float turn);
    }

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cross = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Listener listener;
    private float knobX;
    private float knobY;
    private boolean held;

    public JoystickView(Context context) {
        super(context);
        base.setColor(Color.parseColor("#1b1f27"));
        ring.setColor(Color.parseColor("#39424f"));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(2));
        cross.setColor(Color.parseColor("#2a3240"));
        cross.setStrokeWidth(dp(1));
        knob.setColor(Color.parseColor("#4da3ff"));
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Recentres the stick and reports zero, as if the finger had lifted. */
    public void release() {
        held = false;
        knobX = 0;
        knobY = 0;
        invalidate();
        if (listener != null) {
            listener.onMove(0, 0);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dp(6);

        canvas.drawCircle(cx, cy, radius, base);
        canvas.drawCircle(cx, cy, radius, ring);
        canvas.drawLine(cx - radius, cy, cx + radius, cy, cross);
        canvas.drawLine(cx, cy - radius, cx, cy + radius, cross);

        knob.setColor(held ? Color.parseColor("#6cb6ff") : Color.parseColor("#4da3ff"));
        canvas.drawCircle(cx + knobX * radius, cy + knobY * radius, dp(28), knob);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - dp(6);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                held = true;
                // Keep the parent scroll view from stealing the gesture mid-drive.
                getParent().requestDisallowInterceptTouchEvent(true);
                float dx = (event.getX() - cx) / radius;
                float dy = (event.getY() - cy) / radius;
                float mag = (float) Math.hypot(dx, dy);
                if (mag > 1f) {
                    dx /= mag;
                    dy /= mag;
                }
                knobX = dx;
                knobY = dy;
                invalidate();
                if (listener != null) {
                    // Screen Y grows downward; forward is up.
                    listener.onMove(-knobY, -knobX);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                release();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
