package dev.posedmcp.root;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;

import dev.posedmcp.Logx;
import dev.posedmcp.R;

/**
 * The modal that a privileged request has to pass through.
 *
 * <p>It is an application overlay rather than an activity so that it can appear
 * while another app is in the foreground, and so a stray tap on the app
 * underneath cannot approve anything. The command is rendered verbatim: what the
 * user reads is exactly what {@code su} will run.
 *
 * <p>Styled with Material 3 like the rest of the app, which means the views are
 * built against a themed wrapper of the caller's context - an overlay has no
 * activity, so nothing would apply the app's theme to it otherwise, and Material
 * components refuse to build against a theme that is not a Material one.
 * Everything here is the most-confirmed surface of the app, so it is worth the
 * one wrapper.
 */
final class ConfirmOverlay {

    private ConfirmOverlay() {
    }

    /** Handle to a window that is currently on screen. */
    static final class Session {
        private final WindowManager wm;
        private final View root;
        private final Handler main = new Handler(Looper.getMainLooper());
        private Runnable tick;
        private boolean dismissed;

        Session(WindowManager wm, View root) {
            this.wm = wm;
            this.root = root;
        }

        /** Safe to call from any thread; the window itself is only touched on the main one. */
        void dismiss() {
            main.post(() -> dismissOnMain());
        }

        private void dismissOnMain() {
            if (dismissed) {
                return;
            }
            dismissed = true;
            main.removeCallbacksAndMessages(null);
            try {
                wm.removeViewImmediate(root);
            } catch (Throwable t) {
                Logx.w("overlay remove failed: " + t);
            }
        }
    }

    interface OnDecision {
        void onDecision(boolean approved, String note);
    }

    /**
     * Shows the confirmation window and reports whether it made it onto the screen.
     *
     * <p>Tool calls arrive on a worker thread, and a View cannot be constructed or
     * attached there, so the whole build runs on the main looper. The caller is
     * about to block waiting for the user anyway, so waiting briefly for the
     * window to appear costs nothing and lets it fall back to a notification if
     * the window is refused.
     */
    static boolean show(Context ctx, ConfirmationGate.Request req, OnDecision onDecision) {
        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return false;
        }
        Handler main = new Handler(Looper.getMainLooper());
        java.util.concurrent.CountDownLatch attached =
                new java.util.concurrent.CountDownLatch(1);
        boolean[] success = {false};

        main.post(() -> {
            try {
                success[0] = buildAndAttach(ctx, wm, req, onDecision, main);
            } catch (Throwable t) {
                Logx.e("could not attach confirmation overlay", t);
            } finally {
                attached.countDown();
            }
        });

