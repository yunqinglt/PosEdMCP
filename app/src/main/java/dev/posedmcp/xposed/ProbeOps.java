package dev.posedmcp.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The in-process half of the probe snapshot.
 *
 * <p>Runs inside the injected process, where {@code Thread.getAllStackTraces()}
 * reads the app's threads directly - no signal delivery, no file, no root.
 * That is exactly what makes it work for apps that swallow SIGQUIT, the one
 * case the external trace capture cannot serve, and it is what the module's
 * scope buys: this op exists only in processes the framework injected us
 * into.
 *
 * <p>Frame strings keep the same shape as the ANR trace parser produces
 * ("com.foo.Bar.baz(Bar.java:12)"), so the app-side analysis - counting
 * which foreign packages own the most frames - treats both sources alike.
 */
public final class ProbeOps {

    private static final int MAX_THREADS = 60;
    private static final int MAX_FRAMES = 25;

    private ProbeOps() {
    }

    /** Every thread with its Java stack, capped; sorted by tid for stability. */
    public static JSONObject threadSnapshot() {
        JSONObject out = new JSONObject();
        JSONArray threads = new JSONArray();
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            List<Thread> sorted = new ArrayList<>(all.keySet());
            sorted.sort(Comparator.comparingLong(Thread::getId));
            for (Thread t : sorted) {
                if (threads.length() >= MAX_THREADS) {
                    break;
                }
                StackTraceElement[] stack = all.get(t);
                if (stack == null) {
                    continue;
                }
                JSONObject thread = new JSONObject();
                thread.put("name", t.getName());
                thread.put("tid", t.getId());
                thread.put("state", String.valueOf(t.getState()));
                JSONArray frames = new JSONArray();
                int n = Math.min(stack.length, MAX_FRAMES);
                for (int i = 0; i < n; i++) {
                    StackTraceElement e = stack[i];
                    frames.put(e.getClassName() + "." + e.getMethodName() + "("
                            + (e.isNativeMethod() ? "Native Method"
                                    : e.getFileName() == null ? "Unknown Source"
                                            : e.getFileName() + ":" + e.getLineNumber())
                            + ")");
                }
                thread.put("stack", frames);
                threads.put(thread);
            }
        } catch (Throwable t) {
            // A snapshot that cannot be taken must not take the app with it.
        }
        try {
            out.put("threads", threads);
        } catch (Throwable ignored) {
        }
        return out;
    }
}
