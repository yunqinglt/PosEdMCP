package dev.posedmcp.rootlessfixture;

import android.app.Activity;
import android.os.Bundle;
import android.system.Os;
import android.widget.TextView;
import dev.posedmcp.root.RootShell;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.FutureTask;
import org.json.JSONObject;

/** Uses the real libsu AAR and production backend, with su absent only in this process's PATH. */
public final class FixtureActivity extends Activity {
    int assertions;
    void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this); text.setText("Running shell regression"); setContentView(text);
        new Thread(() -> {
            JSONObject result;
            try { result = collect(); }
            catch (Throwable t) { result = new JSONObject(); try { result.put("failure", t.toString()).put("assertions", assertions); } catch (Exception ignored) {} }
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "result.json"))) {
                out.write(result.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { throw new RuntimeException(e); }
            String done = result.toString(); runOnUiThread(() -> text.setText(done));
        }, "rootless-regression").start();
    }

    JSONObject collect() throws Exception {
        RootShell.initialize(this);
        JSONObject before = RootShell.permissionJson();
        check(before.isNull("rootGranted") && before.optLong("checkedAt") == 0, "initial unknown state");
        check(RootShell.permissionJson().toString().equals(before.toString()), "status does not start a shell");
        String oldPath = System.getenv("PATH");
        File bin = new File(getFilesDir(), "bin"); bin.mkdirs();
        for (String name : new String[]{"sh", "id"}) {
            File path = new File(bin, name);
            if (!path.exists()) Os.symlink("/system/bin/" + name, path.getAbsolutePath());
        }
        Os.setenv("PATH", bin.getAbsolutePath(), true);
        try {
            RootShell.Result text = RootShell.execShell("/system/bin/printf 'a\\nb'", 5000);
            check(text.ok() && text.stdout.equals("a\nb") && text.stderr.isEmpty(), "exact text bytes and EOF: "
                    + new JSONObject().put("exit", text.exitCode).put("stdout", text.stdout).put("stderr", text.stderr).put("uid", text.uid));
            check(!text.root && text.uid == android.os.Process.myUid(), "actual unprivileged UID");
            RootShell.Result stdinEof = RootShell.execShell("/system/bin/cat", 2000);
            check(stdinEof.ok() && stdinEof.stdout.isEmpty(), "stdin preserves noninteractive EOF");
            JSONObject permission = RootShell.permissionJson();
            check(!permission.getBoolean("rootGranted") && permission.getBoolean("shellAvailable")
                    && permission.getString("mode").equals("app"), "libsu fallback status");
            File forbidden = new File(getFilesDir(), "must-not-run");
            RootShell.Result refused = RootShell.exec("/system/bin/touch '" + forbidden.getAbsolutePath() + "'", 5000);
            check(!refused.ok() && refused.stderr.contains("not executed") && !forbidden.exists(), "root-only command never downgraded");
            RootShell.Result failed = RootShell.execShell("/system/bin/printf out; /system/bin/printf err >&2; exit 13", 5000);
            check(failed.exitCode == 13 && failed.stdout.equals("out") && failed.stderr.equals("err"), "stderr and failing exit code");
            RootShell.Result quotes = RootShell.execShell("/system/bin/printf '%s' \"中文's\"", 5000);
            check(quotes.ok() && quotes.stdout.equals("中文's"), "quotes and UTF-8 survive shell framing");
            Method execute = RootShell.class.getDeclaredMethod("execute", String.class, long.class, boolean.class, boolean.class, int.class);
            execute.setAccessible(true);
            Object capture = execute.invoke(null, "/system/bin/printf '\\000\\377\\n'", 5000L, false, false, 32 << 20);
            Field outputField = capture.getClass().getDeclaredField("output"); outputField.setAccessible(true);
            Object output = outputField.get(capture);
            Method bytes = output.getClass().getDeclaredMethod("bytes"); bytes.setAccessible(true);
            check(Arrays.equals((byte[]) bytes.invoke(output), new byte[]{0, (byte)255, 10}), "binary NUL/high byte/newline unchanged");
            try { RootShell.execBinary("/system/bin/printf x", 5000); throw new AssertionError("binary root bypass"); }
            catch (java.io.IOException expected) { check(expected.getMessage().contains("not executed"), "binary endpoint retains root requirement"); }
            RootShell.Result large = RootShell.execShell("/system/bin/head -c 1100000 /dev/zero", 5000);
            check(large.ok() && large.stdout.length() == RootShell.MAX_STREAM_BYTES && large.stderr.contains("truncated"), "bounded stdout continues draining");
            RootShell.Result err = RootShell.execShell("/system/bin/head -c 1100000 /dev/zero >&2; exit 7", 5000);
            check(err.exitCode == 7 && err.stderr.contains("truncated"), "stderr cap retains exit trailer");
            RootShell.Result timeout = RootShell.execShell("/system/bin/sleep 3", 200);
            check(timeout.timedOut && timeout.durationMs < 2500, "bounded wall-clock timeout");
            RootShell.Result next = RootShell.execShell("/system/bin/printf next", 5000);
            check(next.ok() && next.stdout.equals("next"), "timeout does not poison subsequent session");
            FutureTask<RootShell.Result> a = new FutureTask<>(() -> RootShell.execShell("/system/bin/printf first", 5000));
            FutureTask<RootShell.Result> b = new FutureTask<>(() -> RootShell.execShell("/system/bin/printf second", 5000));
            new Thread(a).start(); new Thread(b).start();
            check(a.get().stdout.equals("first") && b.get().stdout.equals("second"), "concurrent sessions isolated");
            try { RootShell.launchDetached("exit 0"); throw new AssertionError("watchdog root bypass"); }
            catch (java.io.IOException expected) { check(true, "watchdog retains root requirement"); }
            File empty = new File(getFilesDir(), "empty"); empty.mkdirs();
            Os.setenv("PATH", empty.getAbsolutePath(), true);
            RootShell.Result absent = RootShell.execShell("/system/bin/printf unavailable", 2000);
            check(!absent.ok() && RootShell.permissionJson().getString("mode").equals("unavailable"), "no available shell reports unavailable");
        } finally { Os.setenv("PATH", oldPath, true); }
        return new JSONObject().put("passed", true).put("assertions", assertions).put("uid", android.os.Process.myUid());
    }
}
