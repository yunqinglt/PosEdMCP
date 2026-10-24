package dev.posedmcp.xposed;

import dev.posedmcp.Logx;
import dev.posedmcp.plugin.HookApi;
import java.io.BufferedReader;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** Internal hooks. They never register ordinary user hooks or capture arguments. */
final class ScopeConcealment {
    private static final List<HookApi.Unhook> HANDLES = new ArrayList<>();
    private static final Map<Object, Boolean> MAP_READERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
    private static volatile boolean enabled;
    private static int failures;
    private static boolean installed;

    private ScopeConcealment() {}

    static boolean hiddenClass(String name) {
        return name != null && (name.equals("dev.posedmcp") || name.startsWith("dev.posedmcp.")
                || name.startsWith("de.robv.android.xposed.")
                || name.startsWith("io.github.libxposed.") || name.startsWith("org.lsposed.lspd."));
    }

    static boolean hiddenMap(String line) {
        if (line == null) return false;
        String value = line.toLowerCase(java.util.Locale.ROOT);
        return value.contains("/dev.posedmcp-") || value.contains("/dev.posedmcp/")
                || value.contains("libposednative.so") || value.contains("lspd")
                || value.contains("lsposed") || value.contains("libxposed")
                || value.contains("xposedbridge.jar")
                || value.contains("memfd:posedmcp");
    }

    // ZygoteInit is a real platform class. Restrict this rule to stack views;
    // hiding it from ClassLoader would break legitimate framework code.
    static boolean hiddenStackClass(String name) {
        return hiddenClass(name) || "com.android.internal.os.ZygoteInit".equals(name)
                || (name != null && name.startsWith("com.android.internal.os.ZygoteInit$"));
    }

    static boolean externalCaller() {
        return enabled && !BYPASS.get() && !internalCaller();
    }

    private static boolean hiddenFrame(Object value) {
        if (!(value instanceof StackTraceElement)) return false;
        BYPASS.set(true);
        try { return hiddenStackClass(((StackTraceElement) value).getClassName()); }
        finally { BYPASS.remove(); }
    }

    static boolean mapsPath(Object arg) {
        String path = arg instanceof File ? ((File) arg).getPath()
                : arg instanceof String ? (String) arg : "";
        return "/proc/self/maps".equals(path) || "/proc/thread-self/maps".equals(path)
                || ("/proc/" + android.os.Process.myPid() + "/maps").equals(path);
    }

    static synchronized void configure(ClassLoader loader, boolean active) {
        enabled = active;
        if (installed || !active) return;
        installed = true;
        enabled = false;
        // Installing a boot-class hook invokes reflection and class loading in
        // the framework itself. Do not inspect those calls while its installer
        // is still holding its own locks.
        BYPASS.set(true);
        try {
            HookApi api = Framework.hookApi(loader);
            install(api, BufferedReader.class, "readLine", new Class<?>[0], new HookApi.Callback() {
                @Override public void after(HookApi.HookParam p) throws Exception {
                    if (!enabled || BYPASS.get() || p.throwable() != null
                            || !MAP_READERS.containsKey(p.thisObject())) return;
                    String line = (String) p.result();
                    if (!hiddenMap(line) || internalCaller()) return;
                    BYPASS.set(true);
                    try {
                        BufferedReader reader = (BufferedReader) p.thisObject();
                        do { line = reader.readLine(); } while (hiddenMap(line));
                        // Includes EOF: no fabricated lines, and no recursion on a run
                        // of hidden mappings. Unrelated readers are never rewritten.
                        p.setResult(line);
                    } finally { BYPASS.remove(); }
                }
            });
            for (String type : new String[]{"java.io.FileInputStream", "java.io.FileReader",
                    "java.io.InputStreamReader", "java.io.BufferedReader"}) {
                try {
                    keep(api.hookAllConstructors(type, new HookApi.Callback() {
                        @Override public void after(HookApi.HookParam p) {
                            if (!enabled || BYPASS.get() || p.throwable() != null || p.args().length == 0) return;
                            Object source = p.args()[0];
                            if (mapsPath(source) || MAP_READERS.containsKey(source)) {
                                MAP_READERS.put(p.thisObject(), Boolean.TRUE);
                            }
                        }
                    }), type + "#<init>");
                } catch (Throwable t) { failed(type, t); }
            }
            install(api, StackTraceElement.class, "getClassName", new Class<?>[0],
                    new HookApi.Callback() {
                        @Override public void after(HookApi.HookParam p) {
                            if (enabled && !BYPASS.get() && p.throwable() == null
                                    && hiddenStackClass((String) p.result()) && !internalCaller()) {
                                p.setResult("android.os.Handler");
                            }
                        }
                    });
            for (String name : new String[]{"getMethodName", "getFileName", "toString"}) {
                final String replacement = name.equals("getMethodName") ? "dispatchMessage"
                        : name.equals("getFileName") ? "Handler.java"
                        : "android.os.Handler.dispatchMessage(Handler.java)";
                install(api, StackTraceElement.class, name, new Class<?>[0], new HookApi.Callback() {
                    @Override public void after(HookApi.HookParam p) {
                        if (enabled && !BYPASS.get() && p.throwable() == null
                                && hiddenFrame(p.thisObject()) && !internalCaller()) {
                            p.setResult(name.equals("toString") ? new StackTraceElement(
                                    "android.os.Handler", "dispatchMessage", "Handler.java",
                                    ((StackTraceElement) p.thisObject()).getLineNumber()).toString() : replacement);
                        }
                    }
                });
            }
            install(api, ClassLoader.class, "loadClass", new Class<?>[]{String.class}, classLookup());
            install(api, ClassLoader.class, "loadClass", new Class<?>[]{String.class, boolean.class},
                    classLookup());
            install(api, Method.class, "getModifiers", new Class<?>[0], new HookApi.Callback() {
                @Override public void after(HookApi.HookParam p) {
                    if (!enabled || BYPASS.get() || p.throwable() != null) return;
                    Method method = (Method) p.thisObject();
                    // Preserve genuine JNI methods. Only methods we installed hooks on
                    // can have the hook framework's synthetic native bit removed.
                    if (HookVisibility.maskNative(method) && !internalCaller()) {
                        p.setResult(((Integer) p.result()) & ~Modifier.NATIVE);
                    }
                }
            });
            Logx.i("scope concealment installed (" + failures + " failed hooks)");
        } finally {
            BYPASS.remove();
            enabled = active;
        }
    }

