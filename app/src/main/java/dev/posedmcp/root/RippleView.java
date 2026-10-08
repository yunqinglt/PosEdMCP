package dev.posedmcp.root;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import com.google.android.material.color.MaterialColors;

/**
 * The freeze/unfreeze ripple: one wave, MD3 colours, no allocations in draw.
 *
 * <p>Expand runs from the point the user tapped to the screen edge, the way
 * the freeze fans out from the pill; contract runs the other way, gathering
 * back into the pill as the app is released. The wave front is a band of
 * {@code colorPrimary} over {@code colorPrimaryContainer}, with a trailing
 * echo a little way behind it, all from two fixed shaders - the canvas is
 * scaled per frame rather than the gradient rebuilt, so every frame is two
 * circles and nothing else.
 */
public final class RippleView extends View {

    /** The radius the shaders are built at; the canvas scales it per frame. */
    private static final float BASE_RADIUS = 1000f;
    private static final float ECHO_SCALE = 0.62f;
    private static final float ECHO_ALPHA = 0.55f;

    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);

    private long startAt;
    private long durationMs;
    private float fromR;
    private float toR;
    private float cx;
    private float cy;
    private boolean expanding;
    private boolean running;

    public RippleView(Context context) {
        super(context);
        int primary = MaterialColors.getColor(
                this, androidx.appcompat.R.attr.colorPrimary);
        int container = MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorPrimaryContainer);
        int lead = withAlpha(primary, 120);
        int trail = withAlpha(container, 90);
        int glowColor = withAlpha(container, 46);

        wave.setShader(new RadialGradient(0f, 0f, BASE_RADIUS,
                new int[]{0x00000000, 0x00000000, trail, lead, 0x00000000},
                new float[]{0f, 0.70f, 0.82f, 0.92f, 1f},
                Shader.TileMode.CLAMP));
        glow.setShader(new RadialGradient(0f, 0f, BASE_RADIUS,
                new int[]{0x00000000, glowColor, 0x00000000},
                new float[]{0f, 0.85f, 1f},
                Shader.TileMode.CLAMP));
        setClickable(false);
        setFocusable(false);
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** One wave spreading from the tap to the screen edge. */
    public void startExpand(float centerX, float centerY, float toRadius, long ms) {
        start(centerX, centerY, 0f, toRadius, ms, true);
    }

    /** One wave closing from the screen edge back into the pill. */
    public void startContract(float centerX, float centerY, float fromRadius, long ms) {
        start(centerX, centerY, fromRadius, 0f, ms, false);
    }

    private void start(float centerX, float centerY, float from, float to, long ms,
            boolean expand) {
        cx = centerX;
        cy = centerY;
        fromR = from;
        toR = to;
        durationMs = ms;
        expanding = expand;
        startAt = SystemClock.uptimeMillis();
        running = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!running) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        float t = Math.min(1f, (now - startAt) / (float) durationMs);
        // Expansion leaves the pill fast and settles as it reaches the edge;
        // contraction gathers speed as it arrives at the pill.
        float eased = expanding
                ? 1f - (1f - t) * (1f - t) * (1f - t)
                : t * t * t;
        float radius = fromR + (toR - fromR) * eased;
        float alpha = expanding ? 1f - t : t;

        if (radius > 0.5f && alpha > 0.01f) {
            float scale = radius / BASE_RADIUS;
            canvas.save();
            canvas.translate(cx, cy);
            canvas.scale(scale, scale);
            int a = (int) (alpha * 255);
            glow.setAlpha(a);
            wave.setAlpha(a);
            canvas.drawCircle(0f, 0f, BASE_RADIUS, glow);
            canvas.drawCircle(0f, 0f, BASE_RADIUS, wave);
            canvas.scale(ECHO_SCALE, ECHO_SCALE);
            wave.setAlpha((int) (a * ECHO_ALPHA));
            canvas.drawCircle(0f, 0f, BASE_RADIUS, wave);
            canvas.restore();
        }

        if (t >= 1f) {
            running = false;
        } else {
            postInvalidateOnAnimation();
        }
    }
}
