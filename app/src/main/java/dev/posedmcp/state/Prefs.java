package dev.posedmcp.state;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * Settings for the MCP server and for the confirmation policy.
 *
 * <p>Only the app process uses this. The LSPosed module code running inside
 * system_server and inside scoped apps reads the bridge token straight out of
 * this same file (see {@code BridgeAuth}).
 */
public final class Prefs {

    public static final String FILE = "posedmcp";

    private static final String KEY_MCP_PORT = "mcp_port";
    private static final String KEY_BRIDGE_PORT = "bridge_port";
    private static final String KEY_MCP_TOKEN = "mcp_token";
    private static final String KEY_BRIDGE_TOKEN = "bridge_token";
    private static final String KEY_CONFIRM_SCREEN = "confirm_screen";
    private static final String KEY_CONFIRM_INPUT = "confirm_input";
    private static final String KEY_CONFIRM_PLUGIN = "confirm_plugin";
    private static final String KEY_CONFIRM_TIMEOUT = "confirm_timeout_ms";
    private static final String KEY_EXEC_TIMEOUT = "exec_timeout_ms";
    private static final String KEY_AUTOSTART = "autostart";
    private static final String KEY_HANDOFF_UNTIL = "handoff_until";
    private static final String KEY_HANDOFF_BOOT = "handoff_boot";
    private static final String KEY_PROBE_WINDOW = "probe_window";
    private static final String KEY_PROBE_FREEZE_SECONDS = "probe_freeze_seconds";
    private static final String KEY_PROBE_STATE = "probe_state";

    /** How far the boot wall-clock has to move before it counts as a reboot. */
    private static final long BOOT_SLACK_MS = 120_000L;

    public static final int DEFAULT_MCP_PORT = 8765;
    public static final int DEFAULT_BRIDGE_PORT = 8766;

    /** How long hand-off mode lasts when it is armed. */
    public static final long HANDOFF_DEFAULT_MS = 15 * 60 * 1000L;
    /** How much each "extend" adds. */
    public static final long HANDOFF_EXTEND_MS = 5 * 60 * 1000L;

    private final SharedPreferences sp;

    private Prefs(SharedPreferences sp) {
        this.sp = sp;
    }

