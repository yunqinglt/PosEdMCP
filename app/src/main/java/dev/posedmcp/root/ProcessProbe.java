package dev.posedmcp.root;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import dev.posedmcp.Logx;
import dev.posedmcp.McpService;
import dev.posedmcp.R;
import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.state.ProbeStore;
import dev.posedmcp.state.SavedHook;

/**
 * Freezes an application's processes with SIGSTOP and lets them go again.
 *
 * <p>The freeze itself is trivial; what makes it safe to offer is everything
 * around it. The state is committed to disk, because this process is killed on
 * the ROM's own schedule and a restarted process must still know who is frozen
 * and be able to release them. The release has a second path: a detached root
 * watchdog that sleeps until the deadline and sends SIGCONT itself, so the
 * target is freed even if this app never comes back. Both the watchdog and an
 * early release key on a marker file, so an early release invalidates a late
 * watchdog and a re-freeze invalidates the watchdog before it - the one thing
 * the design cannot do is kill a root process it did not stay root to control.
 *
 * <p>Every pid the watchdog and the resume path touch is checked against what
 * it was at freeze time ({@code /proc/pid/stat} field 22): if the pid has been
 * recycled, neither path may signal the new owner of that number.
 */
public final class ProcessProbe {

    /** The app that is frozen. */
    public static final class State {
        public final String pkg;
        public final int[] pids;
        /** {@code /proc/pid/stat} field 22 for each pid, captured at freeze time. */
        public final String[] starts;
        public final long frozenAt;
        public final long expiresAt;

        State(String pkg, int[] pids, String[] starts, long frozenAt, long expiresAt) {
            this.pkg = pkg;
            this.pids = pids;
            this.starts = starts;
            this.frozenAt = frozenAt;
            this.expiresAt = expiresAt;
        }

        public long remainingMs() {
            return expiresAt - System.currentTimeMillis();
        }
    }

    /** Where the freeze's deadline is left for the detached watchdog to check. */
    private static final String MARKER = "/data/local/tmp/posedmcp-probe";

    private static final Object LOCK = new Object();
    private static volatile State current;

    private ProcessProbe() {
    }

    /** The frozen app, or null; a freeze whose time has passed is cleared. */
    public static State current(Context ctx) {
        synchronized (LOCK) {
            State state = current;
            if (state == null) {
                state = load(ctx);
                current = state;
            }
            if (state != null && state.remainingMs() <= 0) {
                Logx.i("probe: freeze of " + state.pkg + " has expired; clearing");
                current = null;
                clear(ctx);
                return null;
            }
            return state;
        }
    }

    public static boolean isFrozen(Context ctx) {
        return current(ctx) != null;
    }

    /**
     * Why a freeze of this package may not proceed, as a string resource id;
     * null when it may.
     *
     * <p>The deny list is the boundary the user drew: freezing the system
     * interface or the launcher freezes the whole phone for no diagnostic
     * gain, and freezing this app would kill the one thing that can release
     * it. Overlay and accessibility are the two permissions the floating
     * button itself depends on - without the second, this app is frozen by the
     * ROM as soon as it leaves the screen, and a button that goes silent is
     * the worst failure shape this project knows.
     */
    public static Integer guardError(Context ctx, String pkg) {
        if (!Settings.canDrawOverlays(ctx)) {
            return R.string.toast_probe_refused_overlay;
        }
        if (!AccessibilityBridge.isConnected()) {
            return R.string.toast_probe_refused_a11y;
        }
        if (current(ctx) != null) {
            return R.string.toast_probe_refused_busy;
        }
        if (pkg == null || pkg.isEmpty() || !pkg.matches("[A-Za-z0-9._]+")) {
            return R.string.toast_probe_refused_no_foreground;
        }
        if (pkg.equals(ctx.getPackageName())) {
            return R.string.toast_probe_refused_self;
        }
        if (SavedHook.SYSTEM_PACKAGE.equals(pkg) || "com.android.systemui".equals(pkg)
                || pkg.equals(homePackage(ctx))) {
            return R.string.toast_probe_refused_system;
        }
        return null;
    }

