package dev.posedmcp.xposed;

import android.os.Process;
import org.json.JSONObject;

/** Bounded access inside one explicitly selected scoped application. Never registered in system_server. */
public final class NativeMemoryOps {
    private static void prepare(String pkg, JSONObject args) throws Exception {
        if (pkg.equals("android") || pkg.equals("system_server") || pkg.equals("dev.posedmcp") || pkg.equals("dev.deploy"))
            throw new IllegalArgumentException("choose an ordinary scoped application");
        if (args.getInt("pid") != Process.myPid()) throw new IllegalArgumentException("target PID changed");
        NativeRuntime.setModuleApk(args.getString("module_apk"));
        String unavailable = NativeRuntime.ensureLoaded();
        if (unavailable != null) throw new IllegalStateException(unavailable);
    }
    private static long address(String value) {
        if (!value.matches("0[xX][0-9a-fA-F]{1,16}")) throw new IllegalArgumentException("invalid address");
        long parsed = Long.parseUnsignedLong(value.substring(2), 16);
        if (parsed <= 0) throw new IllegalArgumentException("address is outside userspace");
        return parsed;
    }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    public static byte[] decode(String value) {
        if (!value.matches("[0-9a-fA-F]{8}")) throw new IllegalArgumentException("expected one 4-byte instruction");
        byte[] out = new byte[4];
        for (int i=0;i<4;i++) out[i]=(byte)Integer.parseInt(value.substring(2*i,2*i+2),16);
        return out;
    }
    public static JSONObject read(String pkg, JSONObject args) throws Exception {
        prepare(pkg,args);
        int length = args.getInt("length");
        if (length < 1 || length > 4096) throw new IllegalArgumentException("length must be 1..4096");
        byte[] data = NativeRuntime.readSnapshot(address(args.getString("address")), length);
        return new JSONObject().put("package",pkg).put("pid",Process.myPid()).put("uid",Process.myUid())
                .put("address",args.getString("address")).put("bytes",hex(data)).put("length",data.length)
                .put("arch", "aarch64").put("capturedAt",System.currentTimeMillis());
    }
    public static JSONObject patch(String pkg, JSONObject args) throws Exception {
        prepare(pkg,args);
        JSONObject result = new JSONObject(NativeRuntime.patchInstruction(address(args.getString("address")),
                decode(args.getString("expected")),decode(args.getString("bytes"))));
        return result.put("package",pkg).put("pid",Process.myPid()).put("uid",Process.myUid());
    }
}
