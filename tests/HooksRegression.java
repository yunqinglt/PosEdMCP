package dev.posedmcp.xposed;

import dev.posedmcp.plugin.HookApi;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JsePlatform;

/** Plain JVM behavioral regression against production HookRegistry and shipped Lua. */
public final class HooksRegression {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static final class FakeHooks implements HookApi {
        final List<Callback> callbacks = new ArrayList<>();
        public Unhook hookAllMethods(String c, String m, Callback cb) { callbacks.add(cb); return () -> callbacks.remove(cb); }
        public Unhook hookMethod(String c, String m, Class<?>[] p, Callback cb) { return hookAllMethods(c,m,cb); }
        public Unhook hookAllConstructors(String c, Callback cb) { throw new UnsupportedOperationException(); }
        public Unhook hook(Member m, Callback cb) { throw new UnsupportedOperationException(); }
        public Unhook hook(Constructor<?> c, Callback cb) { throw new UnsupportedOperationException(); }
        public Method findMethod(Class<?> c, String n, Class<?>... p) { return null; }
    }
    static final class Param implements HookApi.HookParam {
        Object value; final Object[] arguments;
        Param(String arg) { arguments = new Object[]{arg}; }
        public Object thisObject() { return null; }
        public Object[] args() { return arguments; }
        public void setResult(Object r) { value=r; }
        public Object result() { return value; }
        public void setObjectField(String n,Object v) { throw new UnsupportedOperationException(); }
        public Object getObjectField(String n) { return null; }
        public Throwable throwable() { return null; }
    }
    static void install(String owner) throws Exception {
        HookRegistry.installCustom("test.AMS","forceStopPackage","",20,(p,after,target) -> {
            if (!after && owner.equals("deploy_powerkeeper")) { p.setResult("changed"); HookRegistry.markAltered(); }
        },owner,HooksRegression.class.getClassLoader(),owner);
    }
    static void registry() throws Throwable {
        FakeHooks hooks = new FakeHooks();
        Framework.adoptLibXposed(loader -> hooks,"regression");
        install("powerkeeper"); install("deploy_powerkeeper"); install("");
        check(hooks.callbacks.size()==3 && HookRegistry.snapshot().size()==3,"owners must coexist");
        install("deploy_powerkeeper");
        check(hooks.callbacks.size()==3,"reapply must replace only its owner");
        Param outer=new Param("outer"), inner=new Param("inner");
        for (HookApi.Callback c : hooks.callbacks) c.before(outer);
        for (HookApi.Callback c : hooks.callbacks) c.before(inner);
        for (int i=hooks.callbacks.size()-1;i>=0;i--) hooks.callbacks.get(i).after(inner);
        for (int i=hooks.callbacks.size()-1;i>=0;i--) hooks.callbacks.get(i).after(outer);
        JSONArray records=HookRegistry.records("",100).getJSONArray("records");
        check(records.length()==6,"nested calls must keep six independent records");
        Set<String> seen=new HashSet<>();
        for (int i=0;i<records.length();i++) {
            JSONObject r=records.getJSONObject(i);
            check(r.optBoolean("altered")==r.getString("target").endsWith("@special:deploy_powerkeeper"),"altered record belongs to wrong hook");
            seen.add(r.getString("target")+r.getJSONArray("args").getString(0));
        }
        check(seen.size()==6,"nested argument records were mixed");
        HookRegistry.clear("test.AMS");
        check(hooks.callbacks.size()==2,"ordinary clear must preserve specials");
        HookRegistry.clear("test.AMS","deploy_powerkeeper");
        check(hooks.callbacks.size()==1 && HookRegistry.snapshot().keySet().iterator().next().endsWith("@special:powerkeeper"),"pause must preserve original guard");
        install("deploy_powerkeeper");
        HookRegistry.installCustom("test.Hans","isImportantPkg","",20,(p,after,target) -> {},
                "importance",HooksRegression.class.getClassLoader(),"deploy_powerkeeper");
        check(hooks.callbacks.size()==3,"one special owner must support multiple methods");
        HookRegistry.clear("","powerkeeper");
        check(hooks.callbacks.size()==2,"original pause must preserve all Deploy methods");
        install("powerkeeper");
        HookRegistry.clear("","deploy_powerkeeper");
        check(hooks.callbacks.size()==1 && HookRegistry.snapshot().keySet().iterator().next().endsWith("@special:powerkeeper"),
                "special pause must clear all its methods and preserve the original guard");
        HookRegistry.clear("","powerkeeper");
        check(hooks.callbacks.isEmpty(),"cleanup");
        System.out.println("PASS independent owners, reapply, nested records, multi-method pause and ordinary clear");
    }
    static void lua(Path file, boolean hyper) throws Exception {
        Globals globals=JsePlatform.standardGlobals();
        List<LuaValue> captured=new ArrayList<>();
        LuaTable app=new LuaTable();
        app.set("hook",new OneArgFunction(){public LuaValue call(LuaValue spec){captured.add(spec);return NIL;}});
        app.set("log",new OneArgFunction(){public LuaValue call(LuaValue text){return NIL;}});
        app.set("call",new VarArgFunction(){public Varargs invoke(Varargs a){
            check(a.arg(2).tojstring().equals("getPkgName()"),"unexpected ROM accessor");
            return a.arg(1).get("pkgName");
        }});
        globals.set("app",app);
        globals.load(Files.readString(file),file.toString()).call();
        String[] reasons={"OneKeyClean","SwipeUpClean: test","AutoIdleKill", "user request","shell","force-stop", ""};
        String[] packages={"dev.deploy","dev.deploy:decompiler","dev.deploy.fixture","dev.deployevil","dev.posedmcp","example.app"};
        for(String pkg:packages) for(int r=0;r<reasons.length;r++) {
            boolean[] skipped={false}; LuaTable ctx=new LuaTable(), args=new LuaTable();
            args.set(1,pkg); args.set(hyper?4:5,reasons[r]); ctx.set("args",args);
            ctx.set("set_result",new ZeroArgFunction(){public LuaValue call(){skipped[0]=true;return NIL;}});
            captured.get(0).get("before").call(ctx);
            check(skipped[0]==((pkg.equals("dev.deploy")||pkg.startsWith("dev.deploy:")) && (!hyper||r<3)),"script match: "+pkg+" / "+reasons[r]);
        }
        System.out.println("PASS "+file.getFileName()+" (42 package/reason cases)");
        check(captured.size()==(hyper?1:2),"expected ROM hook count");
        if (!hyper) {
            for (String pkg:packages) {
                LuaValue[] result={LuaValue.NIL};
                LuaTable ctx=new LuaTable(), args=new LuaTable(), hans=new LuaTable();
                hans.set("pkgName",pkg); args.set(1,hans); ctx.set("args",args);
                ctx.set("set_result",new OneArgFunction(){public LuaValue call(LuaValue value){result[0]=value;return NIL;}});
                captured.get(1).get("before").call(ctx);
                boolean match=pkg.equals("dev.deploy")||pkg.startsWith("dev.deploy:");
                check(match?result[0]==LuaValue.TRUE:result[0].isnil(),"Hans exemption leaked: "+pkg);
            }
            System.out.println("PASS Hans importance matches Deploy only (6 cases)");
        }
    }
    public static void main(String[] args) throws Throwable {
        registry();
        lua(Path.of("app/src/main/assets/special/deploy-athena.lua"),false);
        lua(Path.of("app/src/main/assets/special/deploy-powerkeeper.lua"),true);
    }
}
