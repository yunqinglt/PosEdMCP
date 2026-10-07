package dev.posedmcp.state;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The hook library in the form another process can read it.
 *
 * <p>A hook used to be armed only when this app pushed it into a process that
 * had already connected. That put the app's liveness, credential resolution and
 * a socket round trip between a process starting and its hooks existing -
 * measured on the OnePlus at thirty-five seconds for one launch of a target,
 * against about seventy milliseconds for the next one. The screen a hook was
 * meant to change is long gone by thirty-five seconds.
 *
 * <p>So the same definitions travel out with the bridge credentials instead,
 * over the Binder call every injected process already makes at startup to get
 * them (see {@code BridgeTokenService}). Nothing new is exposed: it is the call
 * that hands out the bridge token, and the process receiving it is one the
 * module is loaded into, which can read the token out of its own memory anyway.
 *
 * <p><b>Why not a file.</b> The obvious cheaper channel - a mirror in this app's
 * {@code shared_prefs}, read through LSPosed's {@code XSharedPreferences} - was
 * written first and does not work here. On LSPosed 1.10.2 the class returns an
 * empty map: it reads the preferences file itself, from inside an application
 * that cannot open another's private directory, and nothing elevates it. That is
 * not specific to this file - it is why the bridge credentials already resolve
 * "via Binder service" rather than through the mirror that was written for them,
 * despite what {@code BridgeCredentials} claims about the daemon.
 *
 * <p>Only hooks aimed at ordinary applications are carried. Hooks aimed at
 * {@code system_server} keep the path they had, and re-arming those without the
 * app is exactly what the rescue module exists to prevent: it suspends them from
 * the outside when a kept hook has made the device unable to boot, and a module
 * that put them back by itself would defeat that at the moment it matters.
 */
public final class HookMirror {

    /**
     * Bundle and JSON key for the library, alongside the bridge credentials.
     *
     * <p>Read through {@code BridgeAuth.read}, which walks the same routes the
     * credentials do and takes the first that carries this key - in practice the
     * Binder service.
     */
    public static final String KEY = "hooks";

    private HookMirror() {
    }

    /** The whole library as JSON: every enabled hook that is not a system hook. */
    public static String json(List<SavedHook> hooks) {
        JSONArray array = new JSONArray();
        for (SavedHook hook : hooks) {
            if (!hook.enabled || hook.isSystemHook()) {
                continue;
            }
            try {
                array.put(hook.toJson());
            } catch (Throwable ignored) {
            }
        }
        return array.toString();
    }

    /**
     * The hooks in that JSON which belong to one package.
     *
     * <p>Empty when the blob is missing or unreadable. The caller carries on
     * either way: this is the fast path to a hook existing, not the authority on
     * whether it should. The app still pushes its library into every process
     * that connects, which is what makes a hook saved while that process is
     * already running take effect in it.
     */
    public static List<JSONObject> forPackage(String blob, String packageName) {
        List<JSONObject> out = new ArrayList<>();
        if (blob == null || blob.isEmpty()) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(blob);
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.optJSONObject(i);
                if (entry != null && packageName.equals(entry.optString("packageName", ""))) {
                    out.add(entry);
                }
            }
        } catch (Throwable t) {
            dev.posedmcp.Logx.w("the hook library is not readable JSON: " + t);
        }
        return out;
    }
}
