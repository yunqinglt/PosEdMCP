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
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.posedmcp.Logx;
import dev.posedmcp.McpService;
import dev.posedmcp.R;
import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.state.Prefs;

/**
 * The floating button that freezes the app in front.
 *
 * <p>Three windows, at most one on screen, and the reason is a pair of
 * measured failures. A single full-screen window with NOT_TOUCH_MODAL ate
 * every tap on the phone, so the idle state is a window no bigger than the
 * pill. The frozen state was a full-screen shield that ate everything -
 * including the launcher, which left the user unable to open another app
 * while a freeze was running. So the frozen state now splits: the shield,
 * which exists to keep taps off a frozen app that cannot answer the input
 * dispatcher, is up only while the frozen target is actually in front; the
 * moment the foreground changes, the shield drops to a small countdown pill
 * that every other app can be used around. The ticker watches the
 * foreground and switches between the two.
 *
 * <p>The button is the only way a freeze ever starts - nothing here or
 * anywhere else lets the agent do it.
 */
public final class ProbeWindow {

    private static final int TICK_MS = 500;
    private static final int FOREGROUND_CHECK_TICKS = 4;
    private static final int PILL_MARGIN_DP = 12;
    private static final int PILL_TOP_DP = 60;
    private static final int DRAG_SLOP_DP = 8;

    private static Context appCtx;
    private static WindowManager wm;
    private static boolean attached;
    private static boolean uiFrozen;
    private static boolean uiShield;
    private static long uiExpiresAt;
    private static String frozenPkg;
    private static int tickCount;

    private static View idlePill;
    private static WindowManager.LayoutParams idleLp;

    private static View frozenRoot;
    private static TextView frozenText;

