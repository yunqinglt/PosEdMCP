package dev.posedmcp.state;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The hooks the user has agreed to keep.
 *
 * <p>Same shape as {@link ScriptStore} and for the same reason: the set is
 * small, it is read whole whenever the app draws or a process connects, and a
 * database would be more machinery than the data is worth.
 *
 * <p>One entry per method per package. That is deliberately the same identity
 * the runtime uses ({@code class#method}), so the page can never show two rows
 * for what is one hook in the process, and registering a method again replaces
 * the entry instead of accumulating a near-duplicate.
 */
public final class HookStore {

    private static final String FILE = "hooks";
    private static final String KEY = "saved";

    private final SharedPreferences sp;
    /** Only for {@link HookGuard}, which needs somewhere to keep its note. */
    private final Context app;

    private HookStore(SharedPreferences sp, Context app) {
        this.sp = sp;
        this.app = app;
    }

    public static HookStore of(Context ctx) {
        Context app = ctx.getApplicationContext();
        return new HookStore(app == null
                ? ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                : app.getSharedPreferences(FILE, Context.MODE_PRIVATE),
                app == null ? ctx : app);
    }

    /** Newest first, which is the order the hook page shows them in. */
    public synchronized List<SavedHook> all() {
        List<SavedHook> hooks = new ArrayList<>(read());
        Collections.sort(hooks, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return hooks;
    }

    public synchronized SavedHook byId(String id) {
        for (SavedHook hook : read()) {
            if (hook.id.equals(id)) {
                return hook;
            }
        }
        return null;
    }

    /**
     * Every enabled hook for one package, in the order it was registered.
     *
     * <p>Used when a process connects: these are what get pushed back into it.
     */
    public synchronized List<SavedHook> armedFor(String packageName) {
        List<SavedHook> out = new ArrayList<>();
        for (SavedHook hook : read()) {
            if (hook.enabled && hook.packageName.equals(packageName)) {
                out.add(hook);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.createdAt, b.createdAt));
        return out;
    }

    /** The packages that have hooks, most recently touched first. */
    public synchronized List<String> packages() {
        Set<String> seen = new LinkedHashSet<>();
        for (SavedHook hook : all()) {
            seen.add(hook.packageName);
        }
        return new ArrayList<>(seen);
    }

    public synchronized List<SavedHook> forPackage(String packageName) {
        List<SavedHook> out = new ArrayList<>();
        for (SavedHook hook : all()) {
            if (hook.packageName.equals(packageName)) {
                out.add(hook);
            }
        }
        return out;
    }

    /**
     * Registers a hook, replacing whatever was registered on the same method.
     *
     * <p>Replacing rather than adding is the same call the runtime makes: hooking
     * a method twice leaves one hook, so the library has to agree with it or the
     * page and the process disagree about what is armed.
     *
     * <p>The recorded "last applied" is kept across an edit unless the part that
     * decides whether it can be installed at all has changed - a different
     * method, or a body that no longer resolves. Leaving a stale success on show
     * would tell the user a hook is armed in a process where it never was.
     */
    public synchronized SavedHook save(SavedHook incoming) {
        List<SavedHook> hooks = read();
        long now = System.currentTimeMillis();
        for (SavedHook existing : hooks) {
            if (existing.packageName.equals(incoming.packageName)
                    && existing.className.equals(incoming.className)
                    && existing.methodName.equals(incoming.methodName)) {
                boolean changedWhatIsHooked = !existing.sameInstall(incoming);
                if (changedWhatIsHooked) {
                    existing.lastAppliedAt = 0L;
                    existing.lastError = "";
                }
                incoming.id = existing.id;
                incoming.createdAt = existing.createdAt;
                incoming.updatedAt = now;
                if (!changedWhatIsHooked) {
                    incoming.lastAppliedAt = existing.lastAppliedAt;
                    incoming.lastError = existing.lastError;
                }
                int index = hooks.indexOf(existing);
                hooks.set(index, incoming);
                write(hooks);
                return incoming;
            }
        }
        incoming.id = UUID.randomUUID().toString();
        incoming.createdAt = now;
        incoming.updatedAt = now;
        hooks.add(incoming);
        write(hooks);
        return incoming;
    }

    public synchronized boolean setEnabled(String id, boolean enabled) {
        List<SavedHook> hooks = read();
        for (SavedHook hook : hooks) {
            if (hook.id.equals(id)) {
                hook.enabled = enabled;
                hook.updatedAt = System.currentTimeMillis();
                write(hooks);
                return true;
            }
        }
        return false;
    }

    public synchronized boolean delete(String id) {
        List<SavedHook> hooks = read();
        for (int i = 0; i < hooks.size(); i++) {
            if (hooks.get(i).id.equals(id)) {
                hooks.remove(i);
                write(hooks);
                return true;
            }
        }
        return false;
    }

    /** Records whether a process accepted this hook, for the page to show. */
    public synchronized void recordApplied(String id, boolean ok, String error) {
        List<SavedHook> hooks = read();
        for (SavedHook hook : hooks) {
            if (hook.id.equals(id)) {
                if (ok) {
                    hook.lastAppliedAt = System.currentTimeMillis();
                    hook.lastError = "";
                } else {
                    hook.lastError = error == null ? "" : error;
                }
                write(hooks);
                return;
            }
        }
    }

    private List<SavedHook> read() {
        List<SavedHook> hooks = new ArrayList<>();
        String blob = sp.getString(KEY, "");
        if (blob == null || blob.isEmpty()) {
            return hooks;
        }
        try {
            JSONArray array = new JSONArray(blob);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.optJSONObject(i);
                if (o != null) {
                    hooks.add(SavedHook.fromJson(o));
                }
            }
        } catch (Throwable ignored) {
            // Losing the library is better than refusing to open the page; the
            // hooks it described are gone from any process anyway by now.
        }
        return hooks;
    }

    private void write(List<SavedHook> hooks) {
        JSONArray array = new JSONArray();
        for (SavedHook hook : hooks) {
            try {
                array.put(hook.toJson());
            } catch (Throwable ignored) {
            }
        }
        // commit(), not apply(): a process that connects right after a toggle
        // reads this back on another thread, and a pending write would show it
        // the old definition.
        sp.edit().putString(KEY, array.toString()).commit();
        noteGuard(hooks);
    }

    /**
     * Keeps {@link HookGuard}'s note in step with what was just written.
     *
     * <p>Called from here because every path that changes the library goes
     * through {@link #write}, and the note is a claim about the current state of
     * it. A stale one would have the rescue module act on a bootloop that has
     * nothing to do with system hooks, which is the one way this whole mechanism
     * could make things worse instead of better.
     */
    private void noteGuard(List<SavedHook> hooks) {
        int system = 0;
        for (SavedHook hook : hooks) {
            if (hook.enabled && hook.isSystemHook()) {
                system++;
            }
        }
        HookGuard.noteSystemHooks(app, system);
    }
}
