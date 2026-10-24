package dev.posedmcp.xposed;

import java.util.Set;

/** Exact package boundaries shared by PM, command output and native path policies. */
final class ConcealmentPackages {
    private static final Set<String> NAMES = Set.of(
            "dev.posedmcp", "dev.display", "dev.deploy",
            "de.robv.android.xposed.installer", "org.lsposed.manager", "org.lsposed.lspd",
            "org.meowcat.edxposed.manager", "com.solohsu.android.edxp.manager",
            "top.canyie.dreamland.manager", "io.github.libxposed.manager",
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk");

    static boolean hidden(String name, String target) {
        return name != null && !name.equals(target) && NAMES.contains(name);
    }

    static String[] forTarget(String target) {
        return NAMES.stream().filter(name -> !name.equals(target)).sorted().toArray(String[]::new);
    }

    static String packageOf(Object value) {
        return packageOf(value, 0);
    }

    private static String packageOf(Object value, int depth) {
        if (value == null || depth > 3) return null;
        if (value instanceof String) return (String) value;
        try {
            Object name = value.getClass().getField("packageName").get(value);
            if (name instanceof String) return (String) name;
        } catch (ReflectiveOperationException ignored) {}
        for (String field : new String[]{"activityInfo", "serviceInfo", "providerInfo", "applicationInfo"}) {
            try {
                String name = packageOf(value.getClass().getField(field).get(value), depth + 1);
                if (name != null) return name;
            } catch (ReflectiveOperationException ignored) {}
        }
        // ComponentName and VersionedPackage deliberately expose this accessor.
        try {
            Object name = value.getClass().getMethod("getPackageName").invoke(value);
            if (name instanceof String) return (String) name;
        } catch (ReflectiveOperationException ignored) {}
        return null;
    }

    private ConcealmentPackages() {}
}
