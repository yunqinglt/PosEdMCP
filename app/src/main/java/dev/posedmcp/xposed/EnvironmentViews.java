package dev.posedmcp.xposed;

import dev.posedmcp.plugin.HookApi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Java read views only. Never writes the environment or the global Properties. */
final class EnvironmentViews {
    private static final Set<String> MARKERS = Set.of("magisk.version", "magisk.versionCode",
            "ro.magisk.version", "ro.magisk.version.code", "xposed.version", "lsposed.version");

    static String path(String original) {
        ArrayList<String> kept = new ArrayList<>();
        boolean changed = false;
        for (String item : original.split(":", -1)) {
            if (MountViews.rootPath(ConcealmentPaths.normalized(item))) changed = true;
            else kept.add(item);
        }
        return changed ? String.join(":", kept) : original;
    }

    static final HookApi.Callback ENV = new HookApi.Callback() {
        @Override public void after(HookApi.HookParam p) {
            if (!ExtendedConcealment.isEnabled() || p.throwable() != null) return;
            if (p.args().length == 1 && "PATH".equals(p.args()[0]) && p.result() instanceof String) {
                String original = (String) p.result(), view = path(original);
                if (view != original && ScopeConcealment.externalCaller()) p.setResult(view);
            } else if (p.args().length == 0 && p.result() instanceof Map<?, ?>) {
                Map<?, ?> original = (Map<?, ?>) p.result();
                Object value = original.get("PATH");
                if (!(value instanceof String)) return;
                String view = path((String) value);
                if (view != value && ScopeConcealment.externalCaller()) {
                    Map<Object, Object> copy = new LinkedHashMap<>(original);
                    copy.put("PATH", view);
                    p.setResult(Collections.unmodifiableMap(copy));
                }
            }
        }
    };

    static final HookApi.Callback PROPERTY = new HookApi.Callback() {
        @Override public void after(HookApi.HookParam p) {
            if (!ExtendedConcealment.isEnabled() || p.throwable() != null
                    || !(p.args()[0] instanceof String) || !MARKERS.contains(p.args()[0])
                    || !ScopeConcealment.externalCaller()) return;
            p.setResult(p.args().length == 2 ? p.args()[1] : null);
        }
    };

    private EnvironmentViews() {}
}
