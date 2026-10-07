package dev.posedmcp.root;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.posedmcp.Logx;

/**
 * Captures and parses the Java thread stacks of the app being frozen.
 *
 * <p>SIGQUIT makes ART dump every thread with its Java stack into
 * {@code /data/anr/trace_NN} - even for a non-debuggable third-party app on a
 * user build, which is why none of this needs JDWP or the module's scope.
 * The dump does not go to logcat, so the file is found by polling the anr
 * directory for a new trace whose header names our pid, and read back through
 * the root shell. This must run before the SIGSTOP: a stopped process does
 * not handle SIGQUIT.
 *
 * <p>The parse is deliberately plain text kept plain: the model reads the
 * frames as they are ("com.foo.Bar.baz(Bar.java:12)"), and the one piece of
 * work done here - counting which foreign packages own the most frames - is
 * exactly the kind of thing that costs this app one pass but the model a
 * token per line.
 */
public final class ProbeSnapshot {

    private static final int MAX_THREADS = 60;
    private static final int MAX_FRAMES = 25;
    private static final int MAX_FRAME_CHARS = 200;
    private static final int TRACE_WAIT_ROUNDS = 20;

    /** Why the last capture failed, for probe_state to report honestly. */
    private static volatile String lastError;

    private static final String[] PLATFORM_PREFIXES = {
            "java.", "javax.", "android.", "dalvik.", "sun.", "jdk.", "libcore.",
            "com.android.", "org.apache.", "kotlin.", "kotlinx.", "okhttp3.", "io.reactivex.",
    };

    private ProbeSnapshot() {
    }

    /** The reason the last capture returned null, or null when it succeeded. */
    public static String lastError() {
        return lastError;
    }

    /** The parsed snapshot, or null when the dump could not be read. */
    public static JSONObject capture(Context ctx, String pkg, int pid) {
        long started = System.currentTimeMillis();
        try {
            RootShell.Result quit = RootShell.exec("kill -3 " + pid, 5_000L);
            if (!quit.ok()) {
                lastError = "SIGQUIT was refused (" + quit.stderr.trim() + ")";
                Logx.w("probe: SIGQUIT to " + pid + " reported: " + quit.stderr.trim());
                return null;
            }
            String path = findTrace(pid);
            if (path == null) {
                lastError = "the process does not answer SIGQUIT, so ART never wrote a trace";
                Logx.w("probe: no /data/anr trace for pid " + pid + " appeared");
                return null;
            }
            RootShell.Result cat = RootShell.exec("cat " + path, 10_000L);
            if (!cat.ok()) {
                lastError = "the trace could not be read (" + cat.stderr.trim() + ")";
                Logx.w("probe: could not read " + path + ": " + cat.stderr.trim());
                return null;
            }
            JSONObject snapshot = parse(pkg, pid, cat.stdout);
            Logx.i("probe: trace read: path=" + path + " bytes=" + cat.stdout.length());
            snapshot.put("capturedAt", System.currentTimeMillis());
            lastError = null;
            Logx.i("probe: snapshot captured for " + pkg + " in "
                    + (System.currentTimeMillis() - started) + "ms ("
                    + snapshot.optJSONArray("threads").length() + " threads)");
            return snapshot;
        } catch (Throwable t) {
            // Evidence is best-effort; the freeze must never fail over it.
            lastError = String.valueOf(t);
            Logx.w("probe: snapshot capture failed: " + t);
            return null;
        }
    }

    /**
     * Waits, in one shell on the device, for the trace this SIGQUIT just
     * produced. The round trip of a su call costs more than the wait itself,
     * so the loop lives there rather than here; the mtime guard keeps a stale
     * trace from an earlier dump of the same pid from being read as fresh.
     *
     * <p>The end marker is part of the match, not an afterthought: the header
     * is the first thing ART writes, so a trace matched on the header alone is
     * still being written - reading it then yields the pid block and no
     * threads, which is the one snapshot worse than none.
     */
    private static String findTrace(int pid) {
        long before = System.currentTimeMillis() / 1000L;
        String cmd = "for i in $(seq 1 " + TRACE_WAIT_ROUNDS + "); do"
                + " f=$(ls -t /data/anr/ 2>/dev/null | grep -m1 trace_);"
                + " [ -n \"$f\" ] && [ \"$(stat -c %Y /data/anr/$f)\" -ge " + before + " ]"
                + " && head -3 /data/anr/$f 2>/dev/null | grep -q \"pid " + pid + " \""
                + " && grep -qe \"----- end " + pid + " -----\" /data/anr/$f"
                + " && echo /data/anr/$f && break;"
                + " sleep 0.1;"
                + " done";
        RootShell.Result r = RootShell.exec(cmd, 8_000L);
        String path = r.stdout.trim();
        return path.isEmpty() ? null : path;
    }

