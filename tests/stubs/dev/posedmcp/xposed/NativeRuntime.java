package dev.posedmcp.xposed;

/** Host-only backend: records attempts to load or activate global native hooks. */
public final class NativeRuntime {
    public static boolean ready;
    public static int loads, configurations, lastCount;
    public static String error;
    public static boolean isReady() { return ready; }
    public static String ensureLoaded() { loads++; if (error == null) ready = true; return error; }
    public static String configureConcealmentFiles(String[] packages) {
        configurations++; lastCount = packages.length;
        return "active=" + (packages.length == 0 ? 0 : 1) + " api=1 failed=0";
    }
    public static String concealmentFilesStatus() { return "fixture"; }
    public static int mountConfigurations;
    public static boolean mountActive;
    public static void configureNativeMount(boolean active) { mountConfigurations++; mountActive = active; }
    public static String nativeMountStatus() { return "active=" + (mountActive ? 1 : 0) + " installed=1"; }
}