    private static String homePackage(Context ctx) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            return ctx.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                    .activityInfo.packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * SIGSTOPs every process of the package and arms the detached watchdog.
     *
     * <p>The marker goes down before the signal: if this process dies between
     * the two, a watchdog armed against a marker that is not there would
     * release nothing, and nothing was frozen anyway.
     */
    public static State freeze(Context ctx, String pkg, long durationMs) throws IOException {
        Integer guard = guardError(ctx, pkg);
        if (guard != null) {
            throw new IOException(ctx.getString(guard, pkg));
        }
        synchronized (LOCK) {
            if (current != null) {
                throw new IOException(ctx.getString(R.string.toast_probe_refused_busy, pkg));
            }
            List<long[]> processes = listProcesses(pkg);
            if (processes.isEmpty()) {
                throw new IOException(ctx.getString(R.string.toast_probe_refused_no_process, pkg));
            }

            int[] pids = new int[processes.size()];
            String[] starts = new String[processes.size()];
            StringBuilder stop = new StringBuilder("kill -STOP");
            for (int i = 0; i < processes.size(); i++) {
                pids[i] = (int) processes.get(i)[0];
                starts[i] = String.valueOf(processes.get(i)[1]);
                stop.append(' ').append(pids[i]);
            }

            long now = System.currentTimeMillis();
            State state = new State(pkg, pids, starts, now, now + durationMs);

            // The scene is captured before the freeze: a stopped process does
            // not handle signals or answer bridges, and the whole point of the
            // freeze is what the scene was. Best-effort - a lost snapshot must
            // never lose the freeze itself.
            //
            // Two stack sources, in order of preference. The in-process
            // snapshot (the module inside a scoped app reads its own threads)
            // is fast and works where SIGQUIT is swallowed; the ANR trace is
            // the fallback for apps the module is not inside. An app already
            // known to swallow SIGQUIT skips the doomed trace wait entirely.
            JSONObject snapshot = null;
            McpService service = McpService.instance();
            boolean peer = service != null && service.hasPeer(pkg);
            ProbeStore store = ProbeStore.instance();
            JSONObject latest = store == null ? null : store.latest(pkg);
            boolean swallow = latest != null && latest.has("captureError");
            if (peer) {
                snapshot = service.probeSnapshot(pkg, 3_000L);
            }
            if (snapshot == null && !swallow) {
                snapshot = ProbeSnapshot.capture(ctx, pkg, pids[0]);
            }
            String captureError = ProbeSnapshot.lastError();
            if (snapshot == null) {
                try {
                    snapshot = new JSONObject();
                    snapshot.put("captureError", captureError != null && !captureError.isEmpty()
                            ? captureError : "the process did not answer the snapshot request");
                    snapshot.put("capturedAt", System.currentTimeMillis());
                } catch (Throwable ignored) {
                    snapshot = null;
                }
            }
            // App-side additions while the target is still responsive: the
            // view tree cannot be read from a frozen app by any tool, so it
            // has to be taken now, and the activity labels the scene.
            if (snapshot != null) {
                try {
                    String raw = service == null ? null : service.foregroundRaw();
                    if (raw != null && raw.startsWith(pkg)) {
                        snapshot.put("activity", raw);
                    }
                } catch (Throwable ignored) {
                }
                try {
                    JSONObject ui = AccessibilityBridge.activeWindowTree(120, true);
                    if (ui != null) {
                        snapshot.put("ui", ui);
                    }
                } catch (Throwable t) {
                    Logx.w("probe: view tree capture failed: " + t);
                }
                try {
                    snapshot.put("foreignPackages",
                            ProbeSnapshot.foreignPackages(pkg, snapshot.optJSONArray("threads")));
                } catch (Throwable ignored) {
                }
                try {
                    ProbeSnapshot.annotateThreads(pkg, snapshot.optJSONArray("threads"));
                } catch (Throwable ignored) {
                }
                // The mapped shared objects survive obfuscation and cost one
                // root read; they are worth having even when the stacks are
                // not, so this runs regardless of how the snapshot fared.
                try {
                    JSONArray libs = ProbeSnapshot.libraries(pids[0]);
                    if (libs != null) {
                        snapshot.put("libraries", libs);
                        snapshot.put("sdkHints", ProbeSnapshot.sdkHints(libs));
                    }
                } catch (Throwable t) {
                    Logx.w("probe: library capture failed: " + t);
                }
            }

            // A refused marker write aborts the whole freeze: without the
            // marker the watchdog cannot vouch for its own deadline, and a
            // freeze whose only escape is a person who might not be there is
            // not a freeze this app is allowed to make. A pid that vanished
            // between the listing and the signal is tolerated - resume checks
            // each pid's state, so a dead one is a no-op.
            RootShell.Result stopped = RootShell.exec(
                    "echo " + state.expiresAt + " > " + MARKER + " || exit 1; "
                            + stop + " 2>/dev/null; exit 0",
                    10_000L);
            if (!stopped.ok()) {
                throw new IOException(ctx.getString(R.string.toast_probe_freeze_failed,
                        firstLine(stopped.stderr)));
            }

            armWatchdog(state);
            save(ctx, state);
            current = state;
            if (snapshot != null) {
                try {
                    if (store != null) {
                        store.add(pkg, snapshot);
                    }
                } catch (Throwable t) {
                    Logx.w("probe: could not file the snapshot: " + t);
                }
            }
            Logx.i("probe: frozen " + pkg + " (" + pids.length + " pid(s)) for "
                    + (durationMs / 1000L) + "s, watchdog armed");
            return state;
        }
    }

