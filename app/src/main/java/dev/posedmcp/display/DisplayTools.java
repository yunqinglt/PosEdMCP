package dev.posedmcp.display;

import android.content.Context;
import dev.display.api.DisplayContract;
import dev.display.api.IDisplay;
import dev.posedmcp.mcp.McpTool;
import org.json.JSONObject;
import java.util.function.Consumer;

/** All mutations go through the existing PosEdMCP plugin confirmation policy. */
public final class DisplayTools {
    public interface Confirmation { void request(String title, String detail, String reason) throws Exception; }
    private DisplayTools() {}
    public static void register(Context context, Consumer<McpTool> add, Confirmation confirm) {
        DisplayClient client = DisplayClient.get(context);
        add.accept(McpTool.of("display_status").description("Display AIDL frontend and Core availability; frontend availability does not imply active hooks.")
                .input(new JSONObject()).handler(a -> result(client.status())).build());
        add.accept(McpTool.of("display_capabilities").description("Display rule catalogue. backendImplemented describes available implementations; query display_runtime_state for each live process's support and acknowledgements.")
                .input(new JSONObject()).handler(a -> result(client.call(IDisplay::capabilities))).build());
        add.accept(McpTool.of("display_validate_profile").description("Validate a Display profile without saving/applying. schemaVersion=1, id, rules:[{key,value}]. Query display_capabilities for rule keys.")
                .input(props("profile"), "profile").handler(a -> {
                    String json = profile(a);
                    return result(client.call(s -> s.validateProfile(json)));
                }).build());
        add.accept(McpTool.of("display_get_profile").description("Read a stored Display profile with its revision. Requires Core.")
                .input(props("profileId"), "profileId").handler(a -> {
                    String id = DisplayContract.id(string(a,"profileId"));
                    return result(client.call(s -> s.getProfile(id)));
                }).build());
        add.accept(McpTool.of("display_put_profile").description("Save a validated Display profile using CAS. expectedRevision=0 creates; requestId identifies an identical retry. Requires Core and plugin confirmation; does not apply hooks.")
                .mutating().input(props("profile", "expectedRevision", "requestId", "reason"), "profile", "expectedRevision", "requestId", "reason")
                .handler(a -> {
                    String json = profile(a), request = request(a); long revision = revision(a,"expectedRevision");
                    JSONObject unavailable = unavailable(client); if (unavailable != null) return unavailable;
                    confirm.request("Save Display profile", json + "\nExpected revision: " + revision, string(a,"reason"));
                    return result(client.call(s -> s.putProfile(json, revision, request)));
                }).build());
        add.accept(McpTool.of("display_delete_profile").description("Delete a Display profile using CAS; Core must reject deletion while referenced. Requires Core and plugin confirmation.")
                .mutating().input(props("profileId", "expectedRevision", "requestId", "reason"), "profileId", "expectedRevision", "requestId", "reason")
                .handler(a -> {
                    String id = DisplayContract.id(string(a,"profileId")), request = request(a); long revision = revision(a,"expectedRevision");
                    JSONObject unavailable = unavailable(client); if (unavailable != null) return unavailable;
                    confirm.request("Delete Display profile", id + "\nExpected revision: " + revision, string(a,"reason"));
                    return result(client.call(s -> s.deleteProfile(id, revision, request)));
                }).build());
        add.accept(McpTool.of("display_get_target").description("Read desired profile binding and revision for an exact application package. Requires Core.")
                .input(props("package"), "package").handler(a -> {
                    String pkg = target(a); return result(client.call(s -> s.getTarget(pkg)));
                }).build());
        add.accept(McpTool.of("display_apply_profile").description("Bind a pinned profile revision to an exact LSPosed-scoped package using target CAS. Core must verify actual scope. Accepted desired state does not imply every process acknowledged it; query display_runtime_state. Requires Core and plugin confirmation.")
                .mutating().input(props("package", "profileId", "profileRevision", "expectedTargetRevision", "requestId", "reason"),
                        "package", "profileId", "profileRevision", "expectedTargetRevision", "requestId", "reason")
                .handler(a -> {
                    String pkg = target(a), id = DisplayContract.id(string(a,"profileId")), request = request(a);
                    long profileRev = revision(a,"profileRevision"), targetRev = revision(a,"expectedTargetRevision");
                    if (profileRev == 0) throw new IllegalArgumentException("profileRevision must be > 0");
                    JSONObject unavailable = unavailable(client); if (unavailable != null) return unavailable;
                    JSONObject stored = client.call(s -> s.getProfile(id));
                    if (!stored.optBoolean("ok")) return result(stored);
                    JSONObject data = stored.getJSONObject("data");
                    if (revision(data,"revision") != profileRev) throw new IllegalArgumentException("profile revision changed; read it again");
                    confirm.request("Apply Display profile to " + pkg, "Profile: " + id + " @ " + profileRev
                            + "\nExpected target revision: " + targetRev + "\n" + data.getJSONObject("profile"), string(a,"reason"));
                    return result(client.call(s -> s.applyProfile(pkg, id, profileRev, targetRev, request)));
                }).build());
        add.accept(McpTool.of("display_clear_target").description("Remove a package's desired Display profile and request original framework behavior. Actual process restoration must be checked with display_runtime_state. Requires Core and plugin confirmation.")
                .mutating().input(props("package", "expectedTargetRevision", "requestId", "reason"), "package", "expectedTargetRevision", "requestId", "reason")
                .handler(a -> {
                    String pkg = target(a), request = request(a); long revision = revision(a,"expectedTargetRevision");
                    JSONObject unavailable = unavailable(client); if (unavailable != null) return unavailable;
                    confirm.request("Clear Display profile for " + pkg, "Restore original values; expected target revision: " + revision, string(a,"reason"));
                    return result(client.call(s -> s.clearTarget(pkg, revision, request)));
                }).build());
        add.accept(McpTool.of("display_runtime_state").description("Read actual scope verification, per-process acknowledged revision, unsupported rules, and restart requirements. Requires Core; never infer activation from stored configuration.")
                .input(props("package"), "package").handler(a -> {
                    String pkg = target(a); return result(client.call(s -> s.getRuntimeState(pkg)));
                }).build());
    }
    private static JSONObject unavailable(DisplayClient client) throws Exception {
        JSONObject status = client.status();
        if (!status.optBoolean("ok")) return result(status);
        if (!status.getJSONObject("data").optBoolean("coreAvailable"))
            return result(DisplayContract.error("CORE_UNAVAILABLE", "DisplayCore is not implemented; nothing was saved or applied"));
        return null;
    }
    private static JSONObject result(JSONObject reply) throws Exception {
        return reply.optBoolean("ok") ? McpTool.json(reply) : McpTool.error(reply.toString());
    }
    private static String string(JSONObject a, String name) throws Exception {
        String s = DisplayContract.strictString(a.get(name));
        if (s.trim().isEmpty() || s.length() > 2048) throw new IllegalArgumentException(name + " must be nonblank and <=2048 characters");
        return s;
    }
    private static String profile(JSONObject a) throws Exception {
        String json = a.getJSONObject("profile").toString(); DisplayContract.validateProfile(json); return json;
    }
    private static String target(JSONObject a) throws Exception { return DisplayContract.target(string(a,"package")); }
    private static String request(JSONObject a) throws Exception { return DisplayContract.id(string(a,"requestId")); }
    private static long revision(JSONObject a, String key) throws Exception {
        return DisplayContract.integer(a.get(key), 0, 9_007_199_254_740_991L);
    }
    private static JSONObject props(String... names) {
        JSONObject props = new JSONObject();
        try {
            for (String name : names) {
                JSONObject schema = new JSONObject();
                if (name.endsWith("Revision") || name.equals("profileRevision"))
                    schema.put("type", "integer").put("minimum", name.equals("profileRevision") ? 1 : 0).put("maximum", 9_007_199_254_740_991L);
                else if (name.equals("profile")) schema.put("type", "object");
                else schema.put("type", "string").put("minLength", 1).put("maxLength", name.equals("reason") ? 2048 : name.equals("package") ? 255 : 64);
                props.put(name, schema);
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        return props;
    }
}
