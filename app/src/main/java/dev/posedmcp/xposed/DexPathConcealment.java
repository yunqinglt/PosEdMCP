package dev.posedmcp.xposed;

import dev.posedmcp.Logx;
import dev.posedmcp.plugin.HookApi;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/** Keeps Class/Field contracts and filters only module entries in returned dex path views. */
final class DexPathConcealment {
    private static final Set<String> PATH_FIELDS = Set.of("dexElements", "nativeLibraryDirectories",
            "systemNativeLibraryDirectories", "nativeLibraryPathElements");
    private static final Set<Field> OBSERVED = ConcurrentHashMap.newKeySet();
    private static volatile boolean enabled;
    private static boolean installed;
    private static int registered, failed;

    static synchronized void configure(ClassLoader loader, boolean active) {
        enabled = active;
        if (!active || installed) return;
        installed = true;
        HookApi api = Framework.hookApi(loader);
        HookApi.Callback discovery = new HookApi.Callback() {
            @Override public void after(HookApi.HookParam p) {
                if (!enabled || p.throwable() != null) return;
                if (p.result() instanceof Field && pathField((Field) p.result())) OBSERVED.add((Field) p.result());
                else if (p.result() instanceof Field[]) {
                    for (Field f : (Field[]) p.result()) if (pathField(f)) OBSERVED.add(f);
                }
            }
        };
        hook(api, Class.class, "getDeclaredField", new Class<?>[]{String.class}, discovery);
        hook(api, Class.class, "getDeclaredFields", new Class<?>[0], discovery);
        hook(api, Field.class, "get", new Class<?>[]{Object.class}, new HookApi.Callback() {
            @Override public void after(HookApi.HookParam p) {
                if (!enabled || p.throwable() != null || p.result() == null
                        || !(p.thisObject() instanceof Field)) return;
                Field field = (Field) p.thisObject();
                if (!(OBSERVED.contains(field) || pathField(field)) || !ScopeConcealment.externalCaller()) return;
                p.setResult(ScopeConcealment.internalView(() -> filtered(p.result())));
            }
        });
    }

    static boolean pathField(Field field) {
        return field.getDeclaringClass().getName().equals("dalvik.system.DexPathList")
                && PATH_FIELDS.contains(field.getName());
    }

    static Object filtered(Object original) {
        boolean array = original.getClass().isArray();
        if (!array && !(original instanceof List<?>)) return original;
        int size = array ? Array.getLength(original) : ((List<?>) original).size();
        List<Object> kept = new ArrayList<>(size);
        boolean removed = false;
        for (int i = 0; i < size; i++) {
            Object entry = array ? Array.get(original, i) : ((List<?>) original).get(i);
            if (hiddenEntry(entry)) removed = true; else kept.add(entry);
        }
        if (!removed) return original;
        if (!array) return kept;
        Object copy = Array.newInstance(original.getClass().getComponentType(), kept.size());
        for (int i = 0; i < kept.size(); i++) Array.set(copy, i, kept.get(i));
        return copy;
    }

    private static boolean hiddenEntry(Object entry) {
        if (entry instanceof java.io.File || entry instanceof String) return ConcealmentPaths.dex(entry);
        if (entry == null || !entry.getClass().getName().startsWith("dalvik.system.DexPathList$")) return false;
        for (String name : new String[]{"path", "dir", "zip", "dexFile"}) {
            try {
                Field field = entry.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object path = field.get(entry);
                if (name.equals("dexFile") && path != null) {
                    path = path.getClass().getMethod("getName").invoke(path);
                }
                if (path instanceof String) {
                    for (String part : ((String) path).split(":")) if (ConcealmentPaths.dex(part)) return true;
                } else if (ConcealmentPaths.dex(path)) return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Unknown vendor element layout is returned unchanged.
            }
        }
        return false;
    }

    private static void hook(HookApi api, Class<?> type, String name, Class<?>[] args, HookApi.Callback callback) {
        try {
            HookApi.Unhook h = api.hook(type.getDeclaredMethod(name, args), callback);
            if (h == null || !h.isActive()) throw new IllegalStateException("hook declined");
            registered++;
        } catch (Throwable t) { failed++; Logx.w("dex path view: " + name + ": " + t); }
    }
    static synchronized JSONObject status() {
        JSONObject out = new JSONObject();
        try { out.put("enabled", enabled).put("registered", registered).put("failed", failed)
                .put("observedFields", OBSERVED.size()); } catch (Exception ignored) {}
        return out;
    }
    private DexPathConcealment() {}
}
