package dev.posedmcp.tools;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import dev.posedmcp.Logx;
import dev.posedmcp.ipc.BridgeServer;
import dev.posedmcp.ipc.Wire;
import dev.posedmcp.root.ConfirmationGate;
import dev.posedmcp.root.RootShell;

/**
 * Everything the device can be made to do, with the policy applied.
 *
 * <p>Two routes lead to the same capabilities:
 * <ul>
 *   <li>the <b>system bridge</b> - the module running inside system_server, using
 *       platform privileges, no shell involved;</li>
 *   <li>the <b>root shell</b> - {@code su}, which always prompts.</li>
 * </ul>
 * The route a tool picks determines which confirmation applies, so it is chosen
 * explicitly rather than left to a fallback chain that could quietly downgrade a
 * relaxed setting into an unconfirmed root command.
 */
public final class Capabilities {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_ROOT = "root";
    /** The accessibility service: no root, no hidden API, works while another app is in front. */
    public static final String MODE_A11Y = "a11y";

    private final Context context;
    private final BridgeServer bridge;

    public Capabilities(Context context, BridgeServer bridge) {
        this.context = context;
        this.bridge = bridge;
    }

    public Context context() {
        return context;
    }

    public boolean systemOnline() {
        return bridge != null && bridge.systemPeer() != null;
    }

    /** Whether the accessibility route is available. */
    public static boolean accessibilityOnline() {
        return dev.posedmcp.a11y.AccessibilityBridge.isConnected();
    }

    /**
     * Screen capture through the accessibility service.
     *
     * <p>Preferred over the system route wherever it is available: it needs no
     * root, and it is the only one of the two that still exists on Android 16.
     */
    public static Bitmap accessibilityScreenshot() throws IOException {
        return dev.posedmcp.a11y.AccessibilityBridge.screenshot(20_000L);
    }

    /**
     * Whether the system route for screenshots is worth offering.
     *
     * <p>Android 16 and later dropped {@code SurfaceControl.getPhysicalDisplayToken},
     * so the fast path can be dead even while the bridge is perfectly healthy.
     * system_server records why its last attempt failed; asking it avoids
     * spending a user approval on a route that is known not to work.
     */
    private volatile Boolean systemShotOk;

    public boolean systemScreenshotUsable() {
        if (!systemOnline()) {
            return false;
        }
        Boolean known = systemShotOk;
        if (known == null) {
            known = probeSystemScreenshot();
            systemShotOk = known;
        }
        return known;
    }

    private boolean probeSystemScreenshot() {
        try {
            String error = systemStatus().optString("lastScreenshotError", "");
            return error.isEmpty() || "not attempted".equals(error);
        } catch (Throwable t) {
            // Let it try once rather than disabling a route on a bad probe.
            return true;
        }
    }

    public void markSystemScreenshotBroken(String why) {
        Logx.w("system screenshot route disabled: " + why);
        systemShotOk = false;
    }

    // ---- system bridge ----------------------------------------------------

    private JSONObject systemCall(String op, JSONObject args, long timeoutMs) throws IOException {
        if (bridge == null) {
            throw new IOException("bridge is not running");
        }
        return bridge.request(Wire.ROLE_SYSTEM, op, args, timeoutMs);
    }

    public JSONObject systemStatus() throws IOException {
        return systemCall("status", new JSONObject(), 5_000L);
    }

    /** Asks system_server what the platform actually offers for screen capture. */
    public JSONObject systemDisplayProbe() throws IOException {
        return systemCall("probe_display", new JSONObject(), 10_000L);
    }

    /** @return raw PNG bytes captured by system_server */
    public byte[] systemScreenshot() throws IOException {
        JSONObject result = systemCall("screenshot", new JSONObject(), 20_000L);
        String base64 = result.optString("png_base64", "");
        if (base64.isEmpty()) {
            throw new IOException("system screenshot returned no data: " + result);
        }
        try {
            return Base64.decode(base64, Base64.DEFAULT);
        } catch (Throwable t) {
            throw new IOException("system screenshot was not valid base64", t);
        }
    }

    public JSONObject systemInput(JSONObject args) throws IOException {
        return systemCall("input", args, 15_000L);
    }

    public JSONObject systemForeground() throws IOException {
        return systemCall("foreground", new JSONObject(), 5_000L);
    }

    /**
     * Arms (or, with -1, disarms) the freeze guard in system_server: while
     * the probe holds a cgroup freeze, the ROM's own freezer manager and its
     * input ANR path must not thaw or kill that uid. The full identity (pid
     * and starttime) travels with the uid so the guard can refuse a recycled
     * pid. Best-effort - the guard protects the freeze, it is never the
     * freeze itself.
     */
    public void setProbeGuard(int uid, int pid, long starttime, long leaseMs) {
        try {
            JSONObject args = new JSONObject();
            args.put("uid", uid);
            args.put("pid", pid);
            args.put("starttime", starttime);
            args.put("lease_ms", leaseMs);
            systemCall("probe_guard", args, 3_000L);
        } catch (Throwable t) {
            Logx.w("probe: could not set the freeze guard: " + t);
        }
    }