    public static Prefs of(Context ctx) {
        return new Prefs(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    private static String randomToken() {
        byte[] buf = new byte[32];
        new SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Creates both tokens if they do not exist yet, and waits for the write.
     *
     * <p>This has to be synchronous and has to happen away from any getter.
     * The LSPosed module reads these tokens out of this file from other
     * processes, so a token that exists only in this process's memory — which is
     * exactly what {@code apply()} can leave behind if the process dies — would
     * lock the bridge out of its own server.
     */
    public void ensureTokens() {
        SharedPreferences.Editor editor = sp.edit();
        boolean changed = false;
        if (sp.getString(KEY_MCP_TOKEN, null) == null) {
            editor.putString(KEY_MCP_TOKEN, randomToken());
            changed = true;
        }
        if (sp.getString(KEY_BRIDGE_TOKEN, null) == null) {
            editor.putString(KEY_BRIDGE_TOKEN, randomToken());
            changed = true;
        }
        if (changed) {
            editor.commit();
        }
    }

    /** Token MCP clients must present. Empty until {@link #ensureTokens()} has run. */
    public String mcpToken() {
        return sp.getString(KEY_MCP_TOKEN, "");
    }

    /** Token in-process bridge clients must present. */
    public String bridgeToken() {
        return sp.getString(KEY_BRIDGE_TOKEN, "");
    }

    public int mcpPort() {
        return sp.getInt(KEY_MCP_PORT, DEFAULT_MCP_PORT);
    }

    public void setMcpPort(int port) {
        sp.edit().putInt(KEY_MCP_PORT, port).apply();
    }

    public int bridgePort() {
        return sp.getInt(KEY_BRIDGE_PORT, DEFAULT_BRIDGE_PORT);
    }

    public void setBridgePort(int port) {
        sp.edit().putInt(KEY_BRIDGE_PORT, port).apply();
    }

    /** Screen capture and UI dumps expose whatever is on screen. */
    public boolean confirmScreen() {
        return sp.getBoolean(KEY_CONFIRM_SCREEN, true);
    }

    /** Synthetic input can drive any app, so it is gated by default. */
    public boolean confirmInput() {
        return sp.getBoolean(KEY_CONFIRM_INPUT, true);
    }

    /** Loading code into another app's process. */
    public boolean confirmPlugin() {
        return sp.getBoolean(KEY_CONFIRM_PLUGIN, true);
    }

    public void setConfirm(String key, boolean value) {
        sp.edit().putBoolean(key, value).apply();
    }

    public long confirmTimeoutMs() {
        return sp.getLong(KEY_CONFIRM_TIMEOUT, 120_000L);
    }

    public void setConfirmTimeoutMs(long ms) {
        sp.edit().putLong(KEY_CONFIRM_TIMEOUT, ms).apply();
    }

    public long execTimeoutMs() {
        return sp.getLong(KEY_EXEC_TIMEOUT, 60_000L);
    }

    // ---- hand-off mode ----------------------------------------------------

    /**
     * When hand-off mode lapses, as a wall-clock time, or 0 when it is off.
     *
     * <p>Wall clock rather than {@code elapsedRealtime}: that counter restarts
     * at zero on every boot, so a deadline saved as "5 minutes after boot"
     * would, after a reboot, sit thousands of seconds in the future and quietly
     * re-arm a mode the user thought had been cleared. Hand-off mode is the one
     * setting where that matters, so it is the one that avoids the trap.
     *
     * <p>Only this app process reads it: the gate lives here, not in the module.
     */
    public long handoffUntil() {
        return sp.getLong(KEY_HANDOFF_UNTIL, 0L);
    }

    /**
     * Arms hand-off mode until the deadline.
     *
     * <p>{@code commit()} rather than {@code apply()}: the very next tool call
     * reads this back, and a value still sitting in a background write queue is
     * a value the gate would not see.
     */
    public void setHandoffUntil(long epochMillis) {
        sp.edit()
                .putLong(KEY_HANDOFF_UNTIL, epochMillis)
                .putLong(KEY_HANDOFF_BOOT, bootWallClock())
                .commit();
    }

    /** Ends hand-off mode now. */
    public void clearHandoff() {
        sp.edit().remove(KEY_HANDOFF_UNTIL).remove(KEY_HANDOFF_BOOT).commit();
    }

    /** Milliseconds of hand-off mode left; 0 or less when it is off or lapsed. */
    public long handoffRemainingMs() {
        long until = handoffUntil();
        return until <= 0L ? 0L : until - System.currentTimeMillis();
    }

    /**
     * Whether the device has rebooted since hand-off was armed.
     *
     * <p>This is the whole reason the boot time is written down. A wall-clock
     * deadline survives the process being killed and restarted, which on this
     * ROM is routine — its own memory sweeper takes this app out mid-session and
     * the accessibility binding has it back a second later. Treating that as the
     * end of the session meant a routine sweep silently spent the user's
     * remaining minutes, so a process restart now changes nothing and a reboot
     * ends the window, which is what the two actually mean.
     */
    public boolean handoffCrossesReboot() {
        long armedAt = sp.getLong(KEY_HANDOFF_BOOT, 0L);
        return armedAt == 0L || Math.abs(bootWallClock() - armedAt) > BOOT_SLACK_MS;
    }

    /** Wall-clock time this device booted, as far as this process can tell. */
    private static long bootWallClock() {
        return System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
    }

    public boolean autostart() {
        return sp.getBoolean(KEY_AUTOSTART, true);
    }

    public void setAutostart(boolean value) {
        sp.edit().putBoolean(KEY_AUTOSTART, value).apply();
    }

    /**
     * Rotates both tokens. Existing clients are cut off, which is the point:
     * the MCP endpoint has no other authentication. Committed synchronously so
     * the module's copy cannot go stale.
     */
    public void rotateTokens() {
        sp.edit()
                .putString(KEY_MCP_TOKEN, randomToken())
                .putString(KEY_BRIDGE_TOKEN, randomToken())
                .commit();
    }

    // ---- the manual probe ------------------------------------------------

    /** How long one probe freeze lasts when nothing releases it earlier. */
    public static final int DEFAULT_PROBE_FREEZE_SECONDS = 60;
    /** The durations offered in Special settings. */
    public static final int[] PROBE_FREEZE_CHOICES = {30, 60, 300};

    /** Whether the floating probe button is on screen. */
    public boolean probeWindowEnabled() {
        return sp.getBoolean(KEY_PROBE_WINDOW, false);
    }

    public void setProbeWindowEnabled(boolean value) {
        sp.edit().putBoolean(KEY_PROBE_WINDOW, value).apply();
    }

    public int probeFreezeSeconds() {
        return sp.getInt(KEY_PROBE_FREEZE_SECONDS, DEFAULT_PROBE_FREEZE_SECONDS);
    }

    public void setProbeFreezeSeconds(int seconds) {
        sp.edit().putInt(KEY_PROBE_FREEZE_SECONDS, seconds).apply();
    }

    /**
     * The frozen-app record, or null. Committed synchronously: the record has
     * to survive this process being killed - the watchdog releases the app at
     * the deadline either way, but only this record lets a restarted process
     * say who is frozen and release them early.
     */
    public String probeStateJson() {
        return sp.getString(KEY_PROBE_STATE, null);
    }

    public void setProbeStateJson(String json) {
        sp.edit().putString(KEY_PROBE_STATE, json).commit();
    }

    public void clearProbeState() {
        sp.edit().remove(KEY_PROBE_STATE).commit();
    }
}
