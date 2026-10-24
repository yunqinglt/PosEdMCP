package dev.posedmcp.root;

import android.content.Context;
import com.topjohnwu.superuser.Shell;
import dev.posedmcp.Logx;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Dedicated libsu sessions. Root-only callers never silently run with app privileges. */
public final class RootShell {
    public static final int MAX_STREAM_BYTES = 1 << 20;
    private static volatile Context context;
    private static volatile Permission permission = new Permission(null, null, -1, 0, null);

    private static final class Permission {
        final Boolean root, shell;
        final int uid;
        final long checkedAt;
        final String error;
        Permission(Boolean root, Boolean shell, int uid, long checkedAt, String error) {
            this.root = root; this.shell = shell; this.uid = uid;
            this.checkedAt = checkedAt; this.error = error;
        }
    }

    public static void initialize(Context app) { context = app.getApplicationContext(); }

    public static final class Result {
        public final int exitCode;
        public final String stdout, stderr, command;
        public final boolean timedOut, root;
        public final int uid;
        public final long durationMs;
        Result(String command, int exit, byte[] out, String err, boolean timeout, long duration,
                boolean root, int uid) {
            this.command = command; exitCode = exit; stdout = new String(out, StandardCharsets.UTF_8);
            stderr = err; timedOut = timeout; durationMs = duration; this.root = root; this.uid = uid;
        }
        public boolean ok() { return !timedOut && exitCode == 0; }
    }

    public static String suPath() { return "libsu"; }

    /** Called only during user-started service initialization, never by GET /health. */
    public static boolean isAvailable() {
        Result result = execute("/system/bin/id -u", 15_000L, false, false, 4096).result;
        return result.ok() && result.root;
    }

    /** No shell construction, command execution or root authorization from a status request. */
    public static JSONObject permissionJson() {
        Permission p = permission;
        JSONObject out = new JSONObject();
        try {
            out.put("backend", "libsu").put("rootGranted", p.root == null ? JSONObject.NULL : p.root)
                    .put("shellAvailable", p.shell == null ? JSONObject.NULL : p.shell)
                    .put("mode", p.shell == null ? "unknown" : !p.shell ? "unavailable" : Boolean.TRUE.equals(p.root) ? "root" : "app")
                    .put("uid", p.uid < 0 ? JSONObject.NULL : p.uid).put("checkedAt", p.checkedAt)
                    .put("source", "last shell verification; refreshed at service start and each shell execution");
        } catch (Exception ignored) {}
        return out;
    }

    public static JSONObject diagnostics() {
        JSONObject out = permissionJson();
        try { out.put("probeError", permission.error == null ? JSONObject.NULL : permission.error); }
        catch (Exception ignored) {}
        return out;
    }

    /** All existing privileged callers retain root-required semantics. */
    public static Result exec(String command, long timeoutMs) { return execute(command, timeoutMs, true, false, MAX_STREAM_BYTES).result; }
    static Result execRaw(String command, long timeoutMs) { return exec(command, timeoutMs); }
    /** Explicit application-shell endpoint, usable on devices without su. */
    public static Result execShell(String command, long timeoutMs) { return execute(command, timeoutMs, false, false, MAX_STREAM_BYTES).result; }

    public static byte[] execBinary(String command, long timeoutMs) throws IOException {
        Capture capture = execute(command, timeoutMs, true, false, 32 << 20);
        if (!capture.result.ok()) throw new IOException(capture.result.stderr.isEmpty()
                ? "binary command failed: " + capture.result.exitCode : capture.result.stderr);
        if (capture.output.truncated()) throw new IOException("binary output exceeds 32 MiB");
        return capture.output.bytes();
    }

    /** Watchdog remains root-only and survives closing this command's dedicated session. */
    public static void launchDetached(String script) throws IOException {
        Result r = exec("nohup /system/bin/sh -c " + quote(script) + " </dev/null >/dev/null 2>&1 &", 8_000L);
        if (!r.ok()) throw new IOException("could not arm root watchdog: " + r.stderr);
    }

    private static final class Capture {
        final Bounded output;
        final Result result;
        Capture(Bounded out, Result result) { output = out; this.result = result; }
    }