    private static View frozenPill;
    private static WindowManager.LayoutParams frozenPillLp;
    private static TextView frozenPillText;

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
            String text = appCtx.getString(R.string.probe_pill_resume, mmss(left));
            if (frozenText != null) {
                frozenText.setText(text);
            }
            if (frozenPillText != null) {
                frozenPillText.setText(text);
            }
            // The shield must not outlive the target's foreground: a frozen
            // app the user has left is no longer under their fingers, and the
            // launcher has to stay usable. Checked every ~2 seconds.
            if (++tickCount % FOREGROUND_CHECK_TICKS == 0) {
                switchModeIfNeeded();
            }
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
        uiShield = false;
        frozenPkg = null;
        OPS.submit(() -> {
            try {
                ProcessProbe.resume(appCtx);
            } catch (Throwable t) {
                Logx.w("probe: resume on dismiss failed: " + t);
            }
        });
        main.post(() -> removeWindows());
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
            if (frozenPill != null) {
                wm.removeViewImmediate(frozenPill);
            }
        } catch (Throwable t) {
            Logx.w("probe: frozen pill remove failed: " + t);
        }
        try {
            if (idlePill != null) {
                wm.removeViewImmediate(idlePill);
            }
        } catch (Throwable t) {
            Logx.w("probe: pill remove failed: " + t);
        }
        frozenRoot = null;
        frozenText = null;
        frozenPill = null;
        frozenPillText = null;
        idlePill = null;
    }

    /** Makes the window agree with the persisted state, e.g. after a restart. */
    private static void syncWithState(Context ctx) {
        ProcessProbe.State state = ProcessProbe.current(ctx);
        if (state != null) {
            frozenPkg = state.pkg;
            switchToFrozen(state.expiresAt);
        } else {
            switchToIdle();
        }
    }

    // ---- idle pill ---------------------------------------------------------

    private static void switchToIdle() {
        uiFrozen = false;
        uiShield = false;
        frozenPkg = null;
        tickCount = 0;
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
        idlePill = pill;
        pill.setOnTouchListener(new DragWindow(() -> idleLp, p -> idleLp = p));
        pill.setOnClickListener(v -> onPillTap());

        idleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        idleLp.gravity = Gravity.TOP | Gravity.START;
        int screenW = appCtx.getResources().getDisplayMetrics().widthPixels;
        idleLp.x = screenW - dp(140);
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

    // ---- frozen state -------------------------------------------------------

    /**
     * Enters the frozen UI: the full-screen shield while the frozen target is
     * in front, the small countdown pill once it is not.
     */
    private static void switchToFrozen(long expiresAt) {
        uiFrozen = true;
        uiExpiresAt = expiresAt;
        tickCount = 0;
        main.removeCallbacks(ticker);
        removeWindows();
        if (frozenTargetForeground()) {
            uiShield = true;
            attachFrozen(expiresAt);
        } else {
            uiShield = false;
            attachFrozenPill(expiresAt);
        }
        main.post(ticker);
    }

    /**
     * Follows the foreground: the shield exists only to keep taps off a frozen
     * app that is under them. Once the user has left it, the shield has
     * nothing to protect and everything to block - the launcher above all.
     */
    private static void switchModeIfNeeded() {
        if (!uiFrozen) {
            return;
        }
        boolean foreground = frozenTargetForeground();
        if (foreground == uiShield) {
            return;
        }
        uiShield = foreground;
        removeWindows();
        if (foreground) {
            attachFrozen(uiExpiresAt);
            Logx.i("probe window: target back in front, shield restored");
        } else {
            attachFrozenPill(uiExpiresAt);
            Logx.i("probe window: target left the foreground, shield dropped");
        }
    }

    /** The frozen target is the window in front, per accessibility first. */
    private static boolean frozenTargetForeground() {
        String pkg = frozenPkg;
        if (pkg == null) {
            return false;
        }
        String window = AccessibilityBridge.lastWindowPackage();
        if (window == null || window.isEmpty()) {
            window = foregroundPackage();
        }
        return pkg.equals(window);
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
        pill.setOnTouchListener(new DragFrozenPill());
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        root.addView(pill, pillLp);

        pill.post(() -> {
            if (frozenRoot == null) {
                return;
            }
            pillLp.leftMargin = appCtx.getResources().getDisplayMetrics().widthPixels
                    - pill.getWidth() - dp(PILL_MARGIN_DP);
            pillLp.topMargin = dp(PILL_TOP_DP);
            pill.setLayoutParams(pillLp);
        });

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
        setFrozenTexts(expiresAt);
    }

    /**
     * The small frozen pill: the same countdown and resume, in a window no
     * bigger than itself, so every other app stays usable around it.
     */
    private static void attachFrozenPill(long expiresAt) {
        Context themed = new ContextThemeWrapper(appCtx, R.style.Theme_PosEdMCP);

        MaterialCardView pill = new MaterialCardView(themed);
        pill.setRadius(dp(18));
        pill.setCardElevation(dp(4));
        pill.setClickable(true);
        pill.setCardBackgroundColor(MaterialColors.getColor(pill,
                com.google.android.material.R.attr.colorErrorContainer));
        frozenPillText = new TextView(themed);
        frozenPillText.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_LabelLarge);
        frozenPillText.setTextColor(MaterialColors.getColor(pill,
                com.google.android.material.R.attr.colorOnErrorContainer));
        frozenPillText.setPadding(dp(14), dp(10), dp(14), dp(10));
        pill.addView(frozenPillText);
        frozenPill = pill;
        pill.setOnTouchListener(new DragWindow(() -> frozenPillLp, p -> frozenPillLp = p));
        pill.setOnClickListener(v -> resumeNow());

        frozenPillLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        frozenPillLp.gravity = Gravity.TOP | Gravity.START;
        // Where the user left the idle pill, so the freeze does not move the
        // button they just pressed.
        if (idleLp != null) {
            frozenPillLp.x = idleLp.x;
            frozenPillLp.y = idleLp.y;
        } else {
            int screenW = appCtx.getResources().getDisplayMetrics().widthPixels;
            frozenPillLp.x = screenW - dp(140);
            frozenPillLp.y = dp(PILL_TOP_DP);
        }

        wm.addView(pill, frozenPillLp);
        attached = true;

        pill.post(() -> {
            if (frozenPill == null) {
                return;
            }
            if (idleLp == null) {
                frozenPillLp.x = appCtx.getResources().getDisplayMetrics().widthPixels
                        - pill.getWidth() - dp(PILL_MARGIN_DP);
                wm.updateViewLayout(pill, frozenPillLp);
            }
        });
        setFrozenTexts(expiresAt);
    }

    private static void setFrozenTexts(long expiresAt) {
        String text = appCtx.getString(R.string.probe_pill_resume,
                mmss(Math.max(0L, expiresAt - System.currentTimeMillis())));
        if (frozenText != null) {
            frozenText.setText(text);
        }
        if (frozenPillText != null) {
            frozenPillText.setText(text);
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
            frozenPkg = pkg;
            long expiresAt = System.currentTimeMillis() + durationMs;
            main.post(() -> switchToFrozen(expiresAt));
            try {
                ProcessProbe.State state = ProcessProbe.freeze(appCtx, pkg, durationMs);
                main.post(() -> {
                    uiExpiresAt = state.expiresAt;
                    setFrozenTexts(state.expiresAt);
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

    /**
     * Drags a pill window by its WindowManager.LayoutParams; a touch that
     * never moves is a tap.
     */
    private static final class DragWindow implements View.OnTouchListener {
        private final Supplier<WindowManager.LayoutParams> get;
        private final Consumer<WindowManager.LayoutParams> set;
        private final float[] down = new float[2];
        private final int[] last = new int[2];
        private boolean moved;

        DragWindow(Supplier<WindowManager.LayoutParams> get,
                Consumer<WindowManager.LayoutParams> set) {
            this.get = get;
            this.set = set;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    WindowManager.LayoutParams p = get.get();
                    if (p == null) {
                        return true;
                    }
                    last[0] = p.x;
                    last[1] = p.y;
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
                        WindowManager.LayoutParams p = get.get();
                        if (p == null) {
                            return true;
                        }
                        p.x = clamp(last[0] + (int) dx, 0, screenW - v.getWidth());
                        p.y = clamp(last[1] + (int) dy, 0, screenH - v.getHeight());
                        set.accept(p);
                        wm.updateViewLayout(v, p);
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

    /**
     * Drags the shield's embedded pill, which moves by its layout margins
     * inside the full-screen window. A touch that never moves resumes.
     */
    private static final class DragFrozenPill implements View.OnTouchListener {
        private final float[] down = new float[2];
        private final int[] last = new int[2];
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    FrameLayout.LayoutParams lp =
                            (FrameLayout.LayoutParams) v.getLayoutParams();
                    last[0] = lp.leftMargin;
                    last[1] = lp.topMargin;
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
                        FrameLayout.LayoutParams lp =
                                (FrameLayout.LayoutParams) v.getLayoutParams();
                        lp.leftMargin = clamp(last[0] + (int) dx, 0, screenW - v.getWidth());
                        lp.topMargin = clamp(last[1] + (int) dy, 0, screenH - v.getHeight());
                        v.setLayoutParams(lp);
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
