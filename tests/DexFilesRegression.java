package fixture;

import dev.posedmcp.plugin.HookApi;
import dev.posedmcp.xposed.Framework;
import dalvik.system.DexPathList;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Production discovery, Field.get and File callbacks; no synthetic missing fields. */
public final class DexFilesRegression {
    static int assertions;
    static void check(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
    static Object call(String type, String name, Class<?>[] types, Object... args) throws Exception {
        return RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed." + type, name, types, args);
    }
    public static void main(String[] args) throws Throwable {
        RuntimePoliciesRegression.Hooks hooks = new RuntimePoliciesRegression.Hooks();
        Framework.adoptLibXposed(loader -> hooks, "fixture");
        Class<?>[] config = {ClassLoader.class, boolean.class};
        ClassLoader loader = DexFilesRegression.class.getClassLoader();
        call("ScopeConcealment", "configure", config, loader, true);
        call("DexPathConcealment", "configure", config, loader, true);
        call("FrameworkFileConcealment", "configure", config, loader, true);
        DexPathList list = new DexPathList();
        DexPathList.Element app = new DexPathList.Element("/data/app/normal.app-HASH/base.apk");
        DexPathList.Element bridge = new DexPathList.Element("/system/framework/XposedBridge.jar");
        DexPathList.Element module = new DexPathList.Element("/data/app/~~hash/dev.posedmcp-HASH/base.apk");
        list.dexElements = new DexPathList.Element[]{app, bridge, module};
        Field field = DexPathList.class.getDeclaredField("dexElements");
        RuntimePoliciesRegression.Param discovery = new RuntimePoliciesRegression.Param(DexPathList.class, field, "dexElements");
        hooks.callbacks.get("java.lang.Class#getDeclaredField").after(discovery);
        check(discovery.result == field && discovery.thrown == null, "retain Field and successful lookup");
        RuntimePoliciesRegression.Param fields = new RuntimePoliciesRegression.Param(DexPathList.class, DexPathList.class.getDeclaredFields());
        Object originalFields = fields.result;
        hooks.callbacks.get("java.lang.Class#getDeclaredFields").after(fields);
        check(fields.result == originalFields, "retain fields and metadata");
        HookApi.Callback get = hooks.callbacks.get("java.lang.reflect.Field#get");
        RuntimePoliciesRegression.Param value = new RuntimePoliciesRegression.Param(field, list.dexElements, list);
        get.after(value);
        check(value.result instanceof DexPathList.Element[] && Array.getLength(value.result) == 1
                && Array.get(value.result, 0) == app, "typed array contains original app element");
        check(list.dexElements.length == 3, "cached dex array unchanged");
        Field unrelated = DexPathList.class.getDeclaredField("unrelated");
        RuntimePoliciesRegression.Param untouched = new RuntimePoliciesRegression.Param(unrelated, "keep", list);
        get.after(untouched); check(untouched.result.equals("keep"), "unrelated field untouched");
        list.nativeLibraryDirectories = List.of(new File("/system/lib64"), new File("/data/user/0/dev.posedmcp/lib"));
        Field libs = DexPathList.class.getDeclaredField("nativeLibraryDirectories");
        RuntimePoliciesRegression.Param libView = new RuntimePoliciesRegression.Param(libs, list.nativeLibraryDirectories, list);
        get.after(libView);
        check(((List<?>) libView.result).equals(List.of(new File("/system/lib64")))
                && list.nativeLibraryDirectories.size() == 2, "library list copy retains platform path");
        File jar = new File("/system/framework/./XposedBridge.jar");
        for (String method : List.of("exists", "isFile", "isDirectory", "canRead", "canWrite", "canExecute")) {
            RuntimePoliciesRegression.Param stat = new RuntimePoliciesRegression.Param(jar, true);
            hooks.callbacks.get("java.io.File#" + method).after(stat);
            check(stat.result.equals(false), "absent artifact " + method);
        }
        for (String method : List.of("length", "lastModified")) {
            RuntimePoliciesRegression.Param stat = new RuntimePoliciesRegression.Param(jar, 123L);
            hooks.callbacks.get("java.io.File#" + method).after(stat);
            check(stat.result.equals(0L), "absent metadata " + method);
        }
        RuntimePoliciesRegression.Param normal = new RuntimePoliciesRegression.Param(new File("/system/framework/framework.jar"), true);
        hooks.callbacks.get("java.io.File#exists").after(normal);
        check(normal.result.equals(true), "normal framework file retained");
        RuntimePoliciesRegression.Param similar = new RuntimePoliciesRegression.Param(new File("/system/framework/XposedBridge.jar.backup"), true);
        hooks.callbacks.get("java.io.File#exists").after(similar);
        check(similar.result.equals(true), "artifact exact boundary");
        RuntimePoliciesRegression.Param listing = new RuntimePoliciesRegression.Param(new File("/system/framework"), new String[]{"framework.jar", "XposedBridge.jar"});
        hooks.callbacks.get("java.io.File#list").after(listing);
        check(Arrays.equals((String[]) listing.result, new String[]{"framework.jar"}), "filter parent listing");
        for (String type : List.of("FileInputStream", "FileReader", "RandomAccessFile")) {
            RuntimePoliciesRegression.Param open = new RuntimePoliciesRegression.Param(null, null, jar);
            hooks.callbacks.get("java.io." + type + "#<init>").before(open);
            check(open.thrown instanceof FileNotFoundException, "consistent absent open " + type);
        }
        RuntimePoliciesRegression.Param error = new RuntimePoliciesRegression.Param(jar, null);
        error.thrown = new SecurityException("existing error");
        hooks.callbacks.get("java.io.File#exists").after(error);
        check(error.thrown instanceof SecurityException, "preserve original exception");
        call("DexPathConcealment", "configure", config, loader, false);
        call("FrameworkFileConcealment", "configure", config, loader, false);
        RuntimePoliciesRegression.Param off = new RuntimePoliciesRegression.Param(field, list.dexElements, list);
        get.after(off); check(off.result == list.dexElements, "disabled dex view unchanged");
        RuntimePoliciesRegression.Param offFile = new RuntimePoliciesRegression.Param(jar, true);
        hooks.callbacks.get("java.io.File#exists").after(offFile);
        check(offFile.result.equals(true), "disabled artifact view unchanged");
        System.out.println("PASS dex and framework files (" + assertions + " assertions)");
    }
}
