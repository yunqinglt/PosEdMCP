package fixture;

import dev.posedmcp.plugin.HookApi;
import dev.posedmcp.state.DomainRules;
import dev.posedmcp.diagnostics.DiagnosticRedactor;
import dev.posedmcp.xposed.Framework;
import dev.posedmcp.xposed.LibXposedHookApi;
import io.github.libxposed.api.XposedInterface;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Executes production callbacks with external-app callers and real java.io readers. */
public final class RuntimePoliciesRegression {
    static int assertions;
    static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    static final class Param implements HookApi.HookParam {
        Object receiver, result; Object[] args; Throwable thrown;
        Param(Object receiver, Object result, Object... args) {
            this.receiver = receiver; this.result = result; this.args = args;
        }
        public Object thisObject() { return receiver; }
        public Object result() { return result; }
        public Object[] args() { return args; }
        public Throwable throwable() { return thrown; }
        public void setResult(Object value) { result = value; thrown = null; }
        public void setThrowable(Throwable value) { result = null; thrown = value; }
        public void setObjectField(String name, Object value) { throw new UnsupportedOperationException(); }
        public Object getObjectField(String name) { throw new UnsupportedOperationException(); }
    }
    static final class Hooks implements HookApi {
        Map<String, Callback> callbacks = new HashMap<>();
        public Unhook hook(Member m, Callback cb) {
            callbacks.put(m.getDeclaringClass().getName() + "#" + m.getName(), cb);
            return () -> {};
        }
        public Unhook hook(Constructor<?> c, Callback cb) { return hook((Member)c, cb); }
        public Unhook hookAllConstructors(String c, Callback cb) { callbacks.put(c + "#<init>", cb); return () -> {}; }
        public Unhook hookMethod(String c, String m, Class<?>[] p, Callback cb) { throw new UnsupportedOperationException(); }
        public Unhook hookAllMethods(String c, String m, Callback cb) { throw new UnsupportedOperationException(); }
        public Method findMethod(Class<?> c, String m, Class<?>... p) { return null; }
    }
    static Object callStatic(String cls, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = Class.forName(cls).getDeclaredMethod(name, types); m.setAccessible(true);
        return m.invoke(null, args);
    }
    static void domains() {
        check(Arrays.equals(DomainRules.parse("# comment\nADS.Example.COM.\n*.Example.com\nADS.example.com\n"),
                new String[]{"ads.example.com", "*.example.com"}), "normalize and deduplicate");
        check(DomainRules.parse("广告.例子.中国")[0].startsWith("xn--"), "IDN normalization");
        for (String bad : new String[]{"https://ads.example.com", "*", "com", "127.0.0.1", "a..example.com",
                "bad/example.com", "a.*.com", ".example.com", "a.-b.com", "localhost"}) {
            try { DomainRules.parse(bad); throw new AssertionError("accepted " + bad); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 257; i++) large.append("ad").append(i).append(".example.com\n");
        try { DomainRules.parse(large.toString()); throw new AssertionError("accepted too many domains"); }
        catch (IllegalArgumentException expected) { assertions++; }
    }
    static void redaction() {
        String secret = "abc123_TEST";
        String input = "Bearer abc.def==\n{\"bridge_token\":\"old-key\",\"mcp_token\":\"another\"}\n" + secret;
        String out = DiagnosticRedactor.redact(input, secret);
        check(!out.contains("abc.def") && !out.contains("old-key") && !out.contains("another")
                && !out.contains(secret), "redact current and old credentials");
        check(DiagnosticRedactor.redact("ordinary log of example.app").equals("ordinary log of example.app"),
                "retain useful logs");
        check(!DiagnosticRedactor.redact("<string name=\"bridge_token\">old-secret</string>")
                .contains("old-secret"), "redact XML token values");
        String oldToken = "a1".repeat(32);
        check(!DiagnosticRedactor.redact("old escaped or unnamed credential " + oldToken).contains(oldToken),
                "redact historical generated tokens");
    }
    static void concealment() throws Throwable {
        Hooks hooks = new Hooks();
        Framework.adoptLibXposed(loader -> hooks, "fixture");
        callStatic("dev.posedmcp.xposed.ScopeConcealment", "configure",
                new Class<?>[]{ClassLoader.class, boolean.class}, RuntimePoliciesRegression.class.getClassLoader(), true);
        check(hooks.callbacks.size() == 8, "register internal hook families");
        check(Class.forName("dev.posedmcp.xposed.HookRegistry") != null, "no registry dependency on policy calls");
        HookApi.Callback lookup = hooks.callbacks.get("java.lang.ClassLoader#loadClass");
        Param p = new Param(new ClassLoader(){}, null, "dev.posedmcp.HiddenApi");
        lookup.before(p); check(p.thrown instanceof ClassNotFoundException, "hide module class lookup");
        Param own = new Param(RuntimePoliciesRegression.class.getClassLoader(), null, "dev.posedmcp.HiddenApi");
        lookup.before(own); check(own.thrown == null, "retain module loader access");
        Param ordinary = new Param(new ClassLoader(){}, null, "example.app.MainActivity");
        lookup.before(ordinary); check(ordinary.thrown == null, "ordinary class unchanged");
        Param trace = new Param(null, "de.robv.android.xposed.XposedBridge");
        hooks.callbacks.get("java.lang.StackTraceElement#getClassName").after(trace);
        check(trace.result.equals("android.os.Handler"), "rewrite module stack class");
        Param traceOther = new Param(null, "example.app.Crash");
        hooks.callbacks.get("java.lang.StackTraceElement#getClassName").after(traceOther);
        check(traceOther.result.equals("example.app.Crash"), "retain application stack class");
        Method javaMethod = String.class.getDeclaredMethod("isEmpty");
        Method jni = Object.class.getDeclaredMethod("hashCode");
        callStatic("dev.posedmcp.xposed.HookVisibility", "note", new Class<?>[]{Method.class}, javaMethod);
        callStatic("dev.posedmcp.xposed.HookVisibility", "note", new Class<?>[]{Method.class}, jni);
        Param javaModifiers = new Param(javaMethod, Modifier.PUBLIC | Modifier.NATIVE);
        HookApi.Callback modifiers = hooks.callbacks.get("java.lang.reflect.Method#getModifiers");
        modifiers.after(javaModifiers);
        check(javaModifiers.result.equals(Modifier.PUBLIC), "mask synthetic native only");
        Param nativeModifiers = new Param(jni, Modifier.PUBLIC | Modifier.NATIVE);
        modifiers.after(nativeModifiers);
        check(nativeModifiers.result.equals(Modifier.PUBLIC | Modifier.NATIVE), "preserve genuine JNI native bit");
        Object stream = new Object(), decoder = new Object();
        hooks.callbacks.get("java.io.FileInputStream#<init>").after(new Param(stream, null, "/proc/self/maps"));
        hooks.callbacks.get("java.io.InputStreamReader#<init>").after(new Param(decoder, null, stream));
        String hidden = "7000-8000 r-xp /data/app/dev.posedmcp-xxx/base.apk";
        String real = "8000-9000 r-xp /system/lib64/libc.so";
        BufferedReader reader = new BufferedReader(new StringReader(hidden + "\n" + real + "\n"));
        hooks.callbacks.get("java.io.BufferedReader#<init>").after(new Param(reader, null, decoder));
        HookApi.Callback lines = hooks.callbacks.get("java.io.BufferedReader#readLine");
        Param line = new Param(reader, hidden); lines.after(line);
        check(line.result.equals(real), "skip consecutive module mappings");
        Param eof = new Param(reader, hidden); lines.after(eof);
        check(eof.result == null, "preserve EOF after hidden lines");
        Param otherReader = new Param(new BufferedReader(new StringReader("")), hidden); lines.after(otherReader);
        check(otherReader.result.equals(hidden), "never rewrite unrelated text readers");
        callStatic("dev.posedmcp.xposed.ScopeConcealment", "configure",
                new Class<?>[]{ClassLoader.class, boolean.class}, RuntimePoliciesRegression.class.getClassLoader(), false);
        Param off = new Param(new ClassLoader(){}, null, "dev.posedmcp.HiddenApi");
        lookup.before(off); check(off.thrown == null, "disable takes effect");
    }
    static int proceeds;
    static Object invokeAdapter(HookApi.Callback callback, Object result, Throwable original) throws Throwable {
        XposedInterface.Chain chain = (XposedInterface.Chain)Proxy.newProxyInstance(
                RuntimePoliciesRegression.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getArgs" -> List.of();
                    case "proceed" -> { proceeds++; if (original != null) throw original; yield result; }
                    default -> null;
                });
        Method intercept = LibXposedHookApi.class.getDeclaredMethod("intercept", XposedInterface.Chain.class, HookApi.Callback.class);
        intercept.setAccessible(true);
        try { return intercept.invoke(new LibXposedHookApi(null, null), chain, callback); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static void adapter() throws Throwable {
        proceeds = 0;
        int[] after = {0};
        try {
            invokeAdapter(new HookApi.Callback() {
                public void before(HookApi.HookParam p) { p.setThrowable(new ClassNotFoundException("hidden")); }
                public void after(HookApi.HookParam p) { after[0]++; }
            }, "original", null);
            throw new AssertionError("lost chosen exception");
        } catch (ClassNotFoundException expected) { assertions++; }
        check(proceeds == 0 && after[0] == 1, "skip original but close after exactly once");
        check(invokeAdapter(new HookApi.Callback() {
            public void after(HookApi.HookParam p) { p.setResult("recovered"); }
        }, null, new IOException("original")).equals("recovered"), "replace original exception with result");
        try {
            invokeAdapter(new HookApi.Callback() {
                public void after(HookApi.HookParam p) { p.setThrowable(new IOException("replacement")); }
            }, "original", null);
            throw new AssertionError("lost after exception");
        } catch (IOException expected) { check(expected.getMessage().equals("replacement"), "replace result with exception"); }
        check(invokeAdapter(new HookApi.Callback() {
            public void before(HookApi.HookParam p) { p.setThrowable(new IOException()); throw new IllegalStateException(); }
        }, "survived", null).equals("survived"), "before callback bug falls through");
        check(invokeAdapter(new HookApi.Callback() {
            public void after(HookApi.HookParam p) { p.setResult("bad"); throw new IllegalStateException(); }
        }, "original", null).equals("original"), "after callback bug restores outcome");
    }
    public static void main(String[] args) throws Throwable {
        domains(); redaction(); concealment(); adapter();
        System.out.println("PASS runtime policies (" + assertions + " assertions)");
    }
}
