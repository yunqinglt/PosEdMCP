package dev.posedmcp.xposed;

import android.os.SystemClock;

import dev.posedmcp.Logx;
import dev.posedmcp.plugin.HookApi;

/**
 * The freeze guard for the probe's cgroup freeze, running inside system_server.
 *
 * <p>ColorOS's own freezer manager (HANS) and its input ANR path would undo
 * what the probe froze: HANS posts an unfreeze when the input dispatcher
 * finds a window unresponsive, and a frozen app that cannot confirm focus
 * earns a "bg anr" kill a few seconds later - measured, the unfreeze intent
 * arrives and then AMS kills the pid for a {@code FocusEvent(hasFocus=false)}
 * timeout. This guard holds one narrow, expiring session (a uid, a pid, a
 * starttime) and, only while it is armed, deflects the unfreeze intents and
 * the ANR kill for exactly that target. Everything else flows through.
 *
 * <p>It is Java rather than Lua because the session needs a real deadline
 * and a field read, and because answering for a boolean-returning method
 * with an empty result is what once crashed system_server: here the boolean
 * path returns {@code false} and the void paths skip cleanly.
 */
public final class FreezeGuard {

    private static volatile int uid = -1;
    private static volatile int pid = -1;
    private static volatile long starttime = -1;
    /** {@link SystemClock#elapsedRealtime()} the lease runs out. */
    private static volatile long leaseEndsAt;

    private static volatile boolean installed;

    private FreezeGuard() {
    }

    /** True on ColorOS - the ROM this guard exists for, keyed on HANS. */
    public static boolean isColorOS(ClassLoader loader) {
        try {
            return Class.forName("com.android.server.am.OplusHansManager", false, loader) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Installs the hooks once, from system_server. The ANR kill guard is
     * generic Android and installs on every ROM; the HANS unfreeze guards are
     * ColorOS-only. Every hook degrades on its own: one that cannot install
     * logs the fact and the rest still stand - the guard is an enhancement
     * over the freeze, never a precondition of it.
     */
    public static synchronized void install(ClassLoader loader) {
        if (installed) {
            return;
        }
        installed = true;
        installAnrKillGuard(loader);
        if (isColorOS(loader)) {
            installHansGuards(loader);
        } else {
            Logx.i("freeze guard: not ColorOS; the ANR kill guard alone is installed");
        }
    }

    /**
     * The ANR kill's execution entry, on every ROM. Skipping it before its
     * side effects leaves AMS consistent - the process stays marked alive,
     * which is true. Scoped to the guarded uid and to an ANR reason.
     */
    private static void installAnrKillGuard(ClassLoader loader) {
        try {
            if (Class.forName("com.android.server.am.ProcessRecord", false, loader) == null) {
                Logx.w("freeze guard: ProcessRecord not resolvable; no ANR kill guard");
                return;
            }
            HookApi api = Framework.hookApi(loader);
            api.hookMethod("com.android.server.am.ProcessRecord", "killLocked",
                    new Class<?>[]{String.class, String.class, int.class, int.class,
                            boolean.class, boolean.class},
                    new HookApi.Callback() {
                        @Override
                        public void before(HookApi.HookParam param) {
                            Object reasonArg = param.args()[0];
                            String reason = reasonArg == null ? "" : reasonArg.toString();
                            if (!reason.toLowerCase().contains("anr")) {
                                return;
                            }
                            try {
                                Object u = param.getObjectField("uid");
                                if (u instanceof Integer && guards((Integer) u)) {
                                    Logx.i("freeze guard: blocked anr kill of uid " + u
                                            + " (" + reason + ")");
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                Logx.w("freeze guard: could not read the kill's uid: " + t);
                            }
                        }
                    });
            Logx.i("freeze guard: ANR kill guard installed");
        } catch (Throwable t) {
            Logx.e("freeze guard: ANR kill guard unavailable: " + t);
        }
    }

    /** The HANS unfreeze guards. ColorOS only - HANS exists nowhere else. */
    private static void installHansGuards(ClassLoader loader) {
        try {
            HookApi api = Framework.hookApi(loader);

            // HANS unfreezes on input dispatch - the path a frozen app takes
            // when it loses focus. Void; skipped entirely while guarded.
            api.hookMethod("com.android.server.am.OplusHansManager",
                    "unFreezeForInputDispatcher", new Class<?>[]{int.class},
                    new HookApi.Callback() {
                        @Override
                        public void before(HookApi.HookParam param) {
                            Object a = param.args()[0];
                            if (a instanceof Integer && guards((Integer) a)) {
                                Logx.i("freeze guard: blocked input unfreeze of uid " + a);
                                param.setResult(null);
                            }
                        }
                    });

            // HANS's general unfreeze. Returns boolean: false is the honest
            // answer for a guarded uid, and the value the caller expects.
            api.hookMethod("com.android.server.am.OplusHansManager",
                    "hansUnFreeze",
                    new Class<?>[]{int.class, String.class, String.class},
                    new HookApi.Callback() {
                        @Override
                        public void before(HookApi.HookParam param) {
                            Object a = param.args()[0];
                            if (a instanceof Integer && guards((Integer) a)) {
                                Logx.i("freeze guard: blocked HANS unfreeze of uid " + a);
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    });

            Logx.i("freeze guard: HANS guards installed");
        } catch (Throwable t) {
            Logx.e("freeze guard: HANS guards unavailable: " + t);
        }
    }

    /** Arms the session. The lease runs on elapsedRealtime, not wall clock. */
    public static synchronized void arm(int newUid, int newPid, long newStarttime, long leaseMs) {
        uid = newUid;
        pid = newPid;
        starttime = newStarttime;
        leaseEndsAt = SystemClock.elapsedRealtime() + Math.max(1000L, leaseMs);
        Logx.i("freeze guard: armed for uid " + uid + " pid " + pid
                + " for " + (leaseMs / 1000L) + "s");
    }

    public static synchronized void disarm() {
        if (uid <= 0) {
            return;
        }
        Logx.i("freeze guard: disarmed (was uid " + uid + ")");
        uid = -1;
        pid = -1;
        starttime = -1;
    }

    /** The uid the guard currently protects, or -1. */
    public static int guardedUid() {
        return uid;
    }

    private static boolean guards(int candidate) {
        int u = uid;
        return u > 0 && candidate == u && SystemClock.elapsedRealtime() < leaseEndsAt;
    }
}