    /** Turns the raw ANR text into the JSON {@code probe_state} serves. */
    static JSONObject parse(String pkg, int pid, String text) {
        JSONObject out = new JSONObject();
        try {
            out.put("pid", pid);
        } catch (Throwable ignored) {
        }
        JSONArray threads = new JSONArray();
        Map<String, int[]> foreign = new LinkedHashMap<>();

        JSONObject thread = null;
        JSONArray stack = null;
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.startsWith("\"")) {
                // "name" prio=5 tid=7 State
                int close = line.indexOf('"', 1);
                if (close < 0) {
                    continue;
                }
                int tidAt = line.indexOf(" tid=");
                if (tidAt < 0) {
                    continue;
                }
                int tidStart = tidAt + 5;
                int stateEnd = line.indexOf(' ', tidStart);
                if (stateEnd < 0) {
                    stateEnd = line.length();
                }
                try {
                    thread = new JSONObject();
                    thread.put("name", line.substring(1, close));
                    thread.put("tid", Integer.parseInt(
                            line.substring(tidStart, stateEnd).trim()));
                    thread.put("state", line.substring(stateEnd).trim());
                    stack = new JSONArray();
                    thread.put("stack", stack);
                    threads.put(thread);
                    if (threads.length() > MAX_THREADS) {
                        threads.remove(threads.length() - 1);
                        thread = null;
                        stack = null;
                        break;
                    }
                } catch (Throwable t) {
                    thread = null;
                    stack = null;
                }
                continue;
            }
            if (thread == null || stack == null || !line.startsWith("  at ")) {
                continue;
            }
            if (stack.length() >= MAX_FRAMES) {
                continue;
            }
            String frame = line.substring(5).trim();
            if (frame.length() > MAX_FRAME_CHARS) {
                frame = frame.substring(0, MAX_FRAME_CHARS);
            }
            try {
                stack.put(frame);
                countForeign(foreign, pkg, frame);
            } catch (Throwable ignored) {
            }
        }

        try {
            out.put("threads", threads);
            out.put("foreignPackages", foreignJson(foreign));
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Which non-platform, non-app packages own the most frames. */
    private static void countForeign(Map<String, int[]> foreign, String pkg, String frame) {
        int paren = frame.indexOf('(');
        if (paren <= 0) {
            return;
        }
        String full = frame.substring(0, paren);
        int lastDot = full.lastIndexOf('.');
        if (lastDot <= 0) {
            return;
        }
        // Methods conventionally start with a lower-case letter (or $init and
        // friends), class simple names with an upper-case one - which is how
        // "Class$1.run" is told apart from "Class.method".
        String tail = full.substring(lastDot + 1);
        String className = Character.isLowerCase(tail.charAt(0)) || tail.startsWith("$")
                ? full.substring(0, lastDot) : full;
        int classDot = className.lastIndexOf('.');
        if (classDot <= 0) {
            return;
        }
        String classPkg = className.substring(0, classDot);
        for (String prefix : PLATFORM_PREFIXES) {
            if (classPkg.startsWith(prefix)) {
                return;
            }
        }
        if (classPkg.equals(pkg) || classPkg.startsWith(pkg + ".")) {
            return;
        }
        int[] counts = foreign.computeIfAbsent(classPkg, k -> new int[1]);
        counts[0]++;
    }

    private static JSONArray foreignJson(Map<String, int[]> foreign) {
        JSONArray out = new JSONArray();
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(foreign.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        int shown = 0;
        for (Map.Entry<String, int[]> entry : sorted) {
            if (shown++ >= 5) {
                break;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("pkg", entry.getKey());
                item.put("frames", entry.getValue()[0]);
                out.put(item);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }
}
