package fixture;

import dev.posedmcp.xposed.Framework;
import dev.posedmcp.xposed.NativeRuntime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.JSONObject;

/** Exercises the installed callbacks with both positive and ordinary controls. */
public final class EnvironmentConcealmentRegression {
    private static final ClassLoader LOADER = EnvironmentConcealmentRegression.class.getClassLoader();
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static Object call(String type, String method, Class<?>[] args, Object... values) throws Exception {
        return RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed." + type, method, args, values);
    }
    private static RuntimePoliciesRegression.Param param(Object receiver, Object result, Object... args) {
        return ExtendedConcealmentRegression.param(receiver, result, args);
    }
    private static final class Child extends Process {
        final InputStream stdout, stderr = new ByteArrayInputStream(new byte[]{42});
        Child(String text) { stdout = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
        public InputStream getInputStream() { return stdout; }
        public InputStream getErrorStream() { return stderr; }
        public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        public int waitFor() { return 23; }
        public int exitValue() { return 23; }
        public void destroy() {}
    }
    private static InputStream stdout(ExtendedConcealmentRegression.Hooks hooks, Child child) throws Throwable {
        var p = param(child, child.stdout);
        hooks.get(Child.class, "getInputStream").after(p);
        return (InputStream)p.result;
    }
    public static void main(String[] args) throws Throwable {
        var hooks = new ExtendedConcealmentRegression.Hooks();
        Framework.adoptLibXposed(loader -> hooks, "fixture");
        Class<?>[] toggle = {ClassLoader.class, boolean.class};
        call("ScopeConcealment", "configure", toggle, LOADER, true);
        call("ExtendedConcealment", "configure", toggle, LOADER, true);
        var named = hooks.get(System.class, "getenv", String.class);
        String path = "/system/bin:/debug_ramdisk/.magisk/bin::/sbin:/opt/magisk-tools:/sbin/su:";
        String clean = "/system/bin::/sbin:/opt/magisk-tools:";
        var p = param(null, path, "PATH"); named.after(p);
        check(clean.equals(p.result), "PATH removes only known entries, keeps order and existing empty fields");
        p = param(null, path, "OTHER"); named.after(p);
        check(p.result == path, "other environment entries untouched");
        var all = hooks.get(System.class, "getenv");
        Map<String,String> env = Map.of("PATH", path, "OTHER", "magisk");
        p = param(null, env); all.after(p);
        check(((Map<?,?>)p.result).equals(Map.of("PATH", clean, "OTHER", "magisk"))
                && env.get("PATH").equals(path), "getenv map is a copy and consistent with the named view");
        try { ((Map<?,?>)p.result).clear(); throw new AssertionError("writable getenv"); }
        catch (UnsupportedOperationException expected) { assertions++; }
        var prop = hooks.get(System.class, "getProperty", String.class);
        var fallback = hooks.get(System.class, "getProperty", String.class, String.class);
        p = param(null, "27", "magisk.version"); prop.after(p);
        check(p.result == null, "exact Java property looks absent");
        p = param(null, "27", "magisk.version", "default"); fallback.after(p);
        check("default".equals(p.result), "two argument property preserves caller default");
        for (String key : List.of("os.arch", "java.vm.name", "ro.debuggable", "magisk.version.extra")) {
            p = param(null, "real", key); prop.after(p);
            check("real".equals(p.result), "ordinary property " + key);
        }
        p = param(null, null, ""); p.thrown = new IllegalArgumentException("empty");
        prop.after(p); check(p.thrown instanceof IllegalArgumentException, "original property exception retained");

        String bad = "magisk /debug_ramdisk tmpfs rw 0 0";
        String good = "/dev/block/dm-1 /system erofs ro,tag=magisk 0 0";
        String info = "916 255 254:79 /adb/modules/zygisk_lsposed/bin/dex2oat64 /apex/dex2oat64 ro shared:68 - f2fs /dev/block/dm-79 rw";
        String infoGood = "916 255 254:79 /adb/modules/zygisk_lsposed_demo /apex/dex2oat64 ro shared:68 - f2fs /dev/block/dm-79 rw";
        var nio = hooks.get(Files.class, "readAllBytes", Path.class);
        byte[] mounts = (bad + "\r\n" + good).getBytes(StandardCharsets.UTF_8);
        for (String file : List.of("/proc/self/mounts", "/proc/mounts", "/proc/thread-self/mounts",
                "/proc/self/task/7/mounts", "/proc/self/mount")) {
            p = param(null, mounts, Paths.get(file)); nio.after(p);
            check(Arrays.equals((byte[])p.result, good.getBytes(StandardCharsets.UTF_8)), "mount alias " + file);
        }
        p = param(null, mounts, Paths.get("/proc/987654/mounts")); nio.after(p);
        check(p.result == mounts, "other PID untouched");
        for (String file : List.of("/data/example/mounts", "example/proc/self/mounts")) {
            p = param(null, mounts, Paths.get(file)); nio.after(p);
            check(p.result == mounts, "ordinary same-name file untouched: " + file);
        }
        p = param(null, (info + "\n" + infoGood + "\n").getBytes(StandardCharsets.UTF_8), Paths.get("/proc/self/mountinfo"));
        nio.after(p); check(new String((byte[])p.result, StandardCharsets.UTF_8).equals(infoGood + "\n"), "mountinfo field boundary");
        p = param(null, null, Paths.get("/proc/self/mount")); p.thrown = new NoSuchFileException("mount");
        nio.after(p); check(p.thrown instanceof NoSuchFileException, "singular path failure not fabricated");
        try (BufferedReader reader = new BufferedReader(new StringReader(bad + "\n" + bad + "\n" + good))) {
            hooks.constructors.get(BufferedReader.class.getName()).after(param(reader, null, "/proc/self/mounts"));
            p = param(reader, reader.readLine()); hooks.get(BufferedReader.class, "readLine").after(p);
            check(good.equals(p.result) && reader.readLine() == null, "reader skips only classified records");
        }

        call("PackageConcealment", "configure", new Class<?>[]{ClassLoader.class, boolean.class, String.class},
                LOADER, true, "example.app");
        var start = hooks.get(ProcessBuilder.class, "start");
        String mountLine = "magisk on /debug_ramdisk type tmpfs (rw)";
        String ordinary = "/dev/block/dm-1 on /system type erofs (ro,tag=magisk)";
        for (List<String> command : List.of(List.of("mount"), List.of("/system/bin/mount"),
                List.of("/system/bin/toybox", "mount"), List.of("sh", "-c", "mount"))) {
            Child raw = new Child(mountLine + "\r\n" + ordinary);
            p = param(new ProcessBuilder(command), raw); start.after(p);
            check(p.result == raw && new String(stdout(hooks, raw).readAllBytes(), StandardCharsets.UTF_8).equals(ordinary)
                    && raw.getErrorStream() == raw.stderr && raw.waitFor() == 23, "stdout only " + command);
        }
        Child raw = new Child(bad + "\n" + good + "\n");
        p = param(new ProcessBuilder("sh", "-c", "cat '/proc/self/mounts'"), raw); start.after(p);
        check(new String(stdout(hooks, raw).readAllBytes(), StandardCharsets.UTF_8)
                .equals(good + "\n"), "simple shell cat uses proc format");
        for (ProcessBuilder ordinaryCommand : List.of(new ProcessBuilder("mount", "-o", "rw"),
                new ProcessBuilder("sh", "-c", "mount | head"), new ProcessBuilder("cat", "/proc/42/mountinfo"),
                new ProcessBuilder("mount").redirectErrorStream(true),
                new ProcessBuilder("mount").redirectOutput(ProcessBuilder.Redirect.INHERIT))) {
            Child control = new Child(bad);
            p = param(ordinaryCommand, control); start.after(p);
            check(p.result == control && stdout(hooks, control) == control.stdout, "untouched " + ordinaryCommand.command());
        }
        String longRecord = "a".repeat(65536) + mountLine + "\n";
        Child oversized = new Child(longRecord);
        p = param(new ProcessBuilder("mount"), oversized); start.after(p);
        check(new String(stdout(hooks, oversized).readAllBytes(), StandardCharsets.UTF_8)
                .equals(longRecord), "oversized continuation is not mistaken for a new record");
        call("ExtendedConcealment", "configure", toggle, LOADER, false);
        p = param(null, path, "PATH"); named.after(p);
        check(p.result == path, "disable restores PATH");
        Child disabled = new Child(mountLine);
        p = param(new ProcessBuilder("mount"), disabled); start.after(p);
        check(p.result == disabled && stdout(hooks, disabled) == disabled.stdout, "disable leaves child unchanged");

        Class<?>[] nativeToggle = {boolean.class, String.class};
        call("NativeMountConcealment", "configure", nativeToggle, false, "example.app");
        call("NativeMountConcealment", "configure", nativeToggle, true, "com.greenpoint.android.mc10086.activity");
        check(NativeRuntime.loads == 0 && NativeRuntime.mountConfigurations == 0, "disabled and incompatible do not load native runtime");
        call("NativeMountConcealment", "configure", nativeToggle, true, "example.app");
        JSONObject status = (JSONObject)call("NativeMountConcealment", "status", new Class<?>[0]);
        check(status.getBoolean("enabled") && NativeRuntime.mountConfigurations == 1, "native opt-in is separate");
        call("NativeMountConcealment", "configure", nativeToggle, false, "example.app");
        check(!NativeRuntime.mountActive, "native disable clears active filtering");
        System.out.println("PASS environment and mount views (" + assertions + " assertions)");
    }
}
