package dev.posedmcp.tools;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import dev.posedmcp.Logx;
import dev.posedmcp.ipc.BridgeServer;
import dev.posedmcp.state.HookGuard;
import dev.posedmcp.state.HookStore;
import dev.posedmcp.state.SavedHook;
import dev.posedmcp.xposed.LuaRuntime;

/**
 * Puts saved hooks back into a process.
 *
 * <p>A runtime hook lives only in the process it was installed into, and on
 * this device processes are killed constantly - the ROM freezes and reaps
 * background apps within seconds, and every reinstall clears them. Without this,
 * a hook was useful for exactly one sitting, which made the whole observation
 * workflow unreliable in a way that had nothing to do with the analysis.
 *
 * <p>So the app holds the definitions and pushes them in whenever one of a
 * package's processes connects. The push has to happen off the accepting thread
 * (see {@link BridgeServer.PeerListener}) or the reply is never read.
 *
 * <p>"A package's processes" includes the single one behind
 * {@link BridgeServer#SYSTEM_PACKAGE}: system_server is never reaped the way an
 * app is, so a hook there rarely needs putting back - but when it does, it is
 * because system_server itself restarted, which is exactly when nothing else is
 * going to do it.
 *
 * <p>The stored rule is already the request body the module expects, which is
 * why re-arming a value hook is a copy rather than a translation - there is no
 * second spelling of a hook to drift out of step with the first.
 */
public final class HookDeploy {

    /** Generous: a Lua hook may be re-running a script inside a starting app. */
    private static final long TIMEOUT_MS = 20_000L;

    private HookDeploy() {
    }

    /** Re-arms every enabled hook of a package in one of its processes. */
    public static void deploy(Context ctx, BridgeServer bridge, String peerKey, String pkg,
            String moduleApk) {
        HookStore store = HookStore.of(ctx);
        List<SavedHook> hooks = store.armedFor(pkg);
        if (hooks.isEmpty()) {
            return;
        }
        if (SavedHook.isSystem(pkg) && HookGuard.suspended(ctx)) {
            Logx.w("not re-arming " + hooks.size() + " hook(s) into system_server: the rescue"
                    + " module has them suspended");
            return;
        }
        int armed = 0;
        for (SavedHook hook : hooks) {
            try {
                push(bridge, peerKey, hook, moduleApk);
                store.recordApplied(hook.id, true, null);
                armed++;
            } catch (Throwable t) {
                String error = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                store.recordApplied(hook.id, false, error);
                Logx.w("could not re-arm " + hook.target() + " in " + peerKey + ": " + error);
            }
        }
        Logx.i("re-armed " + armed + "/" + hooks.size() + " hook(s) in " + peerKey);
    }

    /** Sends one hook into one process, and says so if it did not take. */
    public static JSONObject push(BridgeServer bridge, String peerKey, SavedHook hook,
            String moduleApk) throws IOException {        if (SavedHook.BODY_LUA.equals(hook.body)) {
            JSONObject args = new JSONObject();
            try {
                args.put("source", hook.source);
                args.put("max_instructions", LuaRuntime.DEFAULT_MAX_INSTRUCTIONS);
                args.put("module_apk", moduleApk == null ? "" : moduleApk);
            } catch (Throwable t) {
                throw new IOException(t);
            }
            JSONObject result = bridge.request(peerKey, "lua_exec", args, TIMEOUT_MS);
            // The bridge call succeeding only means the script was run. A script
            // that threw is a hook that is not armed, and the page has to say so
            // rather than show a tick it did not earn.
            if (!result.optBoolean("ok", false)) {
                throw new IOException("the script failed: "
                        + result.optString("error", "unknown error"));
            }
            return result;
        }

        JSONObject args;
        try {
            args = new JSONObject(hook.spec);
        } catch (Throwable t) {
            throw new IOException("this hook's stored rule cannot be read back: " + t, t);
        }
        return bridge.request(peerKey, "hook_method", args, TIMEOUT_MS);
    }

    /**
     * Arms one hook in every process of its package, right now.
     *
     * <p>Used when the user turns a hook back on from the app: the page is
     * showing them a switch, and a switch that only takes effect after the next
     * restart would be a lie about the current state.
     *
     * <p>A suspended hook is not armed even from here. The suspension exists
     * because this hook may be the reason the device cannot finish booting, and
     * a button that quietly overrode it would make it worthless at the moment it
     * is needed.
     */
    public static JSONObject applyToAllProcesses(Context ctx, BridgeServer bridge, SavedHook hook,
            String moduleApk) {
        JSONObject out = new JSONObject();
        int applied = 0;
        org.json.JSONArray failures = new org.json.JSONArray();
        if (hook.isSystemHook() && HookGuard.suspended(ctx)) {
            try {
                out.put("applied", 0);
                out.put("failures", failures);
                out.put("suspended", HookGuard.suspension(ctx));
            } catch (Throwable ignored) {
            }
            return out;
        }
        for (String peerKey : bridge.peerKeys(hook.packageName)) {
            try {
                push(bridge, peerKey, hook, moduleApk);
                applied++;
            } catch (Throwable t) {
                try {
                    failures.put(new JSONObject()
                            .put("process", peerKey)
                            .put("error", t.getMessage() == null ? String.valueOf(t)
                                    : t.getMessage()));
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            out.put("applied", applied);
            out.put("failures", failures);
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Takes one hook out of every live process of its package. */
    public static org.json.JSONArray clearFromAllProcesses(BridgeServer bridge, SavedHook hook) {
        JSONObject args = new JSONObject();
        try {
            args.put("subject", hook.className + "#" + hook.methodName);
        } catch (Throwable ignored) {
        }
        return bridge.requestAllProcesses(hook.packageName, "hook_clear", args, TIMEOUT_MS);
    }
}
