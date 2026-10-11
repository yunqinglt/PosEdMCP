package dev.posedmcp.state;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A local, bounded history of native libraries loaded through Deploy.
 *
 * <p>A native load is deliberately not represented as a persistent SavedHook:
 * the library has no unload operation and its lifetime ends with the target
 * process. Keeping the event here still makes the Hooks page answer the useful
 * question "what native code did I ask to enter?" without pretending that it is
 * a re-armed hook.
 */
public final class NativeInjectionStore {
    private static final String FILE = "native-injections";
    private static final String KEY = "entries";
    private static final int MAX_ENTRIES = 64;

    private final SharedPreferences prefs;

    private NativeInjectionStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    public static NativeInjectionStore of(Context context) {
        Context app = context.getApplicationContext();
        return new NativeInjectionStore((app == null ? context : app)
                .getSharedPreferences(FILE, Context.MODE_PRIVATE));
    }

    /** Records only a successful, user-approved load. */
    public synchronized void record(String packageName, String peer, String path,
            JSONObject library, JSONObject result) {
        JSONArray old = read();
        JSONArray next = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("package", packageName);
            item.put("peer", peer);
            item.put("path", path);
            item.put("sha256", library.optString("sha256", ""));
            item.put("size", library.optLong("size", 0L));
            item.put("handleId", result.optInt("handleId", 0));
            item.put("loader", result.optString("loader", ""));
            item.put("entry", result.optString("entry", ""));
            item.put("entryResult", result.optString("entryResult", ""));
            item.put("alreadyLoaded", result.optBoolean("alreadyLoaded", false));
            item.put("loadedAt", System.currentTimeMillis());
            for (int i = 0; i < old.length() && next.length() < MAX_ENTRIES - 1; i++) {
                JSONObject previous = old.optJSONObject(i);
                if (previous != null) next.put(previous);
            }
            next.put(item);
            prefs.edit().putString(KEY, next.toString()).commit();
        } catch (JSONException ignored) {
            // A history entry is diagnostic UI; a malformed optional field must
            // never turn a successful native load into an error.
        }
    }

    /** Newest first. Returned objects are copies so the UI cannot mutate storage. */
    public synchronized List<JSONObject> all() {
        JSONArray array = read();
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject value = array.optJSONObject(i);
            if (value != null) {
                try {
                    out.add(new JSONObject(value.toString()));
                } catch (JSONException ignored) {
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private JSONArray read() {
        try {
            return new JSONArray(prefs.getString(KEY, "[]"));
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }
}
