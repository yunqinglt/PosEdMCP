package dev.posedmcp.state;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A hook the user has agreed to keep, so it comes back when the app restarts.
 *
 * <p>An ordinary runtime hook lives in the target process and dies with it -
 * and on this device a process dies often: the ROM freezes and kills background
 * apps within seconds, and every reinstall clears them. That made a hook
 * useless for anything but a single sitting. A saved hook is the definition the
 * app holds on to, and re-arms inside the target process each time it connects.
 *
 * <p>Because it re-arms on its own, the confirmation prompt is no longer the
 * only gate - it is paid once, at registration. What replaces it is the hook
 * page, which is why it has to be accurate and why removing a hook there has to
 * reach into the live process rather than merely forget the definition.
 */
public final class SavedHook {

    /**
     * The package name that means system_server.
     *
     * <p>Not a real package: it is what classic Xposed reports system_server as,
     * and the first thing {@code Framework.isSystemServer} tests for. A hook
     * cannot be stored without a package to hang it on, so the tools take this
     * name for it, and everything that addresses a target resolves it to the
     * {@code system} bridge peer rather than to any {@code app:} peer.
     */
    public static final String SYSTEM_PACKAGE = "android";

    /** A Java method hooked through the framework. The only layer there is today. */
    public static final String LAYER_DEX = "dex";

    /** The body is the declarative rule - return a value, rewrite arguments, set fields. */
    public static final String BODY_RULE = "rule";

    /** The body is a Lua function, re-created by running {@link #source}. */
    public static final String BODY_LUA = "lua";

    public String id = "";
    public String packageName = "";
    /** {@link #LAYER_DEX}. "native" is where this is headed; nothing produces it yet. */
    public String layer = LAYER_DEX;

    public String className = "";
    public String methodName = "";
    /** Comma-separated parameter types, or empty to hook every overload. */
    public String params = "";

    /** {@link #BODY_RULE} or {@link #BODY_LUA}. */
    public String body = BODY_RULE;
    /** The rule spec, as JSON text, for {@link #BODY_RULE}. */
    public String spec = "";
    /** The script that registers the hook, for {@link #BODY_LUA}. Re-run to re-arm. */
    public String source = "";

    /** One line, in the user's language: what this hook does to the app. */
    public String effect = "";
    /** Off means it is kept but not pushed to any process. */
    public boolean enabled = true;

    public long createdAt;
    public long updatedAt;
    /** Zero until a process has accepted this hook at least once. */
    public long lastAppliedAt;
    /** Why the last attempt failed, or empty. */
    public String lastError = "";

    /** The method this hook is attached to, as the tools spell it. */
    public String target() {
        return className + "#" + methodName + "(" + params + ")";
    }

    /** Whether a package name means the system framework rather than an app. */
    public static boolean isSystem(String packageName) {
        return SYSTEM_PACKAGE.equals(packageName);
    }

    /** Whether this hook is aimed at the process the whole system runs in. */
    public boolean isSystemHook() {
        return isSystem(packageName);
    }

    public static SavedHook fromJson(JSONObject o) {
        SavedHook h = new SavedHook();
        h.id = o.optString("id", "");
        h.packageName = o.optString("packageName", "");
        h.layer = o.optString("layer", LAYER_DEX);
        h.className = o.optString("className", "");
        h.methodName = o.optString("methodName", "");
        h.params = o.optString("params", "");
        h.body = o.optString("body", BODY_RULE);
        h.spec = o.optString("spec", "");
        h.source = o.optString("source", "");
        h.effect = o.optString("effect", "");
        h.enabled = o.optBoolean("enabled", true);
        h.createdAt = o.optLong("createdAt", 0L);
        h.updatedAt = o.optLong("updatedAt", 0L);
        h.lastAppliedAt = o.optLong("lastAppliedAt", 0L);
        h.lastError = o.optString("lastError", "");
        return h;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("packageName", packageName);
        o.put("layer", layer);
        o.put("className", className);
        o.put("methodName", methodName);
        o.put("params", params);
        o.put("body", body);
        o.put("spec", spec);
        o.put("source", source);
        o.put("effect", effect);
        o.put("enabled", enabled);
        o.put("createdAt", createdAt);
        o.put("updatedAt", updatedAt);
        o.put("lastAppliedAt", lastAppliedAt);
        o.put("lastError", lastError);
        return o;
    }

    /**
     * Whether two definitions would put the same thing into a process.
     *
     * <p>Only the parts that decide what actually gets installed count. Editing
     * the description of a hook does not make its recorded success a lie, but
     * widening it from one overload to all of them does.
     */
    public boolean sameInstall(SavedHook other) {
        return params.equals(other.params)
                && body.equals(other.body)
                && spec.equals(other.spec)
                && source.equals(other.source);
    }

    /** What the agent sees when listing: the definition, without the script. */
    public JSONObject describe() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("package", packageName);
        o.put("layer", layer);
        o.put("target", target());
        o.put("body", body);
        o.put("effect", effect);
        o.put("enabled", enabled);
        o.put("lastAppliedAt", lastAppliedAt == 0 ? JSONObject.NULL : lastAppliedAt);
        if (!lastError.isEmpty()) {
            o.put("lastError", lastError);
        }
        return o;
    }
}
