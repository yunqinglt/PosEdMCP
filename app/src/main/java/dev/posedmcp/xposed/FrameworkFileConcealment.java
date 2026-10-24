package dev.posedmcp.xposed;

import dev.posedmcp.Logx;
import dev.posedmcp.plugin.HookApi;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import org.json.JSONObject;

/** Consistent absent-file semantics for exact framework artifacts, without replacement files. */
final class FrameworkFileConcealment {
    private static volatile boolean enabled;
    private static boolean installed;
    private static int registered, failed;

    static synchronized void configure(ClassLoader loader, boolean active) {
        enabled = active;
        if (!active || installed) return;
        installed = true;
        HookApi api = Framework.hookApi(loader);
        for (String name : new String[]{"exists", "isFile", "isDirectory", "canRead", "canWrite", "canExecute"}) {
            hook(api, name, false);
        }
        for (String name : new String[]{"length", "lastModified"}) hook(api, name, 0L);
        for (String name : new String[]{"list", "listFiles"}) {
            try {
                for (java.lang.reflect.Method method : File.class.getDeclaredMethods()) {
                    if (!method.getName().equals(name)) continue;
                    keep(api.hook(method, new HookApi.Callback() {
                        @Override public void after(HookApi.HookParam p) {
                            if (!enabled || p.throwable() != null) return;
                            String parent = ConcealmentPaths.normalized(p.thisObject());
                            if (!ConcealmentPaths.artifact(parent) && !parent.equals("/system")
                                    && !parent.equals("/system/framework") && !parent.equals("/system/bin")
                                    && !parent.equals("/system/lib") && !parent.equals("/system/lib64")) return;
                            if (!ScopeConcealment.externalCaller()) return;
                            if (ConcealmentPaths.artifact(p.thisObject())) { p.setResult(null); return; }
                            if (p.result() instanceof String[]) {
                                String[] values = (String[]) p.result();
                                ArrayList<String> kept = new ArrayList<>();
                                for (String v : values) if (!ConcealmentPaths.artifact(new File((File) p.thisObject(), v))) kept.add(v);
                                if (kept.size() != values.length) p.setResult(kept.toArray(new String[0]));
                            } else if (p.result() instanceof File[]) {
                                File[] values = (File[]) p.result();
                                ArrayList<File> kept = new ArrayList<>();
                                for (File v : values) if (!ConcealmentPaths.artifact(v)) kept.add(v);
                                if (kept.size() != values.length) p.setResult(kept.toArray(new File[0]));
                            }
                        }
                    }));
                }
            } catch (Throwable t) { failure(name, t); }
        }
        for (String name : new String[]{"java.io.FileInputStream", "java.io.FileReader", "java.io.RandomAccessFile"}) {
            try {
                keep(api.hookAllConstructors(name, new HookApi.Callback() {
                    @Override public void before(HookApi.HookParam p) {
                        if (enabled && p.args().length > 0 && ConcealmentPaths.artifact(p.args()[0])
                                && ScopeConcealment.externalCaller()) {
                            p.setThrowable(new FileNotFoundException(String.valueOf(p.args()[0]) + " (No such file or directory)"));
                        }
                    }
                }));
            } catch (Throwable t) { failure(name, t); }
        }
    }

    private static void hook(HookApi api, String name, Object absent) {
        try {
            keep(api.hook(File.class.getDeclaredMethod(name), new HookApi.Callback() {
                @Override public void after(HookApi.HookParam p) {
                    if (enabled && p.throwable() == null && ConcealmentPaths.artifact(p.thisObject())
                            && ScopeConcealment.externalCaller()) p.setResult(absent);
                }
            }));
        } catch (Throwable t) { failure(name, t); }
    }
    private static void keep(HookApi.Unhook hook) {
        if (hook == null || !hook.isActive()) throw new IllegalStateException("hook declined");
        registered++;
    }
    private static void failure(String name, Throwable t) { failed++; Logx.w("framework file: " + name + ": " + t); }
    static synchronized JSONObject status() {
        JSONObject out = new JSONObject();
        try { out.put("enabled", enabled).put("registered", registered).put("failed", failed); } catch (Exception ignored) {}
        return out;
    }
    private FrameworkFileConcealment() {}
}
