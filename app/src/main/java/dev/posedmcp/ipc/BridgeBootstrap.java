package dev.posedmcp.ipc;

import android.content.Context;
import android.os.Bundle;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import dev.posedmcp.Logx;
import dev.posedmcp.state.HookMirror;
import dev.posedmcp.state.HookStore;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.state.RuntimePolicy;

/** Main-process writer, fresh disk reader in :bootstrap. Never starts the MCP server. */
public final class BridgeBootstrap {
    private BridgeBootstrap() {}

    private static File file(Context context) {
        return new File(context.getFilesDir(), "bridge-bootstrap.json");
    }

    public static synchronized void publish(Context context) {
        Prefs prefs = Prefs.of(context);
        // Only the main process creates credentials. A Binder request must not race it.
        if (prefs.bridgeToken().isEmpty()) return;
        AtomicFile target = new AtomicFile(file(context));
        FileOutputStream out = null;
        try {
            JSONObject json = new JSONObject()
                    .put("bridge_token", prefs.bridgeToken())
                    .put("bridge_port", prefs.bridgePort())
                    .put("module_apk", context.getApplicationInfo().sourceDir)
                    .put(RuntimePolicy.KEY, RuntimePolicy.read(context).toString())
                    .put(HookMirror.KEY, HookMirror.json(HookStore.of(context).all()));
            out = target.startWrite();
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            target.finishWrite(out);
            FrameworkPreferencesProvider.publish(context);
        } catch (Exception e) {
            if (out != null) target.failWrite(out);
            Logx.e("Could not publish bridge bootstrap", e);
        }
    }

    public static Bundle read(Context context, int callingUid) {
        try {
            // Do not open AtomicFile here: its recovery can rename files while the
            // single writer is publishing. rename() gives readers a complete generation.
            File target = file(context);
            if (!target.exists()) return legacy(context, callingUid);
            JSONObject json = new JSONObject(new String(
                    java.nio.file.Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
            return bundle(context, json, callingUid);
        } catch (Exception e) {
            Logx.e("Could not read bridge bootstrap", e);
            return new Bundle();
        }
    }

    private static Bundle legacy(Context context, int uid) throws Exception {
        // Upgrade path before the user first opens this version. Read-only: no
        // token creation, hook writes or background server startup in this process.
        Prefs prefs = Prefs.of(context);
        return bundle(context, new JSONObject()
                .put("bridge_token", prefs.bridgeToken()).put("bridge_port", prefs.bridgePort())
                .put("module_apk", context.getApplicationInfo().sourceDir)
                .put(RuntimePolicy.KEY, RuntimePolicy.read(context).toString())
                .put(HookMirror.KEY, HookMirror.json(HookStore.of(context).all())), uid);
    }

    private static Bundle bundle(Context context, JSONObject json, int uid) throws Exception {
        Bundle reply = new Bundle();
        reply.putString("bridge_token", json.optString("bridge_token", ""));
        reply.putInt("bridge_port", json.optInt("bridge_port", Prefs.DEFAULT_BRIDGE_PORT));
        reply.putString("module_apk", json.optString("module_apk", ""));
        reply.putString(RuntimePolicy.KEY, json.optString(RuntimePolicy.KEY, "{}"));
        // Keep Binder replies small, and never bootstrap a system_server hook.
        JSONArray hooks = new JSONArray();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages != null && uid % 100000 >= 10000) {
            for (String pkg : packages) {
                for (JSONObject hook : HookMirror.forPackage(json.optString(HookMirror.KEY), pkg)) {
                    hooks.put(hook);
                }
            }
        }
        reply.putString(HookMirror.KEY, hooks.toString());
        return reply;
    }
}
