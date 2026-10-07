package dev.posedmcp.xposed;

import dev.posedmcp.plugin.HookApi;

/**
 * Which hook framework loaded this module, and the one thing that depends on it.
 *
 * <p>The module ships two entry points and does not choose between them — the
 * framework invokes the one it is built for. That is not a preference that
 * could be expressed any other way: the classic entry is handed an
 * {@code XposedBridge}, the modern one is handed an {@code XposedInterface}, and
 * neither can reach the other's. Which backend this module runs on was decided
 * by whoever called it.
 *
 * <p>So this is where the two entries meet. Whichever ran records itself here,
 * and everything downstream — the hook registry, {@code app.hook} in the Lua
 * runtime — asks for a {@link HookApi} without caring which one arrives.
 *
 * <p>Why both exist at all: Vector is LSPosed rewritten by the same author, and
 * it loads classic modules through a compatibility bridge that is thinner than
 * the real thing. Measured on Vector 2.2, {@code AndroidAppHelper.currentApplication()}
 * returns null there, so {@code app.context()} inside a scoped process was
 * always nil. The framework's own release notes describe the legacy bridge as
 * the fragile half. Running on each framework's native API is the way out of
 * emulating one with the other.
 */
public final class Framework {

    public enum Backend {
        /** {@code de.robv.android.xposed}, the 2012 API. LSPosed 1.x, and Vector's bridge. */
        CLASSIC,
        /** {@code io.github.libxposed.api}, the API Vector is built on. */
        LIBXPOSED,
    }

    /** Builds a {@link HookApi} for one application's class loader. */
    public interface Hooks {
        HookApi forClassLoader(ClassLoader loader);
    }

    private static volatile Backend backend = Backend.CLASSIC;
    private static volatile Hooks hooks = XposedHookApi::new;
    private static volatile String label = "";

    private Framework() {
    }

    /** Called by the classic entry, once per process. */
    public static void adoptClassic(String reportedLabel) {
        backend = Backend.CLASSIC;
        hooks = XposedHookApi::new;
        label = reportedLabel == null ? "" : reportedLabel;
    }

    /** Called by the modern entry, once per process. */
    public static void adoptLibXposed(Hooks factory, String reportedLabel) {
        backend = Backend.LIBXPOSED;
        hooks = factory;
        label = reportedLabel == null ? "" : reportedLabel;
    }

    public static Backend backend() {
        return backend;
    }

    /**
     * Which framework, and which of its two hook APIs this process is running on.
     *
     * <p>Both halves matter and they are not the same question. The first is the
     * framework's own name and version, which only it can answer. The second is
     * the API the hooks are built on - and on LSPosed those differ, because it
     * loads this module through its modern entry while the hooks stay on the
     * classic API it is actually good at.
     */
    public static String describe() {
        String what = label.isEmpty() ? "an unidentified framework" : label;
        return what + " · " + (backend == Backend.LIBXPOSED ? "libxposed hooks" : "classic hooks");
    }

    public static HookApi hookApi(ClassLoader loader) {
        return hooks.forClassLoader(loader);
    }

    /**
     * Whether the process we are running in is system_server.
     *
     * <p>Decided by this process's own {@code /proc/self/cmdline} whenever it can
     * be read, because it is the only signal here that cannot be about some other
     * process. The other two are hints the framework passed us, and one of them
     * is wrong in a way that is very expensive to find: the package name in a
     * callback is whichever package the framework happened to be loading, and in
     * {@code com.android.systemui}'s process that can be {@code android}.
     *
     * <p>Measured on Vector: taking that name as the answer put a second
     * {@code SystemHooks} inside systemui. Both copies then connected as the
     * {@code system} peer, and since a peer's key is its role, each new
     * connection closed the other's socket - a reconnect loop at one per second
     * that ran for hours, invisible in the UI, leaving every push to the system
     * peer racing a socket that was being torn down, and letting systemui answer
     * the system ops with its own privileges instead of system_server's.
     *
     * <p>The hints are still here, but only for a process whose own name cannot
     * be read; they no longer decide while the answer is available.
     */
    public static boolean isSystemServer(String packageName, String processName) {
        String cmdline = processNameFromCmdline();
        if (cmdline != null && !cmdline.isEmpty()) {
            return isSystemServerName(cmdline);
        }
        if ("android".equals(packageName)) {
            return true;
        }
        return isSystemServerName(processName);
    }

    /** The names the platform gives the system server process. */
    private static boolean isSystemServerName(String name) {
        return "system_server".equals(name) || "system".equals(name);
    }

    private static String processNameFromCmdline() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[128];
            int read = in.read(buffer);
            if (read <= 0) {
                return null;
            }
            int end = 0;
            while (end < read && buffer[end] != 0) {
                end++;
            }
            return new String(buffer, 0, end, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