        try {
            if (!attached.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                Logx.w("confirmation overlay did not appear within 5s");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return success[0];
    }

    private static boolean buildAndAttach(Context ctx, WindowManager wm,
            ConfirmationGate.Request req, OnDecision onDecision, Handler main) {
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;

        Context themed = new ContextThemeWrapper(ctx, R.style.Theme_PosEdMCP);

        MaterialCardView card = new MaterialCardView(themed);
        card.setRadius(dp(themed, 24));
        card.setCardElevation(dp(themed, 8));
        card.setCardBackgroundColor(role(card, com.google.android.material.R.attr
                .colorSurfaceContainerHigh));

        LinearLayout content = new LinearLayout(themed);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(themed, 20);
        content.setPadding(pad, pad, pad, pad);
        card.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(themed);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title(themed, req.title), new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView countdown = caption(themed);
        header.addView(countdown);
        content.addView(header);

        if (!TextUtils.isEmpty(req.requester)) {
            content.addView(caption(themed,
                            themed.getString(R.string.overlay_requested_by, req.requester)),
                    topMargin(themed, 4));
        }

        content.addView(sectionLabel(themed, themed.getString(R.string.overlay_command),
                        role(card, androidx.appcompat.R.attr.colorError)),
                topMargin(themed, 16));
        content.addView(scrollingBlock(themed, card, req.detail, true), weighted(themed, 6));

        if (!TextUtils.isEmpty(req.reason)) {
            content.addView(sectionLabel(themed, themed.getString(R.string.overlay_reason),
                            role(card, androidx.appcompat.R.attr.colorPrimary)),
                    topMargin(themed, 16));
            content.addView(scrollingBlock(themed, card, req.reason, false), weighted(themed, 6));
        }

        LinearLayout actions = new LinearLayout(themed);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        content.addView(actions, topMargin(themed, 16));

        MaterialButton deny = new MaterialButton(themed, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        deny.setText(themed.getString(R.string.overlay_deny));
        deny.setAllCaps(false);
        actions.addView(deny);

        // Root commands are the one kind the app will not let the user relax, so
        // the affirmative carries the theme's error role for those and the
        // ordinary primary for everything else. Colour is the only thing telling
        // the two apart at a glance, which is why it is not the same for both.
        boolean dangerous = req.kind == ConfirmationGate.Kind.SHELL;
        int container = dangerous
                ? role(card, androidx.appcompat.R.attr.colorError)
                : role(card, androidx.appcompat.R.attr.colorPrimary);
        int onContainer = dangerous
                ? role(card, com.google.android.material.R.attr.colorOnError)
                : role(card, com.google.android.material.R.attr.colorOnPrimary);

        MaterialButton approve = new MaterialButton(themed);
        approve.setText(req.approveLabel());
        approve.setAllCaps(false);
        approve.setBackgroundTintList(ColorStateList.valueOf(container));
        approve.setTextColor(onContainer);
        LinearLayout.LayoutParams approveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        approveLp.leftMargin = dp(themed, 10);
        actions.addView(approve, approveLp);

        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                Math.min(dp(themed, 380), screenW - dp(themed, 32)),
                Math.min(dp(themed, 520), screenH - dp(themed, 96)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.CENTER;

        Session session = new Session(wm, card);
        final long deadline = System.currentTimeMillis() + req.timeoutMs;
        final boolean[] answered = {false};

        session.tick = new Runnable() {
            @Override
            public void run() {
                if (answered[0]) {
                    return;
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    answered[0] = true;
                    session.dismiss();
                    onDecision.onDecision(false, "no answer within " + (req.timeoutMs / 1000) + "s");
                    return;
                }
                countdown.setText(themed.getString(R.string.overlay_countdown, (left + 999) / 1000));
                main.postDelayed(this, 500L);
            }
        };

        deny.setOnClickListener(v -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            session.dismissOnMain();
            onDecision.onDecision(false, "denied by the user");
        });
        approve.setOnClickListener(v -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            session.dismissOnMain();
            onDecision.onDecision(true, "approved by the user");
        });

        try {
            wm.addView(card, wlp);
        } catch (Throwable t) {
            Logx.e("could not attach confirmation overlay", t);
            return false;
        }
        main.post(session.tick);
        return true;
    }

    private static TextView title(Context themed, String text) {
        TextView tv = new TextView(themed);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        return tv;
    }

    private static TextView caption(Context themed) {
        TextView tv = new TextView(themed);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        return tv;
    }

    private static TextView caption(Context themed, String text) {
        TextView tv = caption(themed);
        tv.setText(text);
        return tv;
    }

    /** A scrollable panel, so long commands stay fully readable. */
    private static ScrollView scrollingBlock(Context themed, View carrier, String text,
            boolean monospace) {
        TextView tv = new TextView(themed);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        if (monospace) {
            tv.setTypeface(Typeface.MONOSPACE);
        }
        tv.setTextIsSelectable(true);
        tv.setTextColor(role(carrier, com.google.android.material.R.attr.colorOnSurface));
        int p = dp(themed, 12);
        tv.setPadding(p, p, p, p);
        tv.setBackgroundColor(role(carrier, com.google.android.material.R.attr
                .colorSurfaceContainerHighest));
        ScrollView scroll = new ScrollView(themed);
        scroll.addView(tv);
        return scroll;
    }

    private static TextView sectionLabel(Context themed, String text, int color) {
        TextView tv = new TextView(themed);
        tv.setText(text);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleSmall);
        tv.setTextColor(color);
        tv.setLetterSpacing(0.08f);
        return tv;
    }

    private static LinearLayout.LayoutParams topMargin(Context ctx, int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, dp);
        return lp;
    }

    private static LinearLayout.LayoutParams weighted(Context ctx, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        lp.weight = 1f;
        lp.topMargin = dp(ctx, topDp);
        return lp;
    }

    /** Resolves a theme role against a view that is already using the theme. */
    private static int role(View view, int attribute) {
        return MaterialColors.getColor(view, attribute);
    }

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}
