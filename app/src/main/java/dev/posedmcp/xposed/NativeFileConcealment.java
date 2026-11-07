package dev.posedmcp.xposed;

import org.json.JSONObject;

/** Native package-directory probes; never loaded or configured in system_server. */
final class NativeFileConcealment {
    private static String applied;
    private static volatile String state = "disabled";
    private static volatile boolean requested, enabled;
    private static volatile String compatibilityReason;
    static synchronized void configure(boolean active, String target) {
        requested = active;
        // Confirmed on China Mobile 12.5.5: libc open's backup jumped into a
        // non-executable CFI mapping / recursed at filtered_open. Do not install
        // this native surface in that app, even when the experimental switch is on.
        boolean incompatible = incompatible(target);
        compatibilityReason = incompatible ? "native loader conflict: libc file hooks skipped" : null;
        active = active && !incompatible;
        String signature = requested + ":" + active + ":" + target;
        if (signature.equals(applied)) return;
        enabled = false;
        if (!active && !NativeRuntime.isReady()) { state = "disabled"; applied = signature; return; }
        String error = NativeRuntime.ensureLoaded();
        if (error != null) { state = error; return; }
        state = NativeRuntime.configureConcealmentFiles(active
                ? ConcealmentPackages.forTarget(target) : new String[0]);
        enabled = active && state != null && state.contains("active=1");
        if (!active || (state != null && state.contains("api=1") && state.contains("failed=0"))) applied = signature;
    }
    static JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", state).put("requested", requested).put("enabled", enabled);
            if (compatibilityReason != null) out.put("compatibilityReason", compatibilityReason);
            if (NativeRuntime.isReady()) out.put("native", NativeRuntime.concealmentFilesStatus());
        } catch (Throwable t) { try { out.put("error", String.valueOf(t)); } catch (Exception ignored) {} }
        return out;
    }
    static boolean incompatible(String target) {
        return "com.greenpoint.android.mc10086.activity".equals(target);
    }
    private NativeFileConcealment() {}
}
