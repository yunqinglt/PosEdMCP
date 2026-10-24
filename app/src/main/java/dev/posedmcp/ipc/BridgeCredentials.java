package dev.posedmcp.ipc;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import dev.posedmcp.Logx;

/**
 * Publishes the bridge credentials where a hooked process can actually read them.
 *
 * <p>The module runs inside other applications' processes, so it cannot read this
 * app's {@code SharedPreferences}: on Android 16 those moved to
 * {@code /data/misc/<uuid>/prefs/<pkg>}, a directory that is {@code drwx--x--x}
 * and unreadable by any other uid. Cross-app preference reads are simply gone.
 *
 * <p>The one channel that still works is the framework's own: LSPosed's
 * {@code XSharedPreferences} reads {@code /data/data/<pkg>/shared_prefs/<name>.xml}
 * and elevates access to it through its daemon, so the file does not have to be
 * world-readable and this app does not have to weaken anything. We therefore
 * write a plain XML mirror at that legacy path, with a name of its own so it
 * cannot collide with the real preferences file.
 */
public final class BridgeCredentials {

    /** File name under {@code shared_prefs/}; matches BridgeAuth's lookup. */
    public static final String FILE = "bridge";

    /**
     * File name under {@code Android/media/<pkg>/}.
     *
     * <p>External media is the one app-specific directory Android intends to be
     * readable by other applications: its SELinux label is {@code media_rw_data_file}
     * with no per-app categories, unlike {@code app_data_file}, whose categories
     * ({@code c141, c257, ...}) are exactly what stops one app reading another's
     * files no matter what the mode bits say.
     */
    public static final String MEDIA_FILE = "posedmcp-bridge.json";

    /** Where that directory lives, as an absolute path any process can build. */
    public static final String MEDIA_DIR = "/storage/emulated/0/Android/media/";

    private BridgeCredentials() {
    }

    /**
     * Writes the credentials everywhere a hooked process might look.
     *
     * <p>Best-effort throughout: the bridge has several routes and a failure on
     * one of them is logged, not fatal.
     */
    public static void publish(Context context, String bridgeToken, int bridgePort) {
        if (bridgeToken == null || bridgeToken.isEmpty()) {
            Logx.w("refusing to publish an empty bridge token");
            return;
        }
        publishToLegacyPrefs(context, bridgeToken, bridgePort);
        publishToExternalMedia(context, bridgeToken, bridgePort);
        BridgeBootstrap.publish(context);
    }

    private static void publishToLegacyPrefs(Context context, String bridgeToken, int bridgePort) {
        File dir = new File(context.getDataDir(), "shared_prefs");
        if (!dir.exists() && !dir.mkdirs()) {
            Logx.w("could not create " + dir);
            return;
        }

        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<map>\n"
                + "    <string name=\"bridge_token\">" + escape(bridgeToken) + "</string>\n"
                + "    <int name=\"bridge_port\" value=\"" + bridgePort + "\" />\n"
                + "</map>\n";

        writeAtomically(new File(dir, FILE + ".xml"), xml);
    }

    private static void publishToExternalMedia(Context context, String bridgeToken, int bridgePort) {
        File[] dirs = context.getExternalMediaDirs();
        if (dirs == null || dirs.length == 0 || dirs[0] == null) {
            Logx.w("no external media directory; other apps will have to use Binder");
            return;
        }
        File dir = dirs[0];
        if (!dir.exists() && !dir.mkdirs()) {
            Logx.w("could not create " + dir);
            return;
        }
        String json = "{\"bridge_token\":\"" + bridgeToken + "\",\"bridge_port\":" + bridgePort + "}\n";
        writeAtomically(new File(dir, MEDIA_FILE), json);
    }

    /** Write-then-rename, so a reader never sees a half-written file. */
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
            return;
        }
        Logx.i("bridge credentials published to " + target.getAbsolutePath());
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