    /**
     * Releases the frozen app, however it got frozen: the button, the agent,
     * or the deadline passing.
     *
     * <p>CONT comes before the marker is removed: if this process dies between
     * the two, the still-armed watchdog fires later and signals an already
     * running process - harmless, and the opposite order would leave the app
     * frozen with nobody left to release it.
     */
    public static void resume(Context ctx) throws IOException {
        State state;
        synchronized (LOCK) {
            state = current(ctx);
            if (state == null) {
                return;
            }
            RootShell.Result resumed = RootShell.exec(resumeScript(state), 10_000L);
            if (!resumed.ok()) {
                Logx.w("probe: resume command reported: " + firstLine(resumed.stderr));
            }
            current = null;
            clear(ctx);
        }
        Logx.i("probe: resumed " + state.pkg);
    }

    /**
     * Releases the frozen app if - and only if - it is this package, in one
     * critical section so a re-freeze started between the check and the
     * release cannot be released by mistake. This is the agent's path:
     * {@code probe_resume} takes a package name and nothing else, and the
     * shell it runs is built entirely from the record this app wrote when it
     * froze that package.
     *
     * @return true when this package was the one frozen and has been released
     */
    public static boolean resumeIf(Context ctx, String pkg) throws IOException {
        synchronized (LOCK) {
            State state = current(ctx);
            if (state == null || !state.pkg.equals(pkg)) {
                return false;
            }
            RootShell.Result resumed = RootShell.exec(resumeScript(state), 10_000L);
            if (!resumed.ok()) {
                Logx.w("probe: resume command reported: " + firstLine(resumed.stderr));
            }
            current = null;
            clear(ctx);
            Logx.i("probe: resumed " + state.pkg + " (agent)");
            return true;
        }
    }

    /** CONTs each pid only while it is still stopped, then takes the marker down. */
    private static String resumeScript(State state) {
        StringBuilder script = new StringBuilder();
        for (int i = 0; i < state.pids.length; i++) {
            script.append("[ \"$(cut -d' ' -f3 /proc/").append(state.pids[i])
                    .append("/stat 2>/dev/null)\" = \"T\" ] && kill -CONT ")
                    .append(state.pids[i]).append("; ");
        }
        script.append("rm -f ").append(MARKER).append("; exit 0");
        return script.toString();
    }