    public JSONObject systemInvokePlugin(String pkg, String className, String method, String argsJson)
            throws IOException {
        JSONObject args = new JSONObject();
        try {
            args.put("class_name", className);
            args.put("method", method);
            args.put("args_json", argsJson == null ? "[]" : argsJson);
        } catch (Throwable t) {
            throw new IOException(t);
        }
        return appCall(pkg, "invoke_plugin", args, 20_000L);
    }

    public JSONObject appCall(String pkg, String op, JSONObject args, long timeoutMs)
            throws IOException {
        if (bridge == null) {
            throw new IOException("bridge is not running");
        }
        return bridge.requestAnyProcess(pkg, op, args, timeoutMs);
    }

    /** Every process of a package, for state that is per-process such as hooks. */
    public org.json.JSONArray appCallAll(String pkg, String op, JSONObject args, long timeoutMs)
            throws IOException {
        if (bridge == null) {
            throw new IOException("bridge is not running");
        }
        return bridge.requestAllProcesses(pkg, op, args, timeoutMs);
    }

    // ---- root shell -------------------------------------------------------

    /** A root shell command that already has the user's approval. */
    public RootShell.Result confirmedShell(String command, long timeoutMs) {
        return RootShell.exec(command, timeoutMs);
    }

    // ---- shared post-processing -------------------------------------------

    /**
     * Re-encodes a screenshot so an agent does not have to ingest a full-resolution
     * PNG. Screenshots of a modern phone are megabytes; downscaling them is the
     * difference between a usable tool and one that blows the context window.
     */
    /**
     * A screenshot after scaling and encoding, with what a caller needs to
     * describe it: an agent that can see the image still needs to know how far it
     * was scaled, or a coordinate read off the picture will not match the one
     * ui_dump reported.
     */
    public static final class Encoded {
        public final String base64;
        public final String mimeType;
        public final int width;
        public final int height;
        public final int bytes;

        Encoded(String base64, String mimeType, int width, int height, int bytes) {
            this.base64 = base64;
            this.mimeType = mimeType;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
        }
    }

    public static Encoded encodeImage(byte[] raw, String format, int maxDimension, int quality)
            throws IOException {
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Throwable t) {
            throw new IOException("could not decode screenshot", t);
        }
        if (bitmap == null) {
            throw new IOException("could not decode screenshot (unsupported format)");
        }
        return encodeImage(bitmap, format, maxDimension, quality);
    }

    /** As above, for callers that already hold the bitmap. Consumes it. */
    public static Encoded encodeImage(Bitmap bitmap, String format, int maxDimension, int quality)
            throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int longest = Math.max(width, height);
        if (maxDimension > 0 && longest > maxDimension) {
            float scale = maxDimension / (float) longest;
            int targetW = Math.max(1, Math.round(width * scale));
            int targetH = Math.max(1, Math.round(height * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
            if (scaled != bitmap) {
                bitmap.recycle();
                bitmap = scaled;
            }
        }

        Bitmap.CompressFormat compressFormat = "jpeg".equalsIgnoreCase(format)
                ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG;
        int effectiveQuality = compressFormat == Bitmap.CompressFormat.PNG ? 100 : quality;
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        int finalWidth = bitmap.getWidth();
        int finalHeight = bitmap.getHeight();
        try {
            if (!bitmap.compress(compressFormat, effectiveQuality, out)) {
                throw new IOException("bitmap compression failed");
            }
        } finally {
            bitmap.recycle();
        }
        byte[] encoded = out.toByteArray();
        return new Encoded(Base64.encodeToString(encoded, Base64.NO_WRAP),
                compressFormat == Bitmap.CompressFormat.JPEG ? "image/jpeg" : "image/png",
                finalWidth, finalHeight, encoded.length);
    }

    /** Confirmation helper so tools cannot forget to ask. */
    public static void require(Context ctx, ConfirmationGate.Kind kind, String title, String detail,
            String reason, String requester, long timeoutMs) throws McpDenied {
        ConfirmationGate.Decision decision = ConfirmationGate.request(ctx,
                new ConfirmationGate.Request(kind, title, detail, reason, requester, timeoutMs));
        if (!decision.approved) {
            throw new McpDenied(decision.note);
        }
    }

    /** Raised when the user (or a timeout) refused a privileged action. */
    public static final class McpDenied extends Exception {
        public McpDenied(String message) {
            super(message == null ? "denied" : message);
        }
    }

    /** Logs and rethrows, so a denial reaches the agent as a readable tool error. */
    public static String describeDenial(Exception e) {
        Logx.i("privileged action denied: " + e.getMessage());
        return e.getMessage();
    }
}