    private static Capture execute(String command, long timeoutMs, boolean requireRoot, boolean nonRoot, int limit) {
        long started = System.nanoTime();
        long timeout = Math.max(1, timeoutMs);
        Bounded out = new Bounded(limit), err = new Bounded(MAX_STREAM_BYTES);
        AtomicReference<Shell> session = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        String marker = "__POSED_EXIT_" + UUID.randomUUID() + ":";
        int[] identity = {-1};
        boolean[] root = {false};
        FutureTask<Integer> task = new FutureTask<>(() -> {
            Shell.Builder builder = Shell.Builder.create().setTimeout(Math.min(15, Math.max(1, (timeout + 999) / 1000)));
            Context app = context;
            if (app != null) builder.setContext(app);
            if (nonRoot) builder.setFlags(Shell.FLAG_NON_ROOT_SHELL);
            Shell shell = builder.build();
            session.set(shell);
            if (cancelled.get()) { shell.close(); return -1; }
            root[0] = shell.isRoot(); identity[0] = root[0] ? 0 : android.os.Process.myUid();
            permission = new Permission(root[0], true, identity[0], System.currentTimeMillis(), null);
            if (requireRoot && !root[0]) {
                err.write("Root permission is unavailable; command was not executed. Use shell_exec for app-UID commands.".getBytes(StandardCharsets.UTF_8));
                return -1;
            }
            // exec replaces the dedicated libsu shell. EOF terminates binary-safe drains;
            // a random stderr trailer carries the exit code without touching stdout bytes.
            String body = "(\n" + command + "\n) </dev/null\n__posed_status=$?\n/system/bin/printf '\\n" + marker
                    + "%s\\n' \"$__posed_status\" >&2\nexit \"$__posed_status\"\n";
            shell.execTask((stdin, stdout, stderr) -> {
                Thread drain = new Thread(() -> copy(stderr, err), "posedmcp-libsu-stderr");
                drain.setDaemon(true); drain.start();
                stdin.write(("exec /system/bin/sh -c " + quote(body) + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                copy(stdout, out);
                try { drain.join(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("interrupted", interrupted); }
            });
            return 0;
        });
        Thread worker = new Thread(task, "posedmcp-libsu-command"); worker.setDaemon(true); worker.start();
        boolean timedOut = false;
        String error = null;
        try { task.get(timeout, TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { timedOut = true; error = "Shell timed out after " + timeout + "ms; session closed."; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); timedOut = true; error = "Shell interrupted; session closed."; }
        catch (ExecutionException e) {
            error = "libsu: " + e.getCause();
            if (session.get() == null) permission = new Permission(false, false, -1, System.currentTimeMillis(), error);
        } finally {
            cancelled.set(true);
            if (!task.isDone()) task.cancel(true);
            Shell shell = session.get();
            if (shell != null) {
                Thread closer = new Thread(() -> { try { shell.close(); } catch (IOException ignored) {} }, "posedmcp-libsu-close");
                closer.setDaemon(true); closer.start();
            }
        }
        String stderr = new String(err.bytes(), StandardCharsets.UTF_8);
        int code = -1;
        int end = stderr.lastIndexOf("\n" + marker);
        if (end >= 0) {
            String status = stderr.substring(end + marker.length() + 1).trim();
            try { code = Integer.parseInt(status); stderr = stderr.substring(0, end); }
            catch (NumberFormatException ignored) {}
        } else if (err.truncated()) {
            String tail = new String(err.tail(), StandardCharsets.UTF_8);
            int markerAt = tail.lastIndexOf("\n" + marker);
            if (markerAt >= 0) try { code = Integer.parseInt(tail.substring(markerAt + marker.length() + 1).trim()); }
            catch (NumberFormatException ignored) {}
        }
        if (error != null) stderr += (stderr.isEmpty() ? "" : "\n") + error;
        if (out.truncated() && limit == MAX_STREAM_BYTES) stderr += "\n[stdout truncated at " + limit + " bytes]";
        if (err.truncated()) stderr += "\n[stderr truncated]";
        return new Capture(out, new Result(command, code, limit <= MAX_STREAM_BYTES ? out.bytes() : new byte[0], stderr, timedOut,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), root[0], identity[0]));
    }

    private static void copy(InputStream input, Bounded capture) {
        byte[] bytes = new byte[8192];
        try { int n; while ((n = input.read(bytes)) != -1) capture.write(bytes, n); }
        catch (IOException e) { Logx.w("libsu stream ended: " + e); }
    }
    private static final class Bounded {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final int limit;
        boolean truncated;
        byte[] tail = new byte[0];
        Bounded(int limit) { this.limit = limit; }
        synchronized void write(byte[] bytes) { write(bytes, bytes.length); }
        synchronized void write(byte[] bytes, int size) {
            int count = Math.min(size, limit - data.size());
            data.write(bytes, 0, count); if (count < size) truncated = true;
            int retained = Math.min(256, size), previous = Math.min(tail.length, 256 - retained);
            byte[] next = new byte[previous + retained];
            System.arraycopy(tail, tail.length - previous, next, 0, previous);
            System.arraycopy(bytes, size - retained, next, previous, retained); tail = next;
        }
        synchronized byte[] bytes() { return data.toByteArray(); }
        synchronized boolean truncated() { return truncated; }
        synchronized byte[] tail() { return tail.clone(); }
    }
    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
    private RootShell() {}
}
