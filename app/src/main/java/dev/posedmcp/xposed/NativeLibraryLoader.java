package dev.posedmcp.xposed;

import android.os.Process;
import android.util.Base64;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import dev.deploy.api.DeployContract;

/** Only registered by AppHost: loading runs as the selected scoped app, never as Deploy. */
public final class NativeLibraryLoader {
    private static final Map<String, Integer> LOADED = new HashMap<>();
    private NativeLibraryLoader() {}

    public static synchronized JSONObject load(String packageName, JSONObject args) throws Exception {
        if (packageName.equals("android") || packageName.equals("system_server")
                || packageName.equals("dev.posedmcp") || packageName.equals("dev.deploy"))
            throw new IllegalArgumentException("native loading requires an ordinary scoped application");
        String encoded = args.getString("library_base64");
        if (encoded.length() > ((DeployContract.MAX_LIBRARY_BYTES + 2) / 3) * 4)
            throw new IllegalArgumentException("native library exceeds transport limit");
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
        if (bytes.length < 64 || bytes.length > DeployContract.MAX_LIBRARY_BYTES
                || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F'
                || bytes[4] != 2 || bytes[5] != 1 || bytes[16] != 3 || bytes[17] != 0
                || (bytes[18] & 255) != 183 || bytes[19] != 0)
            throw new IllegalArgumentException("expected an arm64 ELF shared object");
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        String sha256 = hash.toString();
        if (!sha256.equals(args.getString("sha256")))
            throw new IllegalArgumentException("library SHA-256 does not match approved snapshot");
        String entry = args.optString("entry", "");
        if (!entry.isEmpty() && !entry.matches("[A-Za-z_][A-Za-z0-9_]{0,255}"))
            throw new IllegalArgumentException("invalid entry symbol");
        NativeRuntime.setModuleApk(args.getString("module_apk"));
        String unavailable = NativeRuntime.ensureLoaded();
        if (unavailable != null) throw new IllegalStateException(unavailable);
        boolean alreadyLoaded = LOADED.containsKey(sha256);
        Integer id = LOADED.get(sha256);
        if (id == null) {
            JSONObject loaded = new JSONObject(NativeRuntime.loadSnapshot(bytes, sha256));
            if (loaded.has("error")) throw new IllegalStateException(loaded.getString("error"));
            id = loaded.getInt("handleId");
            LOADED.put(sha256, id);
        }
        JSONObject result = new JSONObject().put("loaded", true).put("package", packageName)
                .put("pid", Process.myPid()).put("uid", Process.myUid()).put("sha256", sha256)
                .put("handleId", id).put("alreadyLoaded", alreadyLoaded)
                .put("loader", "android_dlopen_ext_memfd").put("persistent", false)
                .put("note", "Constructors execute on first load; no JNI_OnLoad call. Library remains until process exit.");
        if (!entry.isEmpty()) {
            long symbol = NativeRuntime.findSymbol(id, entry);
            if (symbol == 0) return result.put("error", "library loaded but entry symbol was not found");
            long value = NativeRuntime.call(symbol, 0, 0, 0, 0, 0, 0, 0);
            result.put("entry", entry).put("entryAddress", "0x" + Long.toHexString(symbol))
                    .put("entryResult", Long.toString(value));
        }
        return result;
    }
}
