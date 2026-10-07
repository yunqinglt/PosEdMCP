package dev.posedmcp.state;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import dev.posedmcp.HiddenApi;
import dev.posedmcp.Logx;
import dev.posedmcp.R;
import dev.posedmcp.root.RootShell;

/**
 * Where a bootloop gets broken from outside this app.
 *
 * <p>A kept hook inside system_server is put back before the user can reach the
 * page that would take it off - so if that hook crashes system_server, the app
 * that could fix it may never get to run at all. The way out therefore cannot
 * live in here. It lives in the {@code posedmcp-guard} Magisk module, which
 * counts boots that never finished and writes a kill-switch this class reads.
 *
 * <p>Two files and one property carry the whole conversation:
 * <ul>
 *   <li>{@value #MARKER_FILE} - written <b>here</b>, exists exactly while enabled
 *       hooks point at system_server. It is how the guard tells "this device is
 *       rebooting because of us" from "this device is rebooting".</li>
 *   <li>{@value #SUSPEND_FILE} - written by the guard, read here. Its presence
 *       means stop arming system hooks.</li>
 *   <li>{@code persist.posedmcp.no_system_hooks} - the same decision, in the one
 *       place a wiped /sdcard cannot take away. The guard sets both; the app
 *       believes either.</li>
 * </ul>
 *
 * <p>The files sit in the app's external media directory, which is where the
 * bridge credentials already go, and for the same reason: it carries no per-app
 * SELinux categories, so a script in a different domain can read it, while the
 * app's own data directory cannot be reached that way.
 *
 * <p>None of this replaces judgement - it is a floor for the case where the app
 * is not in a position to exercise any.
 */
public final class HookGuard {

    /** Value of the suspension property that means "not suspended". */
    private static final String LIFTED = "off";

    private static final String PROP_SUSPENDED = "persist.posedmcp.no_system_hooks";
    private static final String PROP_GUARD = "persist.posedmcp.guard";

    /** The guard's kill-switch, in the app's external media directory. */
    public static final String SUSPEND_FILE = "NO-SYSTEM-HOOKS";

    /** This app's note that enabled system hooks exist. */
    public static final String MARKER_FILE = "system-hooks.json";

    private HookGuard() {
    }

    /**
     * Whether the rescue module is installed and has run on this boot.
     *
     * <p>Read from a property rather than by looking for the module directory,
     * because /data/adb is not readable from an app and is not meant to be.
     */
    public static boolean guardInstalled() {
        return !property(PROP_GUARD, "").isEmpty();
    }

    /**
     * Why system hooks are suspended, or empty when they are not.
     *
     * <p>The property decides and the file explains. The property is the one
     * thing a reformatted /sdcard cannot take away, which matters in a case
     * where nothing is reliable; the file carries the same fact written out in
     * words, for showing to the user.
     */
    public static String suspension(Context context) {
        String fromProperty = property(PROP_SUSPENDED, "");
        String fromFile = "";
        File file = mediaFile(context, SUSPEND_FILE);
        if (file != null && file.isFile()) {
            fromFile = read(file);
        }
        if (!fromProperty.isEmpty()) {
            return fromFile.isEmpty() ? fromProperty : fromFile;
        }
        return fromFile;
    }

    /** Whether system hooks must not be armed right now. */
    public static boolean suspended(Context context) {
        return !suspension(context).isEmpty();
    }

    /**
     * Lifts the suspension, because the user pressed a button.
     *
     * <p>Root, because a {@code persist.} property is the only part of this that
     * is beyond an sdcard wipe. A button may reach for root where a tool may not,
     * for the same reason the accessibility repair does: the tap is the user's
     * own decision rather than an agent's request, and nothing they typed reaches
     * the command.
     *
     * @return {@code null} when it worked, otherwise what to tell the user
     */
    public static String liftSuspension(Context context) {
        File file = mediaFile(context, SUSPEND_FILE);
        if (file != null && file.exists() && !file.delete()) {
            Logx.w("could not delete " + file);
        }
        // A value rather than a removal: clearing a property needs a tool that is
        // not on this app's PATH, and "off" is a value the reader already treats
        // as absent.
        RootShell.Result result = RootShell.exec(
                "setprop " + PROP_SUSPENDED + " " + LIFTED, 10_000L);
        if (!result.ok()) {
            return context.getString(R.string.hookguard_lift_failed);
        }
        Logx.w("system-hook suspension lifted from the Hooks page");
        return null;
    }

    /**
     * Records how many enabled hooks point at system_server, or takes the note
     * away when none do.
     *
     * <p>Called from wherever the hook library changes rather than on a timer: the
     * guard has to be able to say "there were system hooks on disk when this
     * started going wrong", and a stale note would make it act on a device whose
     * problem is something else entirely.
     */
    public static void noteSystemHooks(Context context, int count) {
        File file = mediaFile(context, MARKER_FILE);
        if (file == null) {
            return;
        }
        if (count <= 0) {
            if (file.exists() && !file.delete()) {
                Logx.w("could not remove " + file);
            }
            return;
        }
        try {
            JSONObject note = new JSONObject();
            note.put("package", SavedHook.SYSTEM_PACKAGE);
            note.put("count", count);
            note.put("updatedAt", System.currentTimeMillis());
            writeAtomically(file, note + "\n");
        } catch (Throwable t) {
            Logx.w("could not write " + file + ": " + t);
        }
    }

    /** How many enabled system hooks the library holds, as last recorded. */
    public static int notedSystemHooks(Context context) {
        File file = mediaFile(context, MARKER_FILE);
        if (file == null || !file.isFile()) {
            return 0;
        }
        try {
            return new JSONObject(read(file)).optInt("count", 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- plumbing ---------------------------------------------------------

    private static File mediaFile(Context context, String name) {
        File[] dirs = context.getExternalMediaDirs();
        if (dirs == null || dirs.length == 0 || dirs[0] == null) {
            return null;
        }
        File dir = dirs[0];
        if (!dir.exists() && !dir.mkdirs()) {
            Logx.w("could not create " + dir);
            return null;
        }
        return new File(dir, name);
    }

    /** The platform's property store, which needs the hidden-API exemption. */
    private static String property(String key, String fallback) {
        try {
            HiddenApi.exempt();
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class, String.class)
                    .invoke(null, key, fallback);
            String text = value == null ? "" : String.valueOf(value).trim();
            return LIFTED.equals(text) ? "" : text;
        } catch (Throwable t) {
            // An unreadable property is not a reason to behave as though the
            // device is fine, but it is also not one to refuse to run: the file
            // is still consulted, and the app has no better answer available.
            return "";
        }
    }

    private static String read(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 64 * 1024)];
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            Logx.w("could not read " + file + ": " + t);
            return "";
        }
    }

    /** Write-then-rename, so the guard never reads a half-written note. */
    private static void writeAtomically(File target, String content) {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Throwable t) {
            Logx.w("could not write " + temp + ": " + t);
            return;
        }
        if (!temp.renameTo(target)) {
            Logx.w("could not move " + temp + " into place");
        }
    }
}
