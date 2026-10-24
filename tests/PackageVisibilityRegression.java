package fixture;

import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import dev.posedmcp.plugin.HookApi;
import dev.posedmcp.xposed.Framework;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Production PM callbacks and byte-level child stdout filtering, no Android runtime required. */
public final class PackageVisibilityRegression {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static Object invoke(String type, String name, Class<?>[] signature, Object... args) throws Exception {
        return RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed." + type, name, signature, args);
    }
    public static final class Entry {
        public String packageName;
        public Entry(String name) { packageName = name; }
    }
    public static final class Resolved {
        public Entry activityInfo;
        Resolved(String name) { activityInfo = new Entry(name); }
    }
    public static final class Pm {
        public List<Entry> getInstalledPackages(int flags) { return null; }
        public Entry getApplicationInfo(String name, int flags) { return null; }
        public Object getLaunchIntentForPackage(String name) { return null; }
        public boolean isPackageAvailable(String name) { return true; }
    }
    static HookApi.Callback callback(String name, Class<?>... signature) throws Exception {
        return (HookApi.Callback)invoke("PackageConcealment", "callback", new Class<?>[]{Method.class},
                Pm.class.getDeclaredMethod(name, signature));
    }
    static void packages() throws Throwable {
        String target = "fixture.app";
        String[] hidden = (String[])invoke("ConcealmentPackages", "forTarget", new Class<?>[]{String.class}, target);
        check(Arrays.asList(hidden).containsAll(List.of("dev.posedmcp", "dev.deploy", "dev.display", "org.lsposed.manager")),
                "companion and manager policy");
        check(!(Boolean)invoke("ConcealmentPackages", "hidden", new Class<?>[]{String.class,String.class}, "dev.deploy.fixture", target),
                "exact package boundary");
        check(!(Boolean)invoke("ConcealmentPackages", "hidden", new Class<?>[]{String.class,String.class}, "dev.deploy", "dev.deploy"),
                "never hide the target itself");
        List<Entry> original = List.of(new Entry("dev.posedmcp"), new Entry("android"), new Entry(target), new Entry("normal.app"));
        List<?> filtered = (List<?>)invoke("PackageConcealment", "filtered", new Class<?>[]{Object.class,String.class}, original, target);
        check(filtered.size() == 3 && original.size() == 4 && filtered.get(0) == original.get(1), "copy results without changing originals");
        Object same = invoke("PackageConcealment", "filtered", new Class<?>[]{Object.class,String.class}, List.of(new Entry("normal.app")), target);
        check(((List<?>)same).size() == 1, "preserve ordinary packages");
        List<Resolved> resolved = List.of(new Resolved("dev.display"), new Resolved("normal.app"));
        check(((List<?>)invoke("PackageConcealment", "filtered", new Class<?>[]{Object.class,String.class}, resolved, target)).size() == 1,
                "intent query component filtering");
        ParceledListSlice slice = new ParceledListSlice(original);
        ParceledListSlice copied = (ParceledListSlice)invoke("PackageConcealment", "filtered", new Class<?>[]{Object.class,String.class}, slice, target);
        check(copied != slice && copied.getList().size() == 3 && slice.getList().size() == 4, "Binder slice copy");
        Object array = invoke("PackageConcealment", "filtered", new Class<?>[]{Object.class,String.class}, new String[]{"dev.deploy","normal.app"}, target);
        check(Arrays.equals((String[])array,new String[]{"normal.app"}), "UID package array filtering");
        check(invoke("PackageConcealment", "filtered", new Class<?>[]{Object.class,String.class}, new String[]{"dev.deploy"}, target) == null,
                "unknown UID semantics when all packages hidden");
        Framework.adoptLibXposed(loader -> new RuntimePoliciesRegression.Hooks(), "fixture");
        invoke("ScopeConcealment", "configure", new Class<?>[]{ClassLoader.class,boolean.class}, PackageVisibilityRegression.class.getClassLoader(), true);
        invoke("PackageConcealment", "configure", new Class<?>[]{ClassLoader.class,boolean.class,String.class},
                PackageVisibilityRegression.class.getClassLoader(), true, target);
        HookApi.Callback lookup = callback("getApplicationInfo",String.class,int.class);
        RuntimePoliciesRegression.Param probe = new RuntimePoliciesRegression.Param(null, null, "dev.posedmcp", 0);
        lookup.before(probe); check(probe.thrown instanceof PackageManager.NameNotFoundException, "named PM probe reports not installed");
        RuntimePoliciesRegression.Param ordinary = new RuntimePoliciesRegression.Param(null, "keep", "normal.app", 0);
        lookup.before(ordinary); check(ordinary.thrown == null && ordinary.result.equals("keep"), "unselected lookup untouched");
        RuntimePoliciesRegression.Param launch = new RuntimePoliciesRegression.Param(null, "intent", "dev.posedmcp");
        callback("getLaunchIntentForPackage",String.class).before(launch); check(launch.result == null && launch.thrown == null, "launch probe returns null");
        RuntimePoliciesRegression.Param available = new RuntimePoliciesRegression.Param(null, true, "dev.deploy");
        callback("isPackageAvailable",String.class).before(available); check(Boolean.FALSE.equals(available.result), "availability probe false");
        RuntimePoliciesRegression.Param denied = new RuntimePoliciesRegression.Param(null, original, 0);
        denied.thrown = new SecurityException("permission");
        callback("getInstalledPackages",int.class).after(denied);
        check(denied.thrown instanceof SecurityException && denied.result == original, "original permission exception retained");
        invoke("PackageConcealment", "configure", new Class<?>[]{ClassLoader.class,boolean.class,String.class},
                PackageVisibilityRegression.class.getClassLoader(), false, target);
        RuntimePoliciesRegression.Param off = new RuntimePoliciesRegression.Param(null, "keep", "dev.posedmcp", 0);
        lookup.before(off); check(off.thrown == null && off.result.equals("keep"), "policy disabled");
    }
    static boolean command(String... args) throws Exception {
        return (Boolean)invoke("PackageListFilter", "command", new Class<?>[]{List.class}, Arrays.asList(args));
    }
    static void commands() throws Exception {
        check(command("pm","list","packages"), "pm command");
        check(command("/system/bin/cmd","package","list","packages","-f","-U"), "cmd command flags");
        check(command("sh","-c","'/system/bin/pm' list packages -U"), "simple shell command");
        check(!command("sh","-c","pm list packages; echo normal") && !command("echo","package:dev.posedmcp"), "unrelated and compound commands untouched");
        String input = "package:dev.posedmcp\r\npackage:normal.app\r\npackage:/data/app/base.apk=dev.deploy uid:10001\n"
                + "package:dev.deploy.fixture uid:10002\nwarning: dev.posedmcp\npackage:dev.display";
        Constructor<?> ctor = Class.forName("dev.posedmcp.xposed.PackageListFilter")
                .getDeclaredConstructor(InputStream.class,String.class,java.util.function.BooleanSupplier.class);
        ctor.setAccessible(true);
        boolean[] enabled = {true};
        try (InputStream stream = (InputStream)ctor.newInstance(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                "dev.deploy.fixture", (java.util.function.BooleanSupplier)() -> enabled[0])) {
            String out = new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            check(out.equals("package:normal.app\r\npackage:dev.deploy.fixture uid:10002\nwarning: dev.posedmcp\n"),
                    "raw bytes, CRLF, -f/-U, EOF and unrelated lines");
            check(stream.read() == -1, "stable EOF");
        }
        enabled[0] = false;
        try (InputStream stream = (InputStream)ctor.newInstance(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                "fixture.app", (java.util.function.BooleanSupplier)() -> enabled[0])) {
            check(new String(stream.readAllBytes(),StandardCharsets.UTF_8).equals(input), "disabled stream returns original bytes");
        }
    }
    public static void main(String[] args) throws Throwable {
        packages(); commands(); System.out.println("PASS package visibility (" + checks + " assertions)");
    }
}
