package dev.deploy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.content.pm.ServiceInfo;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.deploy.mcp.DeployMcpServer;

/**
 * Keeps Deploy's MCP endpoint alive.
 *
 * <p>The analogue of PosEdMCP's {@code McpService}, but non-root and read-only:
 * it hosts a {@code DeployMcpServer} on loopback, protected by a bearer token.
 * Deploy has no accessibility service to keep it resident the way PosEdMCP
 * does, so on these ROMs it is expected to be killed and relaunched by PosEdMCP
 * (same-signature, so a bind from it restarts this service) - which is why the
 * server carries no state and the token survives in preferences.
 */
public final class DeployMcpService extends Service {

    public static final String ACTION_START = "dev.deploy.action.START";
    public static final String ACTION_STOP = "dev.deploy.action.STOP";
    public static final int DEFAULT_PORT = 4177;

    private static final String CHANNEL_ID = "deploy-service";
    private static final int NOTIFICATION_ID = 4177;

    private static volatile DeployMcpService instance;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private DeployMcpServer server;

    public static DeployMcpService instance() {
        return instance;
    }

    public static void start(Context context) {
        context.startForegroundService(
                new Intent(context, DeployMcpService.class).setAction(ACTION_START));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, DeployMcpService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        ensureChannel();
        ensureToken();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopServer();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(statusLine()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        startServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        instance = null;
        super.onDestroy();
    }

    private synchronized void startServer() {
        if (started.get()) {
            return;
        }
        server = new DeployMcpServer(port(), token());
        try {
            server.start();
            started.set(true);
        } catch (Throwable t) {
            server = null;
        }
    }

    private synchronized void stopServer() {
        started.set(false);
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public boolean isRunning() {
        return started.get() && server != null && server.isRunning();
    }

    public int port() {
        return prefs().getInt("port", DEFAULT_PORT);
    }

    public String token() {
        return prefs().getString("token", "");
    }

    public String endpoint() {
        return "http://127.0.0.1:" + port() + "/mcp";
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("deploy", MODE_PRIVATE);
    }

    private void ensureToken() {
        SharedPreferences p = prefs();
        if (p.getString("token", "").isEmpty()) {
            p.edit().putString("token", UUID.randomUUID().toString().replace("-", "")).apply();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        // This service is started, not bound; binding goes to DeployAidlService.
        return null;
    }

    // ---- notification -----------------------------------------------------

    private String statusLine() {
        return isRunning() ? endpoint() : "starting…";
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, DeployActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}
