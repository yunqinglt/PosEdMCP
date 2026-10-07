package dev.posedmcp.root;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.posedmcp.Logx;
import dev.posedmcp.McpService;
import dev.posedmcp.R;
import dev.posedmcp.state.Prefs;

/**
 * The floating button that freezes the app in front.
 *
 * <p>Two windows, never more than one on screen at a time, and the reason is a
 * measured failure. The first version was a single full-screen window whose
 * only touchable child was the pill, on the assumption that NOT_TOUCH_MODAL
 * limits a window's touch handling to its views' touchable region. It does
 * not: a full-screen overlay window ate every tap on the screen, its own
 * dialogs' buttons included. So the idle state is a window no bigger than the
 * pill - outside its bounds, NOT_TOUCH_MODAL hands every touch to whatever is
 * underneath. The frozen state is the exact opposite: a full-screen shield
 * that eats everything, because a frozen app that the user taps cannot answer
 * the input dispatcher, and a few seconds of that is how it earns an ANR
 * dialog over the exact screen the freeze was meant to preserve. The shield
 * carries its own countdown pill, so the two windows never need to stack.
 *
 * <p>The button is the only way a freeze ever starts - nothing here or
 * anywhere else lets the agent do it.
 */
public final class ProbeWindow {

    private static final int TICK_MS = 500;
    private static final int PILL_MARGIN_DP = 12;
    private static final int PILL_TOP_DP = 60;
    private static final int DRAG_SLOP_DP = 8;

    private static Context appCtx;
    private static WindowManager wm;
    private static boolean attached;
    private static boolean uiFrozen;
    private static long uiExpiresAt;

    private static View idlePill;
    private static WindowManager.LayoutParams idleLp;

