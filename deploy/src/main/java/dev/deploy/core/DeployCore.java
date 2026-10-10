package dev.deploy.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * The analysis backend all three frontends sit on.
 *
 * <p>This is the seam where the real LIEF engine will plug in. Every frontend -
 * the AIDL service, the MCP service, and the dashboard - calls {@link #analyze}
 * and never reads a file itself, so swapping this class's body for a native LIEF
 * bridge later changes nothing above it.
 *
 * <p>For now the backend is a stub that parses the ELF header by hand. That is
 * enough to prove the plumbing end to end - a real file path reaches a real
 * parser and produces a real answer - without the NDK cross-compilation the
 * full engine needs. The remaining ops are reserved: their names and shapes are
 * fixed now, so nothing that consumes them has to change when they land.
 */
public final class DeployCore {

    public static final String VERSION = "0.1.0";
    /** {@code "stub"} until LIEF is cross-compiled in, then {@code "lief"}. */
    public static final String BACKEND = "stub";

    /** The layers the engine will offer. {@code disasm} is reserved, not listed. */
    private static final String[] OPS = {
            "overview", "symbols", "relocations", "strings", "metadata", "resolve",
    };

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
        if (BACKEND.equals("stub")) {
            o.put("note", "stub backend: only 'overview' parses anything real;"
                    + " the other ops are reserved interfaces");
        }
        return o;
    }

    /**
     * One op against one file.
     *
     * @param path absolute path to a shared object on disk, typically /sdcard
     * @param op   one of {@link #OPS}
     * @param args op-specific arguments, may be empty
     */
    public JSONObject analyze(String path, String op, JSONObject args) throws IOException, JSONException {
        switch (op) {
            case "overview":
                return overview(path);
            case "symbols":
            case "relocations":
            case "strings":
            case "metadata":
            case "resolve":
                return reserved(op);
            default:
                throw new IllegalArgumentException("unknown op '" + op + "'. Known ops: "
                        + String.join(", ", OPS));
        }
    }

    private JSONObject reserved(String op) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("backend", BACKEND);
        o.put("op", op);
        o.put("implemented", false);
        o.put("note", "op '" + op + "' is reserved for the LIEF backend and not implemented in"
                + " this stub. The interface is fixed; the engine is not built yet.");
        return o;
    }

    /**
     * Reads just enough of the ELF header to identify a file: magic, class,
     * endianness, type, machine and entry point. No external library, so this is
     * the one op that already produces a real answer.
     */
    private JSONObject overview(String path) throws IOException, JSONException {
        File file = new File(path);
        JSONObject o = new JSONObject();
        o.put("path", file.getAbsolutePath());
        o.put("exists", file.exists());
        if (!file.exists()) {
            o.put("error", "no such file");
            return o;
        }
        o.put("size", file.length());
        if (!file.canRead()) {
            o.put("error", "cannot read. Deploy is not root, so the file must be world-readable"
                    + " (e.g. already copied to /sdcard).");
            return o;
        }

        byte[] head = readAt(file, 0, 64);
        if (head.length < 20 || head[0] != 0x7f || head[1] != 'E' || head[2] != 'L'
                || head[3] != 'F') {
            o.put("elf", false);
            o.put("note", "not an ELF file");
            return o;
        }
        o.put("elf", true);

        int elfClass = head[4]; // EI_CLASS: 1 = 32-bit, 2 = 64-bit
        int elfData = head[5];  // EI_DATA: 1 = little-endian, 2 = big-endian
        boolean le = elfData == 1;
        int type = u16(head, 16, le);
        int machine = u16(head, 18, le);
        long entry = elfClass == 2 ? u64(head, 24, le) : u32(head, 24, le);

        o.put("class", elfClass == 2 ? "ELF64" : "ELF32");
        o.put("endianness", elfData == 1 ? "little" : "big");
        o.put("type", eType(type));
        o.put("machine", machine(machine));
        o.put("entry", "0x" + Long.toHexString(entry));
        return o;
    }

    private static byte[] readAt(File file, long offset, int length) throws IOException {
        byte[] buf = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            int n = raf.read(buf);
            if (n <= 0) {
                return new byte[0];
            }
            if (n == length) {
                return buf;
            }
            byte[] trimmed = new byte[n];
            System.arraycopy(buf, 0, trimmed, 0, n);
            return trimmed;
        }
    }

    private static int u16(byte[] b, int off, boolean le) {
        return le
                ? (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                : ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static long u32(byte[] b, int off, boolean le) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int shift = le ? i * 8 : (3 - i) * 8;
            v |= (long) (b[off + i] & 0xff) << shift;
        }
        return v;
    }

    private static long u64(byte[] b, int off, boolean le) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int shift = le ? i * 8 : (7 - i) * 8;
            v |= (long) (b[off + i] & 0xff) << shift;
        }
        return v;
    }

    private static String machine(int m) {
        switch (m) {
            case 183: return "AArch64";
            case 40:  return "ARM";
            case 62:  return "x86_64";
            case 3:   return "x86";
            case 8:   return "MIPS";
            default:  return "0x" + Integer.toHexString(m);
        }
    }

    private static String eType(int t) {
        switch (t) {
            case 1: return "REL";
            case 2: return "EXEC";
            case 3: return "DYN";
            case 4: return "CORE";
            default: return "0x" + Integer.toHexString(t);
        }
    }
}
