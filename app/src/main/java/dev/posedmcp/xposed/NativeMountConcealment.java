package dev.posedmcp.xposed;

import org.json.JSONObject;

/** Independently opted-in libc system() view, only in selected app scopes. */
final class NativeMountConcealment {
    private static boolean requested;
    private static boolean configured;
    private static String reason;

    static synchronized void configure(boolean active, String target) {
        requested = active;
        reason = null;
        if (active && NativeFileConcealment.incompatible(target)) {
            reason = "native loader conflict: libc system hook skipped";
            active = false;
        }
        if (active == configured) return;
        if (active) {
            reason = NativeRuntime.ensureLoaded();
            if (reason != null) return;
        }
        NativeRuntime.configureNativeMount(active);
        configured = active;
    }

    static synchronized JSONObject status() {
        JSONObject result = new JSONObject();
        try {
            String nativeState = NativeRuntime.isReady() ? NativeRuntime.nativeMountStatus() : null;
            result.put("requested", requested)
                    .put("enabled", configured && nativeState != null && nativeState.startsWith("active=1 "));
            if (reason != null) result.put("reason", reason);
            if (nativeState != null) result.put("native", nativeState);
        } catch (Throwable t) {
            try { result.put("error", t.toString()); } catch (Exception ignored) {}
        }
        return result;
    }

    private NativeMountConcealment() {}
}