    private static HookApi.Callback classLookup() {
        return new HookApi.Callback() {
            @Override public void before(HookApi.HookParam p) {
                if (enabled && !BYPASS.get() && p.args().length > 0
                        && hiddenClass((String) p.args()[0])
                        && p.thisObject() != ScopeConcealment.class.getClassLoader()
                        && !internalCaller()) {
                    // Replacing the requested name could accidentally load a real
                    // class. ClassNotFoundException preserves loadClass's contract.
                    p.setThrowable(new ClassNotFoundException((String) p.args()[0]));
                }
            }
        };
    }

    private static boolean internalCaller() {
        if (BYPASS.get()) return true;
        BYPASS.set(true);
        try {
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                String name = frame.getClassName();
                if (name.equals(ScopeConcealment.class.getName())
                        || name.startsWith(ScopeConcealment.class.getName() + "$")
                        || name.startsWith("dev.posedmcp.xposed.PackageConcealment")
                        || name.startsWith("dev.posedmcp.xposed.PackageListFilter")) continue;
                // Framework adapters are on every call; find the actual caller.
                if (name.startsWith("dev.posedmcp.xposed.XposedHookApi")
                        || name.startsWith("dev.posedmcp.xposed.LibXposedHookApi")
                        || name.startsWith("de.robv.android.xposed.")
                        || name.startsWith("org.lsposed.") || name.startsWith("io.github.libxposed.")
                        || name.startsWith("java.") || name.startsWith("dalvik.")
                        || name.startsWith("android.") || name.startsWith("com.android.internal.")) continue;
                return name.startsWith("dev.posedmcp.");
            }
            return false;
        } finally { BYPASS.remove(); }
    }

    private static void install(HookApi api, Class<?> type, String name, Class<?>[] params,
            HookApi.Callback callback) {
        try {
            Method method = type.getDeclaredMethod(name, params);
            keep(api.hook(method, callback), type.getName() + "#" + name);
        } catch (Throwable t) { failed(type.getName() + "#" + name, t); }
    }

    private static void keep(HookApi.Unhook handle, String subject) {
        if (handle == null || !handle.isActive()) {
            failed(subject, new IllegalStateException("hook backend declined installation"));
        } else HANDLES.add(handle);
    }

    private static void failed(String subject, Throwable t) {
        failures++;
        Logx.w("scope concealment: " + subject + " failed: " + t);
    }

    static synchronized JSONObject status() {
        JSONObject out = new JSONObject();
        try { out.put("enabled", enabled).put("installed", installed)
                .put("failed", failures).put("registered", HANDLES.size()); }
        catch (Exception ignored) {}
        return out;
    }
}
