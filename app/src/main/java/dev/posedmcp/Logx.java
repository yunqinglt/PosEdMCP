package dev.posedmcp;

import android.util.Log;

/**
 * Logging that survives being called from system_server, where an uncaught
 * throw would take the whole system down. Everything is best-effort.
 */
public final class Logx {

    public static final String TAG = "PosEdMCP";
    private static java.io.File logFile;
    private static final long MAX_FILE_BYTES = 512 * 1024;
    private static final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private static int recentChars;

    /** Called only in our own application processes; injected processes keep logcat only. */
    public static synchronized void initialize(android.content.Context context) {
        try {
            java.io.File dir = new java.io.File(context.getFilesDir(), "diagnostics");
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            String name = android.app.Application.getProcessName().replaceAll("[^A-Za-z0-9_.-]", "_");
            logFile = new java.io.File(dir, name + "-" + android.os.Process.myPid() + ".log");
            java.io.File[] existing = dir.listFiles();
            if (existing != null) {
                java.util.Arrays.sort(existing, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = 15; i < existing.length; i++) existing[i].delete();
            }
            append(Log.INFO, "process started: " + name);
        } catch (Throwable ignored) {}
    }

    private Logx() {
    }

    public static void i(String msg) {
        log(Log.INFO, msg);
    }

    public static void w(String msg) {
        log(Log.WARN, msg);
    }

    public static void e(String msg) {
        log(Log.ERROR, msg);
    }

    public static void e(String msg, Throwable t) {
        try {
            Log.e(TAG, msg, t);
            append(Log.ERROR, msg + "\n" + stack(t));
        } catch (Throwable ignored) {
        }
    }

    private static void log(int priority, String msg) {
        try {
            Log.println(priority, TAG, msg);
            append(priority, msg);
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void append(int priority, String msg) {
        try {
            String line = System.currentTimeMillis() + " " + priority + " " + msg + "\n";
            if (line.length() > 16384) line = line.substring(0, 16384) + " [truncated]\n";
            recent.addLast(line);
            recentChars += line.length();
            while (recentChars > 32768 || recent.size() > 200) {
                recentChars -= recent.removeFirst().length();
            }
            if (logFile == null) return;
            if (logFile.length() >= MAX_FILE_BYTES) {
                java.io.File backup = new java.io.File(logFile.getPath() + ".1");
                if (backup.exists() && !backup.delete()) return;
                if (!logFile.renameTo(backup)) return;
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(logFile, true)) {
                out.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {}
    }

    /** A bounded process-local snapshot, also available in scoped processes. */
    public static synchronized String recent() { return String.join("", recent); }

    /** Never let diagnostics become the failure. */
    public static String stack(Throwable t) {
        if (t == null) {
            return "null";
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
