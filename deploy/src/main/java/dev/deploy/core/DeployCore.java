package dev.deploy.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The analysis backend all three frontends sit on.
 *
 * <p>The actual parsing lives in the native library {@code libdeploynative.so};
 * this class is a thin seam. {@link #nativeAnalyze} is the one door the C++
 * engine implements - currently a hand-written ELF header reader, later LIEF -
 * so swapping in the real engine changes nothing on this side. Every frontend
 * calls {@link #analyze} and never reads a file itself.
 */
public final class DeployCore {

    public static final String VERSION = "0.2.0";
    /** The native backend is now backed by the LIEF ELF parser. */
    public static final String BACKEND = "lief";

    /** The layers the engine will offer. {@code disasm} is reserved, not listed. */
    private static final String[] OPS = {
            "overview", "symbols", "relocations", "strings", "metadata", "resolve",
    };

    static {
        System.loadLibrary("deploynative");
    }

    /** Native seam: one op against one file, JSON in, JSON out. */
    private static native String nativeAnalyze(String op, String path, String argsJson);

    public String ping() {
        return "deploy " + VERSION + " (backend " + BACKEND + ")";
    }

    public JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", "deploy");
        o.put("version", VERSION);
        o.put("backend", BACKEND);
        o.put("abi", android.os.Build.SUPPORTED_ABIS[0]);
        JSONArray ops = new JSONArray();
        for (String op : OPS) {
            ops.put(op);
        }
        o.put("ops", ops);
        o.put("note", "LIEF ELF backend: overview, symbols, relocations, strings,"
                + " metadata and resolve are available; disasm remains reserved");
        return o;
    }

    /**
     * One op against one file.
     *
     * @param path absolute path to a shared object on disk, typically /sdcard
     * @param op   one of {@link #OPS}
     * @param args op-specific arguments, may be empty
     */
    public JSONObject analyze(String path, String op, JSONObject args) throws JSONException {
        return new JSONObject(nativeAnalyze(op, path, args == null ? "{}" : args.toString()));
    }
}