    private static View frozenRoot;
    private static TextView frozenText;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService OPS = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "posedmcp-probe");
        t.setDaemon(true);
        return t;
    });

    private static final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!uiFrozen) {
                return;
            }
            long left = uiExpiresAt - System.currentTimeMillis();
            if (left <= 0) {
                // The detached watchdog has let it go. The window follows, and
                // the persisted record is cleared off the main thread.
                main.post(() -> switchToIdle());
                OPS.submit(() -> ProcessProbe.current(appCtx));
                return;
            }
            frozenText.setText(appCtx.getString(R.string.probe_pill_resume, mmss(left)));
            main.postDelayed(this, TICK_MS);
        }
    };

    private ProbeWindow() {
    }

    /** Attaches the window; a no-op when it is already up. Safe on any thread. */
    public static synchronized void show(Context ctx) {
        if (attached) {
            syncWithState(ctx);
            return;
        }
        if (!Settings.canDrawOverlays(ctx)) {
            Logx.w("probe window: overlay permission missing; not attaching");
            return;
        }
        wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }
        appCtx = ctx.getApplicationContext();
        main.post(() -> {
            if (attached) {
                return;
            }
            try {
                syncWithState(appCtx);
            } catch (Throwable t) {
                Logx.e("probe window: could not attach", t);
            }
        });
    }

    /**
     * Removes the window, releasing whatever it froze first - a frozen app
     * with no countdown on screen is exactly the state this window exists to
     * prevent.
     */
    public static synchronized void dismiss() {
        if (!attached) {
            return;
        }
        attached = false;
        uiFrozen = false;
        OPS.submit(() -> {
            try {
                ProcessProbe.resume(appCtx);
            } catch (Throwable t) {
                Logx.w("probe: resume on dismiss failed: " + t);
            }
        });
        main.post(() -> {
            removeWindows();
        });
    }

    private static void removeWindows() {
        try {
            if (frozenRoot != null) {
                wm.removeViewImmediate(frozenRoot);
            }
        } catch (Throwable t) {
            Logx.w("probe: shield remove failed: " + t);
        }
        try {
            if (idlePill != null) {
                wm.removeViewImmediate(idlePill);
            }
        } catch (Throwable t) {
            Logx.w("probe: pill remove failed: " + t);
        }
        frozenRoot = null;
        idlePill = null;
    }

    /** Makes the window agree with the persisted state, e.g. after a restart. */
    private static void syncWithState(Context ctx) {
        ProcessProbe.State state = ProcessProbe.current(ctx);
        if (state != null) {
            switchToFrozen(state.expiresAt);
        } else {
            switchToIdle();
        }
    }

    // ---- idle pill ---------------------------------------------------------

    private static void switchToIdle() {
        uiFrozen = false;
        main.removeCallbacks(ticker);
        removeWindows();
        attachIdle();
        Logx.i("probe window: idle pill attached");
    }

    private static void attachIdle() {
        Context themed = new ContextThemeWrapper(appCtx, R.style.Theme_PosEdMCP);

        MaterialCardView pill = new MaterialCardView(themed);
        pill.setRadius(dp(18));
        pill.setCardElevation(dp(4));
        pill.setClickable(true);
        TextView text = new TextView(themed);
        text.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_LabelLarge);
        text.setText(R.string.probe_pill_freeze);
        text.setPadding(dp(14), dp(10), dp(14), dp(10));
        pill.addView(text);
        pill.setOnTouchListener(new DragOrTap());
        pill.setOnClickListener(v -> onPillTap());
        idlePill = pill;

        idleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        idleLp.gravity = Gravity.TOP | Gravity.START;
        int screenW = appCtx.getResources().getDisplayMetrics().widthPixels;
        idleLp.x = screenW - dp(140);   // a guess the post below corrects
        idleLp.y = dp(PILL_TOP_DP);

        wm.addView(pill, idleLp);
        attached = true;

        pill.post(() -> {
            if (idlePill == null) {
                return;
            }
            idleLp.x = appCtx.getResources().getDisplayMetrics().widthPixels
                    - pill.getWidth() - dp(PILL_MARGIN_DP);
            wm.updateViewLayout(pill, idleLp);
        });
    }

    // ---- frozen overlay -----------------------------------------------------

    private static void switchToFrozen(long expiresAt) {
        uiFrozen = true;
        uiExpiresAt = expiresAt;
        main.removeCallbacks(ticker);
        removeWindows();
        attachFrozen(expiresAt);
        Logx.i("probe window: frozen overlay attached");
        main.post(ticker);
    }

    private static void attachFrozen(long expiresAt) {
        Context themed = new ContextThemeWrapper(appCtx, R.style.Theme_PosEdMCP);

        FrameLayout root = new FrameLayout(themed);

        View shield = new View(themed);
        shield.setClickable(true);
        root.addView(shield, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        MaterialCardView pill = new MaterialCardView(themed);
        pill.setRadius(dp(18));
        pill.setCardElevation(dp(4));
        pill.setClickable(true);
        pill.setCardBackgroundColor(MaterialColors.getColor(pill,
                com.google.android.material.R.attr.colorErrorContainer));
        frozenText = new TextView(themed);
        frozenText.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_LabelLarge);
        frozenText.setTextColor(MaterialColors.getColor(pill,
                com.google.android.material.R.attr.colorOnErrorContainer));
        frozenText.setPadding(dp(14), dp(10), dp(14), dp(10));
        pill.addView(frozenText);
        pill.setOnClickListener(v -> resumeNow());
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        pillLp.rightMargin = dp(PILL_MARGIN_DP);
        pillLp.topMargin = dp(PILL_TOP_DP);
        root.addView(pill, pillLp);

        frozenRoot = root;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        wm.addView(root, lp);
        attached = true;
        setFrozenText(expiresAt);
    }

    private static void setFrozenText(long expiresAt) {
        if (frozenText != null) {
            frozenText.setText(appCtx.getString(R.string.probe_pill_resume,
                    mmss(Math.max(0L, expiresAt - System.currentTimeMillis()))));
        }
    }

    // ---- actions ------------------------------------------------------------

    private static void onPillTap() {
        Logx.i("probe pill: tap " + (uiFrozen ? "(frozen)" : "(idle)"));
        if (uiFrozen) {
            resumeNow();
            return;
        }
        OPS.submit(() -> {
            String pkg = foregroundPackage();
            Integer guard = ProcessProbe.guardError(appCtx, pkg);
            if (guard != null) {
                Logx.w("probe: freeze refused for " + pkg + ": "
                        + appCtx.getString(guard, pkg == null ? "" : pkg));
                toast(appCtx.getString(guard, pkg == null ? "" : pkg));
                return;
            }
            long durationMs = Prefs.of(appCtx).probeFreezeSeconds() * 1000L;
            // Optimistic: the shield goes up before the freeze lands, so no
            // tap can reach the app in the moment between deciding and doing.
            long expiresAt = System.currentTimeMillis() + durationMs;
            main.post(() -> switchToFrozen(expiresAt));
            try {
                ProcessProbe.State state = ProcessProbe.freeze(appCtx, pkg, durationMs);
                main.post(() -> {
                    uiExpiresAt = state.expiresAt;
                    setFrozenText(state.expiresAt);
                });
            } catch (Throwable t) {
                main.post(() -> switchToIdle());
                toast(appCtx.getString(R.string.toast_probe_freeze_failed,
                        t.getMessage() == null ? "" : t.getMessage()));
                return;
            }
            toast(appCtx.getString(R.string.toast_probe_frozen, pkg,
                    appCtx.getString(durationLabelRes(
                            Prefs.of(appCtx).probeFreezeSeconds()))));
        });
    }

    private static void resumeNow() {
        OPS.submit(() -> {
            ProcessProbe.State state = ProcessProbe.current(appCtx);
            if (state == null) {
                main.post(() -> switchToIdle());
                return;
            }
            String pkg = state.pkg;
            try {
                ProcessProbe.resume(appCtx);
                toast(appCtx.getString(R.string.toast_probe_resumed, pkg));
            } catch (Throwable t) {
                toast(appCtx.getString(R.string.toast_probe_freeze_failed,
                        t.getMessage() == null ? "" : t.getMessage()));
            }
            main.post(() -> switchToIdle());
        });
    }

    /**
     * The package in front, from the module's poller when the bridge is up and
     * from the activity stack when it is not.
     */
    private static String foregroundPackage() {
        McpService service = McpService.instance();
        if (service != null && service.isRunning()) {
            try {
                String pkg = service.foregroundPackage();
                if (pkg != null && !pkg.isEmpty()) {
                    return pkg;
                }
            } catch (Throwable t) {
                Logx.w("probe: foreground via the bridge failed: " + t);
            }
        }
        RootShell.Result r = RootShell.exec(
                "dumpsys activity activities | grep -m1 -E \"topResumedActivity|mResumedActivity\"",
                8_000L);
        String line = r.stdout;
        int slash = line.indexOf('/');
        int space = line.lastIndexOf(' ', slash);
        if (slash > 0 && space >= 0 && space < slash) {
            return line.substring(space + 1, slash).trim();
        }
        return null;
    }

    private static int durationLabelRes(int seconds) {
        if (seconds <= Prefs.PROBE_FREEZE_CHOICES[0]) {
            return R.string.probe_duration_30s;
        }
        if (seconds <= Prefs.PROBE_FREEZE_CHOICES[1]) {
            return R.string.probe_duration_60s;
        }
        return R.string.probe_duration_300s;
    }

    private static String mmss(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static void toast(String text) {
        main.post(() -> {
            try {
                Toast.makeText(appCtx, text, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Logx.w("probe: toast failed: " + t);
            }
        });
    }

    /** Drags the pill; a touch that never moves is a tap. */
    private static final class DragOrTap implements View.OnTouchListener {
        private final float[] down = new float[2];
        private final int[] last = new int[2];
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    last[0] = idleLp.x;
                    last[1] = idleLp.y;
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    moved = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - down[0];
                    float dy = e.getRawY() - down[1];
                    if (!moved && Math.hypot(dx, dy) > dp(DRAG_SLOP_DP)) {
                        moved = true;
                    }
                    if (moved) {
                        int screenW = appCtx.getResources().getDisplayMetrics().widthPixels;
                        int screenH = appCtx.getResources().getDisplayMetrics().heightPixels;
                        idleLp.x = clamp(last[0] + (int) dx, 0, screenW - v.getWidth());
                        idleLp.y = clamp(last[1] + (int) dy, 0, screenH - v.getHeight());
                        wm.updateViewLayout(v, idleLp);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        v.performClick();
                    }
                    return true;
                default:
                    return false;
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static int dp(float value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                appCtx.getResources().getDisplayMetrics());
    }
}
