package dev.posedmcp;

/**
 * Lifts the hidden-API restrictions for this process.
 *
 * <p>Without this, reflection over platform internals silently under-reports:
 * {@code Class.getDeclaredMethods()} returns only the non-hidden members, and
 * invoking a hidden method fails as if it did not exist. That is exactly how the
 * screen-capture chain failed - {@code SurfaceControl.getPhysicalDisplayToken}
 * and every {@code ScreenCapture} method were invisible, so the code concluded
 * the build had no capture path when in fact it was never allowed to look.
 *
 * <p>The module is a legitimate consumer of these APIs - it runs inside
 * system_server - and the platform's own tooling exempts modules the same way.
 * This is a deliberate, process-local relaxation, not a sandbox escape: it only
 * affects what this process may reflect on.
 *
 * <p><b>It works only in a process the framework injects</b>, and that is the
 * platform's doing rather than this approach's: the call that grants the
 * exemption is itself on the block list, so a process that is not already exempt
 * cannot reach it. Measured on Android 15 with targetSdk 36 - a scoped app
 * process logs "hidden API exemptions installed", while this app's own process,
 * which is deliberately not in its own module scope, fails with
 * {@code NoSuchMethodException: VMRuntime.setHiddenApiExemptions}. Nothing is
 * broken by that on its own: the one platform internal this app process reflects
 * on, {@code SystemProperties}, is reachable anyway.
 *
 * <p>Which is why the refusal must not be allowed to settle. A process refused
 * once may still be the framework's guest later, and an earlier version of this
 * class marked the attempt done <i>before</i> making it - so one refusal was
 * permanent for the life of the process, which is the same silently-degraded
 * state this class exists to prevent.
 */
public final class HiddenApi {

    private static volatile boolean exempt;

    /** So a refusal is reported once, rather than once per caller. */
    private static volatile boolean refusalReported;

    private HiddenApi() {
    }

    /** Whether this process may reflect on platform internals, as last attempted. */
    public static boolean isExempt() {
        return exempt;
    }

    /**
     * Tries to lift the restriction, and keeps trying until it works.
     *
     * <p>Idempotent and safe from any thread. Once it has succeeded it never runs
     * again; until then every call retries, because the caller cannot know which
     * kind of process this is and only the attempt can say.
     */
    public static void exempt() {
        if (exempt) {
            return;
        }
        synchronized (HiddenApi.class) {
            if (exempt) {
                return;
            }
            try {
                Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
                Object runtime = vmRuntime.getDeclaredMethod("getRuntime").invoke(null);
                java.lang.reflect.Method setExemptions = vmRuntime
                        .getDeclaredMethod("setHiddenApiExemptions", String[].class);
                setExemptions.setAccessible(true);
                // "L" exempts every signature starting with L, i.e. all classes.
                setExemptions.invoke(runtime, (Object) new String[]{"L"});
                exempt = true;
                Logx.i("hidden API exemptions installed");
            } catch (Throwable t) {
                if (!refusalReported) {
                    refusalReported = true;
                    // Said once, because the state it describes does not change
                    // between calls and repeating it would bury it.
                    Logx.w("this process cannot lift the hidden-API restriction (" + t + ")."
                            + " That is expected outside a process the framework injects - the"
                            + " call that grants the exemption is itself on the block list - and"
                            + " it matters only if something here reflects on a platform internal,"
                            + " which would then report nothing instead of failing.");
                }
            }
        }
    }
}
