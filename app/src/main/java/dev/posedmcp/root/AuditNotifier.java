package dev.posedmcp.root;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import dev.posedmcp.Logx;
import dev.posedmcp.MainActivity;
import dev.posedmcp.R;

/**
 * The audit trail, where the user will actually see it.
 *
 * <p>The log line has always been written; this is the part that reaches a
 * person. It exists because of hand-off mode: with the gate open there is no
 * dialog at all, so a banner is the only thing that tells the user what is
 * being done to their phone while they are not looking at it.
 *
 * <p>That is also why it only speaks during hand-off. The rest of the time the
 * approval dialog is already the report - it names the command and waits - and
 * a banner on top of it would be the same news twice, at the cost of a buzz.
 *
 * <p>Rate-limited, because an agent working through a sequence calls tools in
 * bursts and a phone that vibrates forty times is a phone that gets muted. Only
 * one action in every {@link #MIN_ALERT_INTERVAL_MS} is allowed to interrupt;
 * the ones in between still update the notification, silently, so the shade
 * always shows the latest and the skipped count is folded into the text. A
 * burst that ends inside one window therefore still leaves a correct record -
 * dropping them outright would let "one thing happened" stand for twenty.
 *
 * <p>The on/off banners use their own id and are never rate-limited: losing one
 * of those to a burst would hide exactly the state change that matters most.
 */
public final class AuditNotifier {

    private static final String CHANNEL_ID = "posedmcp-audit";
    private static final int HANDOFF_ID = 42000;
    private static final int ACTION_ID = 42001;

    /** At most one action banner this often; the rest fold into the next one. */
    private static final long MIN_ALERT_INTERVAL_MS = 12_000L;

    private static long lastAlertAt;
    private static int suppressed;

    private AuditNotifier() {
    }

    /** Hand-off mode was just armed by the user, in the app. */
    public static synchronized void handoffArmed(Context ctx, long durationMs) {
        long minutes = Math.max(1L, durationMs / 60_000L);
        post(ctx, HANDOFF_ID, ctx.getString(R.string.audit_handoff_on_title),
                ctx.getString(R.string.audit_handoff_on_body, minutes),
                true);
        // A fresh window starts a fresh burst count.
        lastAlertAt = 0L;
        suppressed = 0;
    }

    /** Hand-off mode ended, however it ended. */
    public static synchronized void handoffEnded(Context ctx, String why) {
        String suffix = why == null || why.isEmpty()
                ? "" : ctx.getString(R.string.audit_handoff_off_why, why);
        post(ctx, HANDOFF_ID, ctx.getString(R.string.audit_handoff_off_title),
                ctx.getString(R.string.audit_handoff_off_body, suffix),
                true);
        suppressed = 0;
    }

    /**
     * One gated action the agent took while the gate was open.
     *
     * <p>Only called for tools that would otherwise have prompted. A read-only
     * call changes nothing, and the agent makes them constantly - reporting
     * those would drown the ones that matter.
     */
    public static synchronized void action(Context ctx, String tool, String detail) {
        String flat = detail == null ? "" : detail.replace('\n', ' ').trim();
        if (flat.length() > 120) {
            flat = flat.substring(0, 120) + "...";
        }

        long now = SystemClock.elapsedRealtime();
        boolean alert = now - lastAlertAt >= MIN_ALERT_INTERVAL_MS;

        String text = flat.isEmpty() ? String.valueOf(tool) : flat;
        if (suppressed > 0) {
            text = text + "\n" + ctx.getString(R.string.audit_more_folded, suppressed);
        }
        if (alert) {
            lastAlertAt = now;
            suppressed = 0;
        } else {
            suppressed++;
        }
        post(ctx, ACTION_ID, ctx.getString(R.string.audit_action, tool), text, alert);
    }

    private static void post(Context ctx, int id, String title, String text, boolean alert) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) {
                return;
            }
            ensureChannel(nm, ctx);

            PendingIntent open = PendingIntent.getActivity(ctx, id,
                    new Intent(ctx, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder builder = new Notification.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    // Clears itself out of the shade; this is news, not state.
                    .setTimeoutAfter(180_000L);
            if (!alert) {
                builder.setOnlyAlertOnce(true);
            }
            nm.notify(id, builder.build());
        } catch (Throwable t) {
            // A notification that cannot be posted must never break the action
            // it was reporting.
            Logx.w("could not post the audit notification: " + t);
        }
    }

    private static void ensureChannel(NotificationManager nm, Context ctx) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                ctx.getString(R.string.audit_channel_handoff), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(ctx.getString(R.string.audit_channel_handoff_desc));
        channel.setBypassDnd(true);
        nm.createNotificationChannel(channel);
    }
}
