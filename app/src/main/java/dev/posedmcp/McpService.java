package dev.posedmcp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.ipc.BridgeCredentials;
import dev.posedmcp.ipc.BridgeServer;
import dev.posedmcp.mcp.McpServer;
import dev.posedmcp.mcp.McpTool;
import dev.posedmcp.mcp.ToolRegistry;
import dev.posedmcp.root.AuditNotifier;
import dev.posedmcp.root.ConfirmationGate;
import dev.posedmcp.state.DeviceStatus;
import dev.posedmcp.state.EventStore;
import dev.posedmcp.state.PeerTrust;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.state.SavedHook;
import dev.posedmcp.tools.Capabilities;
import dev.posedmcp.tools.HookDeploy;

/**
 * Keeps the MCP endpoint and the device bridge alive.
 *
 * <p>Owns the whole server-side object graph, so the app UI only ever starts
 * and stops this service rather than wiring components itself.
 */
public final class McpService extends Service {

    public static final String ACTION_START = "dev.posedmcp.action.START";
    public static final String ACTION_STOP = "dev.posedmcp.action.STOP";

    private static final String CHANNEL_ID = "posedmcp-service";
    private static final int NOTIFICATION_ID = 4100;
    /** How long a refusal is remembered, so a rejected app cannot spam prompts. */
    private static final long REFUSAL_MEMORY_MS = 10 * 60 * 1000L;
    /** How often the notification's hand-off countdown is refreshed. */
    private static final long HANDOFF_TICK_MS = 30_000L;

    private static volatile McpService instance;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final java.util.Map<String, Long> refusedPeers = new java.util.concurrent.ConcurrentHashMap<>();

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean handoffWasArmed;

    private final Runnable handoffTicker = new Runnable() {
        @Override
        public void run() {
            boolean armed = !ConfirmationGate.handoffLeft(McpService.this).isEmpty();
            if (armed != handoffWasArmed) {
                if (!armed) {
                    // The window closed on its own. This is the case the user is
                    // definitely not watching for, and therefore the one they most
                    // need telling about.
                    AuditNotifier.handoffEnded(McpService.this,
                            getString(R.string.handoff_reason_timeout));
                }
                handoffWasArmed = armed;
                updateNotification();
            } else if (armed) {
                // Keep the countdown in the notification roughly honest.
                updateNotification();
            }
            handler.postDelayed(this, HANDOFF_TICK_MS);
        }
    };

    private Prefs prefs;
    private EventStore events;
    private BridgeServer bridge;
    private McpServer mcp;
    private ToolRegistry tools;

    public static McpService instance() {
        return instance;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, McpService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, McpService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = Prefs.of(this);
        // The platform's hidden APIs are what the system-side features are built
        // on; without this, reflection over them silently reports nothing.
        HiddenApi.exempt();
        // Synchronously, before anything can read a token or start a listener.
        prefs.ensureTokens();
        // Hand-off mode is deliberately NOT cleared here. This ROM kills the app
        // on its own schedule - measured, its memory sweeper took both of this
        // app's processes out mid-session under "powersavemode(kill-res)" - and
        // the accessibility binding brought it straight back. Treating every
        // restart as the end of the session meant a routine sweep silently spent
        // the user's remaining minutes. A reboot is the thing that ends it, and
        // the gate notices that itself; all that is left to do here is say so.
        if (prefs.handoffUntil() != 0L && prefs.handoffCrossesReboot()) {
            Logx.i("hand-off mode cleared: the device has rebooted since it was armed");
            prefs.clearHandoff();
            handoffWasArmed = false;
            AuditNotifier.handoffEnded(this, getString(R.string.handoff_reason_reboot));
        }
        // Mirror the bridge credentials where hooked processes can reach them.
        BridgeCredentials.publish(this, prefs.bridgeToken(), prefs.bridgePort());
        ensureChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundNow();
        startServers();
        return START_STICKY;
    }

