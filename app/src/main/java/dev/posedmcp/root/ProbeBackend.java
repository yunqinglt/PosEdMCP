package dev.posedmcp.root;

import dev.posedmcp.Logx;

/**
 * Which freeze backend this device can use, and which one a freeze actually
 * ran on.
 *
 * <p>The cgroup v2 freezer is preferred wherever it exists: one write freezes
 * an app's whole uid cgroup - every process, and any process it spawns later,
 * which per-pid SIGSTOP cannot match - and the release is an idempotent write
 * rather than a signal dance around recycled pids. The trade-off, measured on
 * both handsets, is that the ROMs run their own background-app freezer on the
 * same files: they can release a cgroup we froze, which is why the label in
 * Special settings says experimental. The signal backend is the fallback and
 * stays fully intact.
 *
 * <p>Detection only asks whether the v2 freezer exists and is writable; it
 * never writes, because a test freeze on the wrong cgroup would freeze the
 * phone.
 */
public final class ProbeBackend {

    /** What a freeze used. "cgroup" or "signal". */
    public enum Kind { CGROUP, SIGNAL }

    private static volatile Kind detected;

    private ProbeBackend() {
    }

    /** The capability of this device, probed once per process. */
    public static Kind detect() {
        Kind kind = detected;
        if (kind != null) {
            return kind;
        }
        kind = probe();
        detected = kind;
        Logx.i("probe: freeze backend is " + (kind == Kind.CGROUP ? "cgroup.freeze" : "signals"));
        return kind;
    }

    private static Kind probe() {
        try {
            // /proc/self inside su is the su child, which KernelSU keeps in
            // the root cgroup - so the check uses this app's own pid, whose
            // v2 cgroup line names its uid cgroup. The v2 root has no
            // cgroup.freeze at all, which is why a path must be derived
            // rather than tested at the top. Nothing is written: a test
            // freeze on the wrong cgroup would freeze the phone, and
            // existence plus writability is all the answer needs.
            int pid = android.os.Process.myPid();
            RootShell.Result r = RootShell.exec(
                    "d=$(awk -F: '$1==\"0\"{print $3}' /proc/" + pid + "/cgroup | head -1);"
                            + " d=$(dirname \"$d\");"
                            + " [ \"$d\" = \"/\" ] && exit 1;"
                            + " test -f \"/sys/fs/cgroup$d/cgroup.freeze\""
                            + " && test -w \"/sys/fs/cgroup$d/cgroup.freeze\""
                            + " && echo cgroup",
                    5_000L);
            return r.ok() && r.stdout.contains("cgroup") ? Kind.CGROUP : Kind.SIGNAL;
        } catch (Throwable t) {
            return Kind.SIGNAL;
        }
    }
}
