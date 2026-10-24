package dev.posedmcp.xposed;

import dev.posedmcp.Logx;
import dev.posedmcp.state.DomainRules;
import org.json.JSONArray;
import org.json.JSONObject;

/** Configures only the libc resolver filter, without an ad SDK strategy page. */
final class AdDnsPolicy {
    private static String applied;
    private static volatile String state = "disabled";

    static synchronized void configure(JSONObject policy) {
        boolean enabled = policy.optBoolean("ad_dns_enabled", false);
        JSONArray entries = policy.optJSONArray("ad_domains");
        StringBuilder raw = new StringBuilder();
        if (entries != null) for (int i = 0; i < entries.length(); i++) {
            raw.append(entries.optString(i)).append('\n');
        }
        String[] domains = enabled ? DomainRules.parse(raw.toString()) : new String[0];
        String signature = enabled + ":" + String.join("\n", domains);
        if (signature.equals(applied)) return;
        if (domains.length == 0 && !NativeRuntime.isReady()) {
            state = enabled ? "enabled, no domains configured" : "disabled";
            applied = signature;
            return;
        }
        String error = NativeRuntime.ensureLoaded();
        if (error != null) { state = error; return; }
        state = NativeRuntime.configureAdDns(domains);
        if (domains.length == 0 || state.startsWith("active")) applied = signature;
        Logx.i("ad DNS: " + state);
    }

    static JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("state", state);
            if (NativeRuntime.isReady()) out.put("native", NativeRuntime.adDnsStatus());
        } catch (Throwable t) {
            try { out.put("error", String.valueOf(t)); } catch (Exception ignored) {}
        }
        return out;
    }
    private AdDnsPolicy() {}
}