    /**
     * The second release path. A detached root shell: it is not this app's
     * process, so the ROM killing us does not touch it, and it releases the
     * target at the deadline on its own.
     */
    private static void armWatchdog(State state) throws IOException {
        long secs = Math.max(1L, state.remainingMs() / 1000L);
        StringBuilder script = new StringBuilder("sleep ").append(secs)
                .append("; [ \"$(cat ").append(MARKER).append(" 2>/dev/null)\" = \"")
                .append(state.expiresAt).append("\" ] || exit 0");
        for (int i = 0; i < state.pids.length; i++) {
            script.append("; [ \"$(cut -d' ' -f22 /proc/").append(state.pids[i])
                    .append("/stat 2>/dev/null)\" = \"").append(state.starts[i])
                    .append("\" ] && kill -CONT ").append(state.pids[i]);
        }
        ProcessBuilder pb = new ProcessBuilder(RootShell.suPath(), "-c", script.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(new java.io.File("/dev/null"));
        pb.start();
    }

    private static List<long[]> listProcesses(String pkg) throws IOException {
        // Every process whose name is the package or a "pkg:suffix" of it, with
        // its start time - the one fingerprint that survives pid recycling.
        String cmd = "ps -A -o PID,NAME | awk -v P=\"" + pkg + "\""
                + " '$2==P || index($2,P\":\")==1 {print $1}'"
                + " | while read p; do s=$(cut -d' ' -f22 /proc/$p/stat 2>/dev/null);"
                + " [ -n \"$s\" ] && echo \"$p $s\"; done";
        RootShell.Result r = RootShell.exec(cmd, 10_000L);
        if (!r.ok()) {
            throw new IOException("could not list processes: " + firstLine(r.stderr));
        }
        List<long[]> out = new ArrayList<>();
        for (String line : r.stdout.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2) {
                try {
                    out.add(new long[]{Long.parseLong(parts[0]), Long.parseLong(parts[1])});
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int cut = text.indexOf('\n');
        String line = cut < 0 ? text : text.substring(0, cut);
        return line.trim().isEmpty() ? text.trim() : line.trim();
    }

    private static State load(Context ctx) {
        String json = Prefs.of(ctx).probeStateJson();
        if (json == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(json);
            JSONArray pidsA = o.getJSONArray("pids");
            JSONArray startsA = o.getJSONArray("starts");
            int n = Math.min(pidsA.length(), startsA.length());
            int[] pids = new int[n];
            String[] starts = new String[n];
            for (int i = 0; i < n; i++) {
                pids[i] = pidsA.getInt(i);
                starts[i] = startsA.getString(i);
            }
            return new State(o.getString("pkg"), pids, starts,
                    o.getLong("frozenAt"), o.getLong("expiresAt"));
        } catch (Throwable t) {
            Logx.w("probe: unreadable persisted state, clearing: " + t);
            clear(ctx);
            return null;
        }
    }

    private static void save(Context ctx, State state) {
        try {
            JSONObject o = new JSONObject();
            JSONArray pidsA = new JSONArray();
            JSONArray startsA = new JSONArray();
            for (int i = 0; i < state.pids.length; i++) {
                pidsA.put(state.pids[i]);
                startsA.put(state.starts[i]);
            }
            o.put("pkg", state.pkg);
            o.put("pids", pidsA);
            o.put("starts", startsA);
            o.put("frozenAt", state.frozenAt);
            o.put("expiresAt", state.expiresAt);
            Prefs.of(ctx).setProbeStateJson(o.toString());
        } catch (Throwable t) {
            Logx.e("probe: could not persist the frozen state", t);
        }
    }

    private static void clear(Context ctx) {
        try {
            Prefs.of(ctx).clearProbeState();
        } catch (Throwable t) {
            Logx.w("probe: could not clear the persisted state: " + t);
        }
    }
}
