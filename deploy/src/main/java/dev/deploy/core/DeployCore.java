package dev.deploy.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The analysis backend all three frontends sit on.
 *
 * <p>The actual parsing lives in the native library {@code libdeploynative.so};
 * this class is a thin seam. {@link #nativeAnalyze} is the one door the C++
 * engine implements using LIEF and Capstone. Every frontend
 * calls {@link #analyze} and never reads a file itself.
 */
public final class DeployCore {

    public static final String VERSION = "0.3.0";
    /** The native backend is now backed by the LIEF ELF parser. */
    public static final String BACKEND = "lief";

    /** Implemented operations. Decompilation has a separate, unavailable capability. */
    private static final String[] OPS = {
            "overview", "symbols", "relocations", "strings", "metadata", "resolve", "disasm",
    };

    static {
        System.loadLibrary("deploynative");
    }

    /** Native seam: one op against one file, JSON in, JSON out. */
    private static native String nativeAnalyze(String op, String path, String argsJson);
    private static native String nativeDisassemble(String path, String name, String address,
            int bytes, int count, String mode);

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
        o.put("disassembler", "capstone");
        o.put("decompile", new JSONObject().put("available", false).put("reason", "no decompiler engine installed"));
        o.put("runtimeInjection", false);
        o.put("note", "Read-only ELF analysis. Runtime loading is provided by PosEdMCP in a scoped app.");
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
        if ("decompile".equals(op)) {
            return new JSONObject().put("error", "C pseudocode engine is not installed; use disasm")
                    .put("code", "DECOMPILER_UNAVAILABLE").put("implemented", false);
        }
        if (args == null) args = new JSONObject();
        if ("disasm".equals(op)) {
            String name = args.optString("name", "");
            String address = args.optString("address", "");
            if (name.isEmpty() == address.isEmpty())
                throw new IllegalArgumentException("disasm requires exactly one of name or address");
            if (!address.isEmpty() && !address.matches("0[xX][0-9a-fA-F]{1,16}"))
                throw new IllegalArgumentException("address must be an ELF virtual address in 0x... form");
            int max = boundedInt(args, "max", 64, 1, 512);
            int bytes = boundedInt(args, "bytes", 0, 1, 4096);
            String mode = args.optString("mode", "auto");
            if (!mode.equals("auto") && !mode.equals("arm") && !mode.equals("thumb"))
                throw new IllegalArgumentException("mode must be auto, arm or thumb");
            return new JSONObject(nativeDisassemble(path, name, address, bytes, max, mode));
        }
        return new JSONObject(nativeAnalyze(op, path, args == null ? "{}" : args.toString()));
    }

    private static int boundedInt(JSONObject args, String key, int fallback, int min, int max)
            throws JSONException {
        if (!args.has(key)) return fallback;
        Object raw = args.get(key);
        if (!(raw instanceof Integer) && !(raw instanceof Long))
            throw new IllegalArgumentException(key + " must be an integer");
        long value = ((Number) raw).longValue();
        if (value < min || value > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return (int) value;
    }
}
