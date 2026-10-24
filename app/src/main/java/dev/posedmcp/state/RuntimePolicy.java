package dev.posedmcp.state;

import android.content.Context;
import org.json.JSONObject;

/** Built-in scope behavior, separate from the user's ordinary hook library. */
public final class RuntimePolicy {
    public static final String KEY = "runtime_policy";
    public static final String PREFS = "runtime_policy";

    private RuntimePolicy() {}

    public static JSONObject read(Context context) {
        try {
            return new JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("json", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void save(Context context, JSONObject policy) {
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("json", policy.toString()).commit()) {
            throw new IllegalStateException("Could not save runtime policy");
        }
        dev.posedmcp.ipc.BridgeBootstrap.publish(context);
    }
}
