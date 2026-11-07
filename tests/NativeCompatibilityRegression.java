package fixture;

import dev.posedmcp.xposed.NativeRuntime;
import org.json.JSONObject;

/** Verify that exclusions prevent loading hooks, and live disabling clears native rules. */
public final class NativeCompatibilityRegression {
    static int assertions;
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static void configure(boolean active, String target) throws Exception {
        RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed.NativeFileConcealment", "configure",
                new Class<?>[]{boolean.class, String.class}, active, target);
    }
    static JSONObject status() throws Exception {
        return (JSONObject)RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed.NativeFileConcealment",
                "status", new Class<?>[0]);
    }
    public static void main(String[] args) throws Exception {
        configure(false, "example.app");
        check(NativeRuntime.loads == 0 && NativeRuntime.configurations == 0, "disabled path must not load a library");
        configure(true, "com.greenpoint.android.mc10086.activity");
        check(NativeRuntime.loads == 0 && NativeRuntime.configurations == 0, "known conflict must not install libc hooks");
        check(status().getBoolean("requested") && !status().getBoolean("enabled"), "report requested versus actual policy");
        check(status().getString("compatibilityReason").contains("native loader conflict"), "explain compatibility exclusion");
        configure(true, "example.app");
        check(NativeRuntime.loads == 1 && NativeRuntime.configurations == 1 && NativeRuntime.lastCount > 0,
                "explicit opt-in can configure a compatible target");
        configure(true, "example.app");
        check(NativeRuntime.configurations == 1, "same snapshot does not reinstall");
        configure(false, "example.app");
        check(NativeRuntime.configurations == 2 && NativeRuntime.lastCount == 0, "disable clears live native rules");
        check(!status().getBoolean("enabled") && !status().getBoolean("requested"), "disabled status is explicit");
        NativeRuntime.error = "fixture load declined";
        configure(true, "example.other");
        check(NativeRuntime.configurations == 2 && !status().getBoolean("enabled")
                        && status().getString("state").equals(NativeRuntime.error),
                "load failure does not activate rules and is reported");
        System.out.println("PASS native compatibility (" + assertions + " assertions)");
    }
}
