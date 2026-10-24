package dev.posedmcp.xposed;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.util.Set;

/** Exact framework artifacts, shared by Java file and dex views. No filesystem probes. */
final class ConcealmentPaths {
    private static final Set<String> ARTIFACTS = Set.of(
            "/system/framework/XposedBridge.jar",
            "/system/xposed.prop",
            "/system/lib/libxposed_art.so", "/system/lib64/libxposed_art.so",
            "/system/bin/app_process_xposed", "/system/bin/app_process32_xposed",
            "/system/bin/app_process64_xposed");

    static String normalized(Object value) {
        String path = value instanceof File ? ((File) value).getPath()
                : value instanceof String ? (String) value : "";
        if (File.separatorChar == '\\') path = path.replace('\\', '/');
        if (!path.startsWith("/")) return path;
        try {
            String normalized = Paths.get(path).normalize().toString();
            return File.separatorChar == '\\' ? normalized.replace('\\', '/') : normalized;
        }
        catch (InvalidPathException ignored) { return path; }
    }

    static boolean artifact(Object value) { return ARTIFACTS.contains(normalized(value)); }

    static boolean dex(Object value) {
        String path = normalized(value);
        if (artifact(path) || path.startsWith("/data/adb/lspd/")
                || path.startsWith("/data/adb/lsposed/")) return true;
        if (!path.startsWith("/data/")) return false;
        for (String part : path.split("/")) {
            if (part.equals("dev.posedmcp") || part.startsWith("dev.posedmcp-")
                    || part.equals("org.lsposed.lspd") || part.startsWith("org.lsposed.lspd-")) return true;
        }
        return false;
    }
    private ConcealmentPaths() {}
}
