package dev.posedmcp.xposed;

import org.json.JSONObject;

/** Native package-directory probes; never loaded or configured in system_server. */
final class NativeFileConcealment {
    private static String applied;
    private static volatile String state = "disabled";
    static synchronized void configure(boolean active, String target) {
        String signature = active + ":" + target;
        if (signature.equals(applied)) return;
        if (!active && !NativeRuntime.isReady()) { state = "disabled"; applied = signature; return; }
        String error = NativeRuntime.ensureLoaded();
        if (error != null) { state = error; return; }
        state = NativeRuntime.configureConcealmentFiles(active
                ? ConcealmentPackages.forTarget(target) : new String[0]);
        if (!active || (state != null && state.contains("api=1") && state.contains("failed=0"))) applied = signature;
    }
    static JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", state);
            if (NativeRuntime.isReady()) out.put("native", NativeRuntime.concealmentFilesStatus());
        } catch (Throwable t) { try { out.put("error", String.valueOf(t)); } catch (Exception ignored) {} }
        return out;
    }
    private NativeFileConcealment() {}
}
