package dev.posedmcp.state;
import android.content.Context;
import org.json.JSONObject;
public final class RuntimePolicy {
    public static final String KEY = "runtime_policy";
    public static JSONObject testPolicy = new JSONObject();
    public static JSONObject read(Context context) { return testPolicy; }
}