    private void startForegroundNow() {
        Notification notification = buildNotification(getString(R.string.notif_starting));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private synchronized void startServers() {
        if (started.get()) {
            updateNotification();
            return;
        }
        try {
            events = new EventStore();
            // Window transitions come from the accessibility service, which runs
            // in this process, so they can go straight into the feed.
            AccessibilityBridge.setEventSink((type, data) ->
                    events.add("a11y", type, data, System.currentTimeMillis()));
            bridge = new BridgeServer(prefs.bridgePort(), prefs.bridgeToken(), events,
                    this::trustPeer, this::onPeerReady);
            bridge.start();

            Capabilities capabilities = new Capabilities(this, bridge);
            tools = new ToolRegistry(this, prefs, capabilities, bridge, events);
            mcp = new McpServer(this, prefs, tools, events);
            mcp.start();

            started.set(true);
            // Runs 'su -c id' once, now, so no agent-triggered call can reach
            // root outside the confirmation gate.
            DeviceStatus.probeRootAsync();
            handler.removeCallbacks(handoffTicker);
            handoffWasArmed = false;
            handler.postDelayed(handoffTicker, HANDOFF_TICK_MS);

            Logx.i("service started: MCP on " + prefs.mcpPort() + ", bridge on " + prefs.bridgePort());
        } catch (Throwable t) {
            Logx.e("failed to start servers", t);
        }
        updateNotification();
    }

    private synchronized void shutdown() {
        started.set(false);
        handler.removeCallbacks(handoffTicker);
        try {
            if (mcp != null) {
                mcp.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (bridge != null) {
                bridge.stop();
            }
        } catch (Throwable ignored) {
        }
        mcp = null;
        bridge = null;
        tools = null;
        AccessibilityBridge.setEventSink(null);
        Logx.i("service stopped");
    }

    @Override
    public void onDestroy() {
        shutdown();
        instance = null;
        super.onDestroy();
    }

    /**
     * Decides whether an application process may use the bridge.
     *
     * <p>An application that hosts the module has no way to obtain the token -
     * Android blocks every out-of-band channel - so it asks over the connection
     * it already opened, and the user decides once per package. Later
     * connections are answered from that decision.
     *
     * <p>Refusals are remembered for a while so a rejected application cannot
     * turn itself into a popup generator by reconnecting in a loop.
     */
    private boolean trustPeer(String pkg) {
        PeerTrust trust = PeerTrust.of(this);
        if (trust.isApproved(pkg)) {
            return true;
        }
        Long refusedAt = refusedPeers.get(pkg);
        if (refusedAt != null && SystemClock.elapsedRealtime() - refusedAt < REFUSAL_MEMORY_MS) {
            return false;
        }

        ConfirmationGate.Decision decision = ConfirmationGate.request(this,
                new ConfirmationGate.Request(
                        ConfirmationGate.Kind.PEER,
                        getString(R.string.peer_confirm_title),
                        pkg + describeApp(pkg),
                        getString(R.string.peer_confirm_body),
                        getString(R.string.peer_approve_label), prefs.confirmTimeoutMs()));

        if (decision.approved) {
            trust.approve(pkg);
            refusedPeers.remove(pkg);
            return true;
        }
        refusedPeers.put(pkg, SystemClock.elapsedRealtime());
        return false;
    }

    private String describeApp(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            String label = String.valueOf(getPackageManager().getApplicationLabel(info));
            return label.equals(pkg) ? "" : " (" + label + ")";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Re-arms this package's saved hooks now that one of its processes is up.
     *
     * <p>Runs on the thread {@link BridgeServer} started for it, because it calls
     * back into the peer and the thread that accepted the connection is already
     * committed to reading from it.
     */
    private void onPeerReady(String pkg, String peerKey) {
        BridgeServer server = bridge;
        if (server == null) {
            return;
        }
        refreshFramework();
        HookDeploy.deploy(this, server, peerKey, pkg, moduleApk());
    }

    // ---- which framework is loaded ----

    /** What the last module instance said it was loaded by, and which process said it. */
    private volatile String frameworkReport = "";
    private volatile String frameworkFrom = "";

    /**
     * The framework a running module instance reported, or empty.
     *
     * <p>Asked of the module rather than worked out here, because this is the
     * only answer that is about what is actually injected. The app process has
     * neither Xposed API on its classpath - it cannot even see the module's own
     * classes - so anything it concluded on its own would be about what is
     * installed, not about what is running.
     */
    public String frameworkReport() {
        return frameworkReport;
    }

    /** The process that answered, so the answer can be told apart from a leftover. */
    public String frameworkFrom() {
        return frameworkFrom;
    }

    /**
     * Asks a connected module instance what loaded it.
     *
     * <p>Runs on whatever thread the caller is on: it does a bridge round trip,
     * so never the main one. Answers are not required - a handset with nothing
     * in scope yet simply keeps saying it does not know.
     */
    public void refreshFramework() {
        BridgeServer server = bridge;
        if (server == null) {
            return;
        }
        for (String key : server.connectedKeys()) {
            if (!key.startsWith(dev.posedmcp.ipc.Wire.ROLE_APP)) {
                continue;
            }
            try {
                org.json.JSONObject pong =
                        server.request(key, "ping", new org.json.JSONObject(), 4_000L);
                String reported = pong.optString("framework", "");
                if (!reported.isEmpty()) {
                    frameworkReport = reported;
                    frameworkFrom = key;
                    return;
                }
            } catch (Throwable ignored) {
                // A process mid-start has nothing to say; try the next one.
            }
        }
    }

    /**
     * Where the module APK lives, for the module to load its own native library
     * from inside someone else's process. Only this app can work it out: the
     * module's class loader gives no code source, and the PackageManager does not
     * show it an application it does not own.
     */
    public String moduleApk() {
        try {
            return getApplicationInfo().sourceDir;
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- status for the UI ------------------------------------------------

    public boolean isRunning() {
        return started.get() && mcp != null && mcp.isRunning();
    }

    public int mcpPort() {
        return prefs == null ? Prefs.DEFAULT_MCP_PORT : prefs.mcpPort();
    }

    public int bridgePort() {
        return prefs == null ? Prefs.DEFAULT_BRIDGE_PORT : prefs.bridgePort();
    }

    public int connectedPeers() {
        return bridge == null ? 0 : bridge.connectedKeys().size();
    }

    public boolean systemBridgeConnected() {
        return bridge != null && bridge.systemPeer() != null;
    }

    /**
     * Runs a saved script, because the user tapped Run on it in the automation
     * tab.     *
     * <p>That tap is the user making the decision themselves, so this goes
     * straight to the bridge instead of through the confirmation gate that the
     * agent's own calls use - asking again would be asking them twice.
     */
    public org.json.JSONObject runScript(String pkg, String source, long maxInstructions)
            throws Exception {
        ToolRegistry registry = tools;
        if (registry == null) {
            throw new IllegalStateException("the service is not running");
        }
        return registry.runScript(pkg, source, maxInstructions);
    }

    /**
     * Whether something reachable over the bridge can serve this package.
     *
     * <p>True for the system framework as well as for an app, which is what the
     * hook page asks before it offers to reach into a live process.
     */
    public boolean hasPeer(String pkg) {
        BridgeServer server = bridge;
        return server != null && server.hasPeer(pkg);
    }

    /**
     * Arms a saved hook in every live process of its app, because the user just
     * turned it on in the hook page.
     *
     * @return how many processes took it, or {@code null} if the service is down
     */
    public org.json.JSONObject armHook(SavedHook hook) {
        BridgeServer server = bridge;
        if (server == null) {
            return null;
        }
        return HookDeploy.applyToAllProcesses(this, server, hook, moduleApk());
    }

    /** Takes a hook out of every live process, because the user turned it off. */
    public void disarmHook(SavedHook hook) {
        BridgeServer server = bridge;
        if (server != null) {
            HookDeploy.clearFromAllProcesses(server, hook);
        }
    }

    // ---- hand-off mode ----------------------------------------------------

    /**
     * Arms hand-off mode for a duration; the user did the ceremony in the app.
     *
     * <p>Logged at warn level on purpose. This is the only event in the whole
     * system that removes the gate, and it should be findable in a log by
     * someone asking "what happened on this device at 14:20".
     */
    public void armHandoff(long durationMs) {
        if (prefs == null) {
            return;
        }
        prefs.setHandoffUntil(System.currentTimeMillis() + durationMs);
        Logx.w("HAND-OFF MODE ARMED for " + (durationMs / 60_000L) + " min - every tool now"
                + " runs without asking");
        handoffWasArmed = true;
        AuditNotifier.handoffArmed(this, durationMs);
        updateNotification();
    }

    /** Ends hand-off mode now, whatever time was left. */
    public void disarmHandoff() {
        if (prefs == null) {
            return;
        }
        prefs.clearHandoff();
        Logx.i("hand-off mode turned off");
        handoffWasArmed = false;
        AuditNotifier.handoffEnded(this, getString(R.string.handoff_reason_off));
        updateNotification();
    }

    /** Pushes the notification up to date after the UI changed something. */
    public void refreshNotification() {
        updateNotification();
    }

    /** The registered tools, so the status tab lists what actually exists. */
    public List<McpTool> tools() {
        ToolRegistry registry = tools;
        return registry == null ? Collections.emptyList() : registry.all();
    }

    // ---- notification -----------------------------------------------------

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_service_desc));
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, McpService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(status)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notif_action_stop), stop).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        String handoff = ConfirmationGate.handoffLeft(this);
        String status = "127.0.0.1:" + mcpPort() + " · "
                + getString(systemBridgeConnected()
                        ? R.string.notif_system_ok : R.string.notif_system_offline)
                + " · " + getResources().getQuantityString(R.plurals.notif_peers,
                        connectedPeers(), connectedPeers())
                + (handoff.isEmpty() ? "" : " · " + getString(R.string.notif_handoff, handoff));
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(status));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The user swiping the app away must not take the bridge down.
        updateNotification();
    }
}
