package dev.posedmcp.tools;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import dev.posedmcp.Logx;
import dev.posedmcp.state.HookStore;
import dev.posedmcp.state.SavedHook;

/**
 * The hooks that ship inside this APK, behind a switch in Special settings.
 *
 * <p>These are not an agent's hooks. They are this app's own countermeasures
 * against the phone's background-app killer, and everything about them is
 * different from a hook an agent registers: they are written here rather than
 * asked for, they are aimed at the system framework rather than at an app, and
 * they are the kind of thing that can make a phone misbehave, which is why the
 * switch that turns one on is labelled dangerous.
 *
 * <p><b>Release, suspend, never delete.</b> The switch writes the definition
 * into the hook library with {@link SavedHook#special} set, so it is re-armed
 * after every restart exactly like any other kept hook; switching it off sets
 * {@code enabled} false, which leaves it in the library and out of every
 * process. Deleting is not offered, in the UI or to an agent - a countermeasure
 * the user has to hold down a confirmation to restore is worse than one they
 * can only pause. {@link #refresh} re-reads the asset on every app start, so a
 * script that changed in an update takes effect then, rather than whenever
 * somebody next flips the switch.
 *
 * <p>The source is an asset rather than a string in this file so that it reads
 * as the Lua it is, with its comments in place.
 */
public final class SpecialHooks {

    /** One switchable countermeasure. */
    public static final class Spec {
        /** Stable id: what {@link SavedHook#special} holds, and what the UI keys on. */
        public final String id;
        /** A package whose presence means this is the system the hook is for. */
        public final String marker;
        public final int titleRes;
        /** What turning it on does, and what it costs. Shown under the switch. */
        public final int blurbRes;
        /** Path under {@code assets/}. */
        public final String asset;

        public final String packageName;
        public final String className;
        public final String methodName;
        public final String params;

        Spec(String id, String marker, int titleRes, int blurbRes, String asset,
                String packageName, String className, String methodName, String params) {
            this.id = id;
            this.marker = marker;
            this.titleRes = titleRes;
            this.blurbRes = blurbRes;
            this.asset = asset;
            this.packageName = packageName;
            this.className = className;
            this.methodName = methodName;
            this.params = params;
        }
    }

    private static final List<Spec> ALL = new ArrayList<>();

    static {
        ALL.add(new Spec(
                "athena",
                // ColorOS. Athena is the framework that force-stops apps with
                // reason "o-stop(N)", which is what the Recents swipe and the
                // screen-off cleanup become.
                "com.oplus.athena",
                dev.posedmcp.R.string.special_athena_title,
                dev.posedmcp.R.string.special_athena_blurb,
                "special/athena.lua",
                SavedHook.SYSTEM_PACKAGE,
                "com.android.server.am.OplusAthenaAmManager",
                "forceStopPackage",
                "java.lang.String,int,int,int,java.lang.String,java.lang.String"));

        ALL.add(new Spec(
                "powerkeeper",
                // HyperOS. There is no single MIUI handler to sit on: the cleaners
                // are spread over several classes and com.miui.powerkeeper only
                // asks them to run, so the hook goes on the AMS method they all
                // end at, and the reason string keeps it narrow.
                "com.miui.powerkeeper",
                dev.posedmcp.R.string.special_powerkeeper_title,
                dev.posedmcp.R.string.special_powerkeeper_blurb,
                "special/powerkeeper.lua",
                SavedHook.SYSTEM_PACKAGE,
                "com.android.server.am.ActivityManagerService",
                "forceStopPackage",
                "java.lang.String,int,int,java.lang.String"));
    }

    private SpecialHooks() {
    }

    public static List<Spec> all() {
        return new ArrayList<>(ALL);
    }

    public static Spec byId(String id) {
        for (Spec spec : ALL) {
            if (spec.id.equals(id)) {
                return spec;
            }
        }
        return null;
    }

