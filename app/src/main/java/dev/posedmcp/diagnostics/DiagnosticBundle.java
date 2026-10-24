package dev.posedmcp.diagnostics;

import android.content.Context;
import android.os.Build;
import dev.posedmcp.BuildConfig;
import dev.posedmcp.McpService;
import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.state.HookStore;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.state.RuntimePolicy;
import dev.posedmcp.state.SavedHook;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded local export. No root commands, hook arguments, scripts or preference dumps. */
public final class DiagnosticBundle {
    public static File create(Context context) throws Exception {
        Prefs prefs = Prefs.of(context);
        String[] secrets = {prefs.mcpToken(), prefs.bridgeToken()};
        File folder = new File(context.getCacheDir(), "diagnostic-exports");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Cannot create export directory");
        File[] old = folder.listFiles((dir, name) -> name.endsWith(".zip"));
        if (old != null) {
            Arrays.sort(old, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = 2; i < old.length; i++) old[i].delete();
        }
        File output = new File(folder, "posedmcp-logs-" + new SimpleDateFormat(
                "yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date()) + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) {
            McpService service = McpService.instance();
            JSONObject info = new JSONObject().put("createdAt", System.currentTimeMillis())
                    .put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                    .put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("abi", String.join(",", Build.SUPPORTED_ABIS))
                    .put("serviceRunning", service != null && service.isRunning())
                    .put("connectedPeers", service == null ? 0 : service.connectedPeers())
                    .put("accessibilityConnected", AccessibilityBridge.isConnected())
                    .put("runtimePolicy", RuntimePolicy.read(context))
                    .put("logcatScope", "app UID only; scoped-process logs require adb logcat")
                    .put("omitted", "tokens, hook source/spec/arguments, scripts, preferences, screenshots");
            put(zip, "device.json", info.toString(2), secrets);
            JSONArray hooks = new JSONArray();
            for (SavedHook hook : HookStore.of(context).all()) {
                hooks.put(new JSONObject().put("package", hook.packageName)
                        .put("target", hook.target()).put("enabled", hook.enabled)
                        .put("special", hook.special).put("lastAppliedAt", hook.lastAppliedAt)
                        .put("lastError", hook.lastError));
            }
            put(zip, "hooks-status.json", hooks.toString(2), secrets);
            if (service != null) put(zip, "scoped-processes.json", service.diagnosticPeers().toString(2), secrets);
            File[] logs = new File(context.getFilesDir(), "diagnostics").listFiles();
            if (logs != null) {
                Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                int count = 0;
                for (File log : logs) {
                    if (count >= 12) break;
                    if (!log.isFile() || log.length() > 1024 * 1024
                            || !(log.getName().endsWith(".log") || log.getName().endsWith(".log.1"))) continue;
                    String contents;
                    try { contents = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8); }
                    catch (java.io.IOException rotated) { contents = "[Log rotated or became unavailable: " + rotated + "]"; }
                    put(zip, "logs/" + log.getName(), contents, secrets);
                    count++;
                }
            }
            put(zip, "logcat.txt", logcat(), secrets);
            put(zip, "README.txt", "Collected locally without root. Authentication tokens are redacted.\n"
                    + "Log messages and package names may still contain personal information. Review before sharing.\n"
                    + "Connected scoped processes supply bounded module-log snapshots over the bridge.\n"
                    + "Android limits app logcat to its own UID; this includes :bootstrap but not other scoped apps.\n"
                    + "For full framework/scoped-process diagnostics, collect adb logcat separately.\n", secrets);
        } catch (Exception e) {
            output.delete();
            throw e;
        }
        return output;
    }

    private static void put(ZipOutputStream zip, String name, String text, String[] secrets) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(DiagnosticRedactor.redact(text, secrets).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String logcat() {
        try {
            Process process = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "2000",
                    "PosEdMCP:V", "PosEdNative:V", "AndroidRuntime:E", "*:S")
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (java.io.InputStream in = process.getInputStream()) {
                    byte[] bytes = new byte[8192];
                    int n;
                    while ((n = in.read(bytes)) != -1) {
                        synchronized (captured) {
                            int remaining = 1024 * 1024 - captured.size();
                            if (remaining > 0) captured.write(bytes, 0, Math.min(remaining, n));
                        }
                    }
                } catch (java.io.IOException ignored) {}
            }, "posedmcp-logcat-export");
            reader.setDaemon(true);
            reader.start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            reader.join(1000);
            synchronized (captured) {
                return captured.toString(StandardCharsets.UTF_8.name())
                        + (finished ? "" : "\n[logcat collection timed out]\n");
            }
        } catch (Exception e) { return "logcat unavailable: " + e; }
    }
    private DiagnosticBundle() {}
}
