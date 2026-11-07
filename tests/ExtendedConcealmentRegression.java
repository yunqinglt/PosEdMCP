package fixture;

import dev.posedmcp.plugin.HookApi;
import dev.posedmcp.state.PackageTargets;
import dev.posedmcp.xposed.Framework;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.json.JSONArray;

/** Actual production callbacks, immutable/cached controls, streams, partial reads and errors. */
public final class ExtendedConcealmentRegression {
    static int assertions;
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static final class Hooks implements HookApi {
        final Map<Method, Callback> callbacks = new HashMap<>();
        final Map<String, Callback> constructors = new HashMap<>();
        public Unhook hook(Member m, Callback cb) { callbacks.put((Method)m, cb); return () -> {}; }
        public Unhook hook(Constructor<?> c, Callback cb) { throw new UnsupportedOperationException(); }
        public Unhook hookAllConstructors(String c, Callback cb) { constructors.put(c, cb); return () -> {}; }
        public Unhook hookMethod(String c, String m, Class<?>[] p, Callback cb) { throw new UnsupportedOperationException(); }
        public Unhook hookAllMethods(String c, String m, Callback cb) { throw new UnsupportedOperationException(); }
        public Method findMethod(Class<?> c, String m, Class<?>... p) { return null; }
        Callback get(Class<?> type, String name, Class<?>... args) throws Exception { return callbacks.get(type.getDeclaredMethod(name, args)); }
    }
    static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        return RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed.ExtendedConcealment", name, types, args);
    }
    static void configure(boolean on) throws Exception {
        call("configure", new Class<?>[]{ClassLoader.class, boolean.class}, ExtendedConcealmentRegression.class.getClassLoader(), on);
    }
    static RuntimePoliciesRegression.Param param(Object receiver, Object result, Object... args) {
        return new RuntimePoliciesRegression.Param(receiver, result, args);
    }
    public static void main(String[] args) throws Throwable {
        Hooks hooks = new Hooks(); Framework.adoptLibXposed(loader -> hooks, "fixture");
        RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed.ScopeConcealment", "configure",
                new Class<?>[]{ClassLoader.class, boolean.class}, ExtendedConcealmentRegression.class.getClassLoader(), true);
        int baseline = hooks.callbacks.size(); configure(false);
        check(hooks.callbacks.size() == baseline, "default off does not install extra hooks");
        configure(true); int installed = hooks.callbacks.size();
        check(!hooks.callbacks.containsKey(Class.class.getDeclaredMethod("forName",String.class)),
                "caller-sensitive single-argument forName is never hooked");
        check(installed > baseline, "explicit opt-in installs extra surfaces");
        check(((org.json.JSONObject)call("status", new Class<?>[0])).getInt("failed") == 0, "hook installation reports zero failures");
        StackTraceElement own = new StackTraceElement("example.Main", "start", "Main.java", 7);
        StackTraceElement hidden = new StackTraceElement("de.robv.android.xposed.XposedBridge", "invoke", "XposedBridge.java", 8);
        StackTraceElement zygote = new StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 42);
        StackTraceElement[] original = {hidden, own, zygote};
        var p = param(new Throwable(), original); hooks.get(Throwable.class, "getStackTrace").after(p);
        check(((StackTraceElement[])p.result).length == 1 && ((StackTraceElement[])p.result)[0] == own, "stack array removes precise hidden frames");
        check(original.length == 3 && original[0] == hidden && original[2] == zygote, "cached/original trace never mutated");
        StackTraceElement[] clean = {own}; p=param(Thread.currentThread(), clean); hooks.get(Thread.class,"getStackTrace").after(p);
        check(p.result == clean, "ordinary trace identity retained");
        Map<Thread,StackTraceElement[]> map = Map.of(Thread.currentThread(), original, new Thread(), clean);
        p=param(null,map); hooks.get(Thread.class,"getAllStackTraces").after(p);
        Map<?,?> filtered=(Map<?,?>)p.result;
        check(filtered.size()==2 && ((StackTraceElement[])filtered.get(Thread.currentThread())).length==1, "all-thread snapshot retains thread keys");
        check(map.get(Thread.currentThread()) == original && filtered.containsValue(clean), "snapshot/cache and unrelated arrays retained");
        var lookup=hooks.get(Class.class,"forName",String.class,boolean.class,ClassLoader.class);
        p=param(null,null,"[[Ldev.posedmcp.Hidden;",false,new ClassLoader(){});lookup.before(p);
        check(p.thrown instanceof ClassNotFoundException, "direct array class probe hidden");
        p=param(null,String.class,"java.lang.String",false,new ClassLoader(){});lookup.before(p);
        check(p.thrown==null && p.result==String.class, "ordinary class query unchanged");
        p=param(null,String.class,"com.android.internal.os.ZygoteInit",false,new ClassLoader(){});lookup.before(p);
        check(p.thrown==null,"stack-only platform rule never blocks platform lookup");
        p=param(new ClassLoader(){},String.class,"dev.posedmcp.Hidden");hooks.get(ClassLoader.class,"findLoadedClass",String.class).before(p);
        check(p.result==null && p.thrown==null,"loaded-class lookup uses absent semantics");
        for (String name : List.of("getResource","getResourceAsStream","getResources")) {
            p=param(new ClassLoader(){},"control","de/robv/android/xposed/XposedBridge.class");
            hooks.get(ClassLoader.class,name,String.class).before(p);
            check(name.equals("getResources") ? !((Enumeration<?>)p.result).hasMoreElements() : p.result==null,"class resource probe "+name);
        }
        p=param(new ClassLoader(){},"control","example/XposedBridge.class");hooks.get(ClassLoader.class,"getResource",String.class).before(p);
        check(p.result.equals("control"),"ordinary similarly named class resource preserved");
        String bad="7000-8000 r-xp /memfd:XposedBridge.jar (deleted)", good="8000-9000 r-xp /system/lib64/libc.so";
        var bytes=hooks.get(Files.class,"readAllBytes",Path.class);
        byte[] originalBytes=(bad+"\r\n"+good+"\r\n\u00ff").getBytes(StandardCharsets.ISO_8859_1);
        p=param(null,originalBytes,Paths.get("/proc/self/maps"));bytes.after(p);
        check(Arrays.equals((byte[])p.result,(good+"\r\n\u00ff").getBytes(StandardCharsets.ISO_8859_1)),"NIO byte view preserves CRLF and non-UTF8 bytes");
        check(new String(originalBytes,StandardCharsets.ISO_8859_1).startsWith(bad),"original bytes unchanged");
        p=param(null,originalBytes,Paths.get("/data/example/maps"));bytes.after(p);
        check(p.result==originalBytes,"ordinary binary input untouched");
        IOException error=new IOException("fixture permission denied");p=param(null,null,Paths.get("/proc/self/maps"));p.thrown=error;bytes.after(p);
        check(p.thrown==error,"original read exception retained");
        List<String> list=List.of(bad,good);p=param(null,list,Paths.get("/proc/self/maps"));hooks.get(Files.class,"readAllLines",Path.class).after(p);
        check(p.result.equals(List.of(good)) && list.size()==2,"immutable list filtered without cache mutation");
        AtomicBoolean closed=new AtomicBoolean();Stream<String> source=Stream.of(bad,good).onClose(()->closed.set(true));
        p=param(null,source,Paths.get("/proc/self/maps"));hooks.get(Files.class,"lines",Path.class).after(p);
        try(Stream<?> stream=(Stream<?>)p.result) { check(stream.toList().equals(List.of(good)),"lazy NIO stream filters actual lines"); }
        check(closed.get(),"filtered stream propagates close");
        p=param(null,bad+"\n"+good,Paths.get("/proc/self/maps"));hooks.get(Files.class,"readString",Path.class).after(p);
        check(p.result.equals(good),"readString preserves missing final newline");
        var stringRead=hooks.get(Files.class,"readString",Path.class);
        p=param(null,bad,Paths.get("/proc/self/maps"));stringRead.after(p);
        check(p.result.equals(""),"all-hidden text returns EOF, no fabricated line");
        String unchanged=good+"\r\n";p=param(null,unchanged,Paths.get("/proc/self/maps"));stringRead.after(p);
        check(p.result==unchanged,"ordinary text identity retained");
        for(String path:List.of("/proc/self/./maps","/proc/thread-self/maps","/proc/self/task/123/maps")) {
            check((Boolean)RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed.ScopeConcealment","mapsPath",new Class<?>[]{Object.class},Paths.get(path)),"self maps alias "+path);
        }
        check(!(Boolean)RuntimePoliciesRegression.callStatic("dev.posedmcp.xposed.ScopeConcealment","mapsPath",new Class<?>[]{Object.class},"/proc/999999/task/123/maps"),"other process maps untouched");
        Path temp=Files.createTempFile("posed-maps-control-",".txt");
        try {
            Files.writeString(temp,bad+"\n"+bad+"\n"+good+"\n");
            try(RandomAccessFile file=new RandomAccessFile(temp.toFile(),"r")) {
                hooks.constructors.get(RandomAccessFile.class.getName()).after(param(file,null,"/proc/self/maps","r"));
                p=param(file,file.readLine());hooks.get(RandomAccessFile.class,"readLine").after(p);
                check(p.result.equals(good) && file.readLine()==null,"RandomAccessFile skips consecutive hidden lines and preserves position/EOF");
            }
            RandomAccessFile file=new RandomAccessFile(temp.toFile(),"r");
            hooks.constructors.get(RandomAccessFile.class.getName()).after(param(file,null,"/proc/self/maps","r"));
            p=param(file,file.readLine());file.close();hooks.get(RandomAccessFile.class,"readLine").after(p);
            check(p.thrown instanceof IOException,"RandomAccessFile continuation failure keeps IO exception semantics");
        } finally { Files.deleteIfExists(temp); }
        check(Arrays.equals(PackageTargets.parse("example.app\nexample.app,second.app"),new String[]{"example.app","second.app"}),"target list exact and deduplicated");
        check(!PackageTargets.includes(new JSONArray(),"example.app") && !PackageTargets.includes(null,"example.app"),"empty or missing targets select no app");
        check(PackageTargets.includes(new JSONArray(new String[]{"example.app"}),"example.app") && !PackageTargets.includes(new JSONArray(new String[]{"example.app"}),"example.app.extra"),"no target prefix matching");
        for(String invalid:List.of("*","example","/data/app","example.*")) {
            try {PackageTargets.parse(invalid);throw new AssertionError("invalid target accepted");}catch(IllegalArgumentException expected){assertions++;}
        }
        configure(false);p=param(new Throwable(),original);hooks.get(Throwable.class,"getStackTrace").after(p);
        check(p.result==original,"disable restores original stack views");
        p=param(null,originalBytes,Paths.get("/proc/self/maps"));bytes.after(p);check(p.result==originalBytes,"disable restores original maps bytes");
        configure(true);check(hooks.callbacks.size()==installed,"re-enable does not install duplicate hooks");
        System.out.println("PASS extended Java views ("+assertions+" assertions)");
    }
}
