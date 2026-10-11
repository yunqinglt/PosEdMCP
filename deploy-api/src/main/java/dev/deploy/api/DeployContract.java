package dev.deploy.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Shared by both MCP frontends, so advertised arguments match the AIDL backend. */
public final class DeployContract {
    public static final int MAX_LIBRARY_BYTES = 8 * 1024 * 1024;

    private DeployContract() {}

    public static JSONObject analysisProperties() {
        try {
            return buildProperties();
        } catch (JSONException e) {
            throw new IllegalStateException("invalid built-in analysis schema", e);
        }
    }

    private static JSONObject buildProperties() throws JSONException {
        JSONObject p = new JSONObject();
        p.put("path", field("string", "Absolute ELF path readable by Deploy, typically /sdcard/*.so"));
        p.put("op", field("string", "Analysis operation, default overview").put("enum",
                new JSONArray(new String[]{"overview", "symbols", "relocations", "strings",
                        "metadata", "resolve", "disasm", "decompile"})));
        p.put("name", field("string", "Symbol name for resolve, disasm or decompile; mutually exclusive with address"));
        p.put("address", field("string", "ELF virtual address, hexadecimal 0x...; not a live process address"));
        p.put("max", field("integer", "List limit; disasm default 64/max 512; decompile instruction budget default 4096/max 20000"));
        p.put("bytes", field("integer", "Disasm byte window, maximum 4096; default symbol size or 256"));
        p.put("mode", field("string", "ARM32 only: auto (symbol low bit), arm, thumb")
                .put("enum", new JSONArray(new String[]{"auto", "arm", "thumb"})));
        return p;
    }

    public static JSONObject assemblyProperties() {
        try {
            return new JSONObject().put("arch", architectureField())
                    .put("source", field("string", "Assembly instructions and labels, no directives; <=16384 chars/512 statements"))
                    .put("address", field("string", "Assembly origin as hexadecimal 0x..., default 0x0. Use final runtime address for PC-relative code."));
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public static JSONObject bufferProperties() {
        try {
            return new JSONObject().put("arch", architectureField())
                    .put("bytes", field("string", "1..4096 bytes, hexadecimal pairs without separators"))
                    .put("address", field("string", "Buffer virtual address in 0x... form; default 0x0"))
                    .put("max", field("integer", "Maximum decoded instructions, default 64, range 1..512"));
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    private static JSONObject architectureField() throws JSONException {
        return field("string", "Little-endian instruction architecture; x86 uses Intel syntax")
                .put("enum", new JSONArray(new String[]{"aarch64", "arm", "thumb", "x86", "x86_64"}));
    }

    private static JSONObject field(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }
}
