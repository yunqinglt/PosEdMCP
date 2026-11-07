package dev.posedmcp.xposed;

import java.util.List;

/** Match mount fields and proc paths, never arbitrary substrings or mount options. */
final class MountViews {
    enum Format { MAPS, MOUNTS, INFO, COMMAND }

    static Format proc(Object value) {
        return ScopeConcealment.mapsPath(value) ? Format.MAPS : path(value);
    }

    static Format path(Object value) {
        String path = ConcealmentPaths.normalized(value);
        if (path.equals("/proc/mounts")) return Format.MOUNTS;
        String[] parts = path.split("/", -1);
        if (parts.length < 4 || !parts[0].isEmpty() || !parts[1].equals("proc")) return null;
        boolean own = parts[2].equals("self")
                || parts[2].equals(String.valueOf(android.os.Process.myPid()));
        if (parts.length == 4) {
            if (!own && !parts[2].equals("thread-self")) return null;
        } else if (parts.length != 6 || !own || !parts[3].equals("task")
                || !digits(parts[4])) return null;
        // Some callers use the singular spelling. Classify only: do not redirect
        // a nonexistent file or turn its original exception into success.
        switch (parts[parts.length - 1]) {
            case "mount": case "mounts": return Format.MOUNTS;
            case "mountinfo": return Format.INFO;
            default: return null;
        }
    }

    static Format command(List<String> args) {
        if (args.size() == 3 && (args.get(0).equals("sh") || args.get(0).equals("/system/bin/sh"))
                && args.get(1).equals("-c")) {
            args = PackageListFilter.words(args.get(2));
        }
        if (args == null || args.isEmpty()) return null;
        if (args.get(0).equals("/system/bin/toybox")) args = args.subList(1, args.size());
        if (args.size() == 1 && mountProgram(args.get(0))) return Format.COMMAND;
        if (args.size() == 2 && (args.get(0).equals("cat") || args.get(0).equals("/system/bin/cat"))) {
            return path(args.get(1));
        }
        return null;
    }

    private static boolean mountProgram(String value) {
        return value.equals("mount") || value.equals("/system/bin/mount");
    }

    static boolean hidden(String line, Format format) {
        if (line == null || format == null) return false;
        if (format == Format.MAPS) return ScopeConcealment.hiddenMap(line);
        String[] fields = line.trim().split("[ \t]+");
        switch (format) {
            case MOUNTS:
                return fields.length == 6 && digits(fields[4]) && digits(fields[5])
                        && (source(fields[0]) || artifact(fields[1]));
            case INFO:
                if (fields.length < 10 || !digits(fields[0]) || !digits(fields[1])
                        || fields[2].indexOf(':') < 1) return false;
                for (int i = 6; i + 3 < fields.length; i++) {
                    if (fields[i].equals("-")) {
                        return artifact(fields[3]) || artifact(fields[4]) || source(fields[i + 2]);
                    }
                }
                return false;
            case COMMAND:
                return fields.length >= 6 && fields[1].equals("on") && fields[3].equals("type")
                        && fields[5].startsWith("(") && line.endsWith(")")
                        && (source(fields[0]) || artifact(fields[2]));
            default: return false;
        }
    }

    private static boolean digits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean source(String value) {
        return value.equals("magisk") || value.equals("lsposed") || value.equals("lspd")
                || value.equals("zygisk") || artifact(value);
    }

    static boolean artifact(String path) {
        // Keep this boundary list in sync with mount_command.h. /adb/modules
        // is a filesystem-relative root reported by mountinfo on this device.
        return rootPath(path) || below(path, "/data/adb/lspd") || below(path, "/data/adb/lsposed")
                || below(path, "/data/adb/modules/zygisk_lsposed")
                || below(path, "/adb/modules/zygisk_lsposed");
    }

    static boolean rootPath(String path) {
        return below(path, "/data/adb/magisk") || below(path, "/sbin/.magisk")
                || below(path, "/debug_ramdisk/.magisk") || below(path, "/dev/.magisk")
                || path.equals("/debug_ramdisk/magisk") || path.equals("/debug_ramdisk/magisk32")
                || path.equals("/debug_ramdisk/magisk64") || path.equals("/sbin/magisk")
                || path.equals("/system/bin/magisk") || path.equals("/system/bin/magiskpolicy")
                || path.equals("/system/bin/su") || path.equals("/system/xbin/su")
                || path.equals("/sbin/su") || path.equals("/debug_ramdisk/su");
    }

    private static boolean below(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private MountViews() {}
}
