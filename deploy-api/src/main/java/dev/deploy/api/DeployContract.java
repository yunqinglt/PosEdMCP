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
        p.put("name", field("string", "Symbol name for resolve or disasm; mutually exclusive with address"));
        p.put("address", field("string", "ELF virtual address, hexadecimal 0x...; not a live process address"));
        p.put("max", field("integer", "List limit; disasm defaults to 64 instructions, maximum 512"));
        p.put("bytes", field("integer", "Disasm byte window, maximum 4096; default symbol size or 256"));
        p.put("mode", field("string", "ARM32 only: auto (symbol low bit), arm, thumb")
                .put("enum", new JSONArray(new String[]{"auto", "arm", "thumb"})));
        return p;
    }

    private static JSONObject field(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }
}