    /**
     * The countermeasures that make sense on this phone.
     *
     * <p>Decided by whether the package the hook is about is installed, not by
     * reading a ROM name out of a property. Those properties differ between
     * builds of the same system, whereas the component the hook exists for is
     * either here or it is not - and if it is not, the switch would do nothing.
     */
    public static List<Spec> available(Context ctx) {
        List<Spec> out = new ArrayList<>();
        for (Spec spec : ALL) {
            if (installed(ctx, spec.marker)) {
                out.add(spec);
            }
        }
        return out;
    }

    private static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- the library entry -------------------------------------------------

    /** This countermeasure's entry in the library, or null while it has never been on. */
    public static SavedHook stored(Context ctx, Spec spec) {
        for (SavedHook hook : HookStore.of(ctx).all()) {
            if (spec.id.equals(hook.special)) {
                return hook;
            }
        }
        return null;
    }

    public static boolean isOn(Context ctx, Spec spec) {
        SavedHook hook = stored(ctx, spec);
        return hook != null && hook.enabled;
    }

    /**
     * Turns one on: writes it into the library and pushes it out.
     *
     * <p>Writing the definition is all this does; arming it in the processes that
     * are up is the caller's, through the same service call every other hook
     * goes through. The script is re-read from the asset first, so what gets
     * armed is what this build ships rather than whatever was saved last time.
     */
    public static SavedHook turnOn(Context ctx, Spec spec) {
        SavedHook hook = build(ctx, spec);
        if (hook == null) {
            return null;
        }
        hook.enabled = true;
        HookStore.of(ctx).save(hook);
        Logx.i("special hook released: " + spec.id);
        return hook;
    }

    /**
     * Pauses one: it stays in the library, and leaves every process.
     *
     * <p>The returned hook still has to be taken out of the processes that are
     * up, and that is the half that matters: a hook merely marked disabled is
     * still installed until its process restarts, which for system_server means
     * until the phone reboots.
     */
    public static SavedHook turnOff(Context ctx, Spec spec) {
        SavedHook hook = stored(ctx, spec);
        if (hook == null) {
            return null;
        }
        hook.enabled = false;
        hook.updatedAt = System.currentTimeMillis();
        HookStore.of(ctx).save(hook);
        Logx.i("special hook suspended: " + spec.id);
        return hook;
    }

    /**
     * Brings stored definitions back in line with the assets they came from.
     *
     * <p>Called when the service starts. Without it a script fixed in an update
     * would keep running the old text until somebody happened to toggle the
     * switch, and nothing on screen would say so.
     */
    public static void refresh(Context ctx) {
        for (Spec spec : ALL) {
            SavedHook existing = stored(ctx, spec);
            if (existing == null) {
                continue;
            }
            SavedHook fresh = build(ctx, spec);
            if (fresh == null || fresh.source.equals(existing.source)) {
                continue;
            }
            Logx.i("special hook " + spec.id + " changed in this build; taking the new script");
            fresh.enabled = existing.enabled;
            HookStore.of(ctx).save(fresh);
        }
    }

    /** The library entry for a spec, built from its asset. */
    private static SavedHook build(Context ctx, Spec spec) {
        String source = read(ctx, spec.asset);
        if (source == null || source.isEmpty()) {
            Logx.w("special hook " + spec.id + " has no script at assets/" + spec.asset);
            return null;
        }
        SavedHook hook = new SavedHook();
        hook.packageName = spec.packageName;
        hook.className = spec.className;
        hook.methodName = spec.methodName;
        hook.params = spec.params;
        hook.body = SavedHook.BODY_LUA;
        hook.source = source;
        hook.effect = ctx.getString(spec.titleRes);
        hook.special = spec.id;
        return hook;
    }

    private static String read(Context ctx, String asset) {
        try {
            AssetManager assets = ctx.getAssets();
            try (InputStream in = assets.open(asset)) {
                byte[] buffer = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = in.read(buffer)) > 0) {
                    sb.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                }
                return sb.toString();
            }
        } catch (Throwable t) {
            Logx.w("could not read assets/" + asset + ": " + t);
            return null;
        }
    }
}
