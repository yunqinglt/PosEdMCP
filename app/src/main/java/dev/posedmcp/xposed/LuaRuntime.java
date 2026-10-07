package dev.posedmcp.xposed;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaUserdata;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.CoerceLuaToJava;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import dev.posedmcp.Logx;
import dev.posedmcp.plugin.HookApi;

/**
 * Runs a Lua script inside a target application's process.
 *
 * <p>This exists because the alternative for injected logic - hand-written smali,
 * assembled to a DEX - puts a register machine on the model, and its mistakes are
 * silent. A guard branch with the wrong polarity lists nothing, appends nothing
 * and throws nothing, which is indistinguishable from "the data is not there";
 * that is exactly how a probe inside the GitHub app concluded the app was signed
 * out when it was not. A script is a much smaller thing to get right, and it is
 * readable text in the confirmation prompt, so the user can see what they are
 * approving.
 *
 * <p>The interpreter ships as part of this module, so it is already in the
 * target process - no compile step, no DEX over the bridge, no load. What a
 * script can reach is deliberately the same set of things the tools already do
 * (the application's Context, its class loader, its methods including private
 * ones, its fields, its files). It is a new syntax for existing privileges, not
 * a new privilege.
 */
public final class LuaRuntime {

    /** Roughly a second of pure-Lua work; a probe that needs more is not a probe. */
    public static final long DEFAULT_MAX_INSTRUCTIONS = 50_000_000L;

    private static final int MAX_OUTPUT_CHARS = 8_000;
    private static final int MAX_READ_CHARS = 200_000;
    private static final int MAX_METHOD_LIST = 500;
    private static final int MAX_TABLE_ENTRIES = 100;
    private static final int MAX_ARRAY_ENTRIES = 100_000;
    public static final int DB_ROW_LIMIT = 200;

    /** One native read at a time; a stray address is fatal either way, but a huge one is slow too. */
    private static final int MAX_NATIVE_READ = 4096;

    /** Every SQLite file starts with this, NUL included. */
    private static final byte[] SQLITE_MAGIC =
            "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);

    private LuaRuntime() {
    }

    /**
     * Stops a script that will never finish.
     *
     * <p>Scripts are not sandboxed from the application, so an endless loop pins
     * a thread in it - the app looks hung and the platform eventually kills it,
     * which is the outcome this whole design is trying to avoid. Counting
     * instructions costs about 2x on pure-Lua work, which is worth paying.
     *
     * <p>The script cannot turn this off. The hook lives on the VM, not in the
     * environment the script can reach: dropping or re-pointing {@code debug}
     * makes no difference.
     */
    private static final class Budget extends DebugLib {
        private final long limit;
        private long used;

        Budget(long limit) {
            this.limit = limit;
        }

        long used() {
            return used;
        }

        long limit() {
            return limit;
        }

        @Override
        public void onInstruction(int pc, Varargs v, int top) {
            super.onInstruction(pc, v, top);
            if (++used > limit) {
                throw new LuaError("stopped after " + limit + " vm instructions - the script"
                        + " either loops forever or is doing far more work than this tool is"
                        + " for. Say what you were trying to do instead of retrying as-is.");
            }
        }
    }

    public static JSONObject exec(String packageName, ClassLoader appClassLoader,
            Context appContext, String source, long maxInstructions) throws Exception {
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("source is required");
        }
        long limit = maxInstructions > 0 ? maxInstructions : DEFAULT_MAX_INSTRUCTIONS;

        CappedOutput captured = new CappedOutput(MAX_OUTPUT_CHARS);
        PrintStream out = new PrintStream(captured, true, "UTF-8");

        Budget budget = new Budget(limit);
        Globals globals = globals(out, budget);
        List<SQLiteDatabase> databases = new ArrayList<>();
        // Hooks a script registers are reported back, so the caller can offer to
        // keep them: a hook that dies with the process is only half a hook.
        List<JSONObject> installed = new ArrayList<>();
        globals.set("app", host(packageName, appClassLoader, appContext, out, databases, installed));

        long startedAt = System.currentTimeMillis();
        try {
            // Compilation errors land here, with the offending line - the single
            // biggest thing a script has over a hand-written DEX.
            LuaValue chunk = globals.load(source, "lua_exec");
            LuaValue returned = chunk.call();
            return result(true, describe(returned), null, captured, budget, startedAt, installed);
        } catch (LuaError e) {
            return result(false, null, e.getMessage(), captured, budget, startedAt, installed);
        } catch (Throwable t) {
            // StackOverflowError from runaway recursion, OutOfMemoryError from a
            // script that builds a huge table - both should be reported rather
            // than allowed to take the target process down silently.
            return result(false, null, t.getClass().getSimpleName() + ": " + t.getMessage(),
                    captured, budget, startedAt, installed);
        } finally {
            // A database left open would hold a file descriptor in the target
            // process for as long as that process lives.
            for (SQLiteDatabase database : databases) {
                try {
                    database.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Compiles a script without running any of it.
     *
     * <p>Used when one is filed away for later: a saved script that does not even
     * parse is a trap for whoever taps Run, and compiling is cheap. Nothing the
     * script contains is executed, so this is safe to call from the app process.
     *
     * @return the error text, or {@code null} when it compiles
     */
    public static String checkSyntax(String source) {
        try {
            Globals globals = globals(new PrintStream(new ByteArrayOutputStream(), true),
                    new Budget(DEFAULT_MAX_INSTRUCTIONS));
            globals.load(source, "script");
            return null;
        } catch (LuaError e) {
            return e.getMessage();
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /**
     * A curated environment: base, table, string, math, bit32, coroutine.
     *
     * <p>No {@code io} and no {@code os}. Those would hand a script a way to
     * touch the filesystem and spawn processes that nothing else in this module
     * offers, which would make the tool surface a lie - the bridges and the root
     * shell are meant to be the only doors. File access is available, but through
     * {@code app.files} / {@code app.read}, which are read-only and say so.
     */
    private static Globals globals(PrintStream out, Budget budget) {
        Globals g = new Globals();
        g.load(new JseBaseLib());
        g.load(new PackageLib());
        g.load(new Bit32Lib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new CoroutineLib());
        g.load(new JseMathLib());
        LoadState.install(g);
        LuaC.install(g);
        g.STDOUT = out;
        g.STDERR = out;
        budget.call(g, g);
        return g;
    }

    // ---- the host API ------------------------------------------------------

    private interface Body {
        LuaValue call(Varargs args) throws Exception;
    }

    private static VarArgFunction fn(Body body) {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                try {
                    return body.call(args);
                } catch (LuaError e) {
                    throw e;
                } catch (Throwable t) {
                    throw new LuaError(messageOf(t));
                }
            }
        };
    }

    private static LuaTable host(String packageName, ClassLoader loader, Context context,
            PrintStream out, List<SQLiteDatabase> databases, List<JSONObject> installed) {
        LuaTable host = new LuaTable();
        host.set("name", fn(a -> LuaValue.valueOf(packageName)));
        host.set("uid", fn(a -> LuaValue.valueOf(android.os.Process.myUid())));
        host.set("context", fn(a -> coerce(context)));
        host.set("loader", fn(a -> coerce(loader)));

        host.set("class", fn(a -> {
            String name = a.checkjstring(1);
            try {
                return coerce(Class.forName(name, false, loader));
            } catch (Throwable t) {
                // A miss is an ordinary answer here: an obfuscated app is full of
                // names that survive as strings with no class behind them.
                return LuaValue.NIL;
            }
        }));

        host.set("new", fn(a -> coerce(constructSpec(a.arg(1), rest(a, 1), loader))));

        host.set("call", fn(a -> coerce(call(toJava(a.arg(1)), a.checkjstring(2), rest(a, 2)))));

        host.set("get", fn(a -> {
            Object target = toJava(a.arg(1));
            return coerce(field(target, a.checkjstring(2)).get(staticReceiver(target)));
        }));

        host.set("set", fn(a -> {
            Object target = toJava(a.arg(1));
            Field f = field(target, a.checkjstring(2));
            f.set(staticReceiver(target), convert(a.arg(3), f.getType()));
            return LuaValue.NIL;
        }));

        host.set("methods", fn(a -> methods(toJava(a.arg(1)),
                a.narg() > 1 && !a.arg(2).isnil() ? a.arg(2).tojstring() : "")));

        host.set("exists", fn(a -> LuaValue.valueOf(new File(a.checkjstring(1)).exists())));

        host.set("files", fn(a -> listFiles(a.checkjstring(1))));

        host.set("read", fn(a -> {
            int max = a.narg() > 1 && a.arg(2).isint() ? a.arg(2).toint() : MAX_READ_CHARS;
            return LuaValue.valueOf(readText(a.checkjstring(1), max));
        }));

        host.set("db", fn(a -> openDatabase(a.checkjstring(1), databases)));

        host.set("hook", fn(a -> installHook(a.arg(1), loader, installed)));

        host.set("native", nativeApi());

        host.set("log", fn(a -> {
            String text = a.narg() > 0 ? a.arg(1).tojstring() : "";
            Logx.i("[" + packageName + "] " + text);
            out.println(text);
            return LuaValue.NIL;
        }));

        return host;
    }

    // ---- hooks -------------------------------------------------------------

    /**
     * {@code app.hook{...}} - registers a hook whose body is a Lua function.
     *
     * <p>This is what lets the interpreter do more than run once. A script
     * normally ends and its state goes with it; a function handed here is kept by
     * the module and called again on every matching call, long after the script
     * that defined it has returned.
     *
     * <p>The declaration is a table rather than positional arguments because most
     * of it is optional, and because a hook ends up as a row the user reads on
     * the hook page - these fields are what that page shows.
     */
    private static LuaValue installHook(LuaValue spec, ClassLoader loader,
            List<JSONObject> installed) throws Exception {
        if (!spec.istable()) {
            throw new LuaError("app.hook takes a table, for example: app.hook{"
                    + "class = \"com.x.Y\", method = \"z\", effect = \"what this does\","
                    + " after = function(ctx) print(ctx.result()) end}");
        }
        LuaTable t = spec.checktable();
        String className = textOf(t.get("class"));
        String methodName = textOf(t.get("method"));
        if (className.isEmpty() || methodName.isEmpty()) {
            throw new LuaError("app.hook needs 'class' and 'method'");
        }

        LuaValue before = t.get("before");
        LuaValue after = t.get("after");
        if (before.isnil() && after.isnil()) {
            throw new LuaError("app.hook needs a 'before' or an 'after' function - a hook"
                    + " with no body would just be a slower method call");
        }
        // isfunction() is a real type check, unlike isstring()/isnumber().
        if ((!before.isnil() && !before.isfunction()) || (!after.isnil() && !after.isfunction())) {
            throw new LuaError("'before' and 'after' must be functions");
        }

        String effect = textOf(t.get("effect"));
        HookRegistry.installCustom(className, methodName,
                textOf(t.get("params")), t.get("max_records").optint(200),
                new LuaHookBody(before, after), effect.isEmpty() ? null : effect, loader);

        if (installed != null) {
            installed.add(new JSONObject()
                    .put("class", className)
                    .put("method", methodName)
                    .put("params", textOf(t.get("params")))
                    .put("effect", effect));
        }

        LuaTable back = new LuaTable();
        back.set("class", LuaValue.valueOf(className));
        back.set("method", LuaValue.valueOf(methodName));
        back.set("effect", LuaValue.valueOf(effect));
        return back;
    }

    private static String textOf(LuaValue value) {
        return value == null || value.isnil() ? "" : value.tojstring();
    }

    /**
     * A hook whose body is a Lua function.
     *
     * <p>The closure is held here, in Java, on purpose: the script's environment
     * is discarded when it finishes, so this reference is the only thing keeping
     * the function - and the globals it was compiled against - alive.
     */
    private static final class LuaHookBody implements HookRegistry.Body {
        private final LuaValue before;
        private final LuaValue after;

        LuaHookBody(LuaValue before, LuaValue after) {
            this.before = before;
            this.after = after;
        }

        @Override
        public void call(HookApi.HookParam param, boolean isAfter, String target) {
            LuaValue body = isAfter ? after : before;
            if (body == null || body.isnil()) {
                return;
            }
            body.call(hookContext(param, isAfter, target));
        }
    }

    /**
     * What a hook body is handed: the call in front of it, and the few things it
     * can do to that call.
     *
     * <p>Deliberately narrow. A hook runs on the application's own thread, in the
     * middle of the application's own call, so everything reachable from here is
     * reachable at the worst possible moment. These are the operations that make
     * a hook useful and nothing beyond them.
     */
    private static LuaTable hookContext(HookApi.HookParam param, boolean after, String target) {
        LuaTable ctx = new LuaTable();
        ctx.set("phase", LuaValue.valueOf(after ? "after" : "before"));
        ctx.set("target", LuaValue.valueOf(target));
        ctx.set("this", coerce(param.thisObject()));

        LuaTable args = new LuaTable();
        Object[] live = param.args();
        if (live != null) {
            for (int i = 0; i < live.length; i++) {
                args.set(i + 1, coerce(live[i]));
            }
        }
        ctx.set("args", args);

        ctx.set("set_arg", fn(a -> {
            int index = a.checkint(1);
            Object[] raw = param.args();
            if (raw == null || index < 1 || index > raw.length) {
                return LuaValue.FALSE;
            }
            Object current = raw[index - 1];
            raw[index - 1] = convert(a.arg(2),
                    current == null ? Object.class : current.getClass());
            HookRegistry.markAltered();
            return LuaValue.TRUE;
        }));

        ctx.set("set_result", fn(a -> {
            // Setting a result here makes the framework skip the original.
            param.setResult(toJava(a.arg(1)));
            HookRegistry.markAltered();
            return LuaValue.NIL;
        }));

        ctx.set("result", fn(a -> coerce(param.result())));
        ctx.set("throwable", fn(a -> coerce(param.throwable())));

        ctx.set("field", fn(a -> coerce(param.getObjectField(a.checkjstring(1)))));
        ctx.set("set_field", fn(a -> {
            String name = a.checkjstring(1);
            Object current = param.getObjectField(name);
            param.setObjectField(name,
                    convert(a.arg(2), current == null ? Object.class : current.getClass()));
            HookRegistry.markAltered();
            return LuaValue.NIL;
        }));

        return ctx;
    }

    // ---- reflection --------------------------------------------------------

    private static Class<?> classOf(LuaValue value, ClassLoader loader) throws Exception {
        Object java = toJava(value);
        if (java instanceof Class) {
            return (Class<?>) java;
        }
        if (java instanceof String) {
            return Class.forName((String) java, false, loader);
        }
        throw new LuaError("expected a class - get one with app.class(\"...\")");
    }

    /**
     * Resolves app.new's first argument: a class, a class name, or a class name
     * carrying the constructor to use - {@code "java.util.Date(long)"}. The last
     * form is how a constructor that fits several ways gets picked deliberately;
     * it is the same spelling app.methods prints for one.
     */
    private static Object constructSpec(LuaValue first, LuaValue[] args, ClassLoader loader)
            throws Exception {
        if (!first.isstring()) {
            return construct(classOf(first, loader), args, null);
        }
        String spec = first.tojstring();
        int paren = spec.indexOf('(');
        if (paren <= 0 || !spec.endsWith(")")) {
            return construct(Class.forName(spec, false, loader), args, null);
        }
        String inner = spec.substring(paren + 1, spec.length() - 1).trim();
        String[] wanted = inner.isEmpty() ? new String[0] : inner.split("\\s*,\\s*");
        return construct(Class.forName(spec.substring(0, paren).trim(), false, loader), args, wanted);
    }

    /** {@code null} for a class (a static member's receiver), the object otherwise. */
    private static Object staticReceiver(Object target) {
        return target instanceof Class ? null : target;
    }

    private static Object call(Object target, String spec, LuaValue[] args) throws Exception {
        if (target == null) {
            throw new LuaError("app.call needs a target: a class for a static call, or an"
                    + " instance. Got nil.");
        }
        if (spec == null || spec.isEmpty()) {
            throw new LuaError("app.call needs a method name");
        }
        boolean statics = target instanceof Class;
        Class<?> owner = statics ? (Class<?>) target : target.getClass();
        String searched = statics ? owner.getName() : target.getClass().getName();

        // "put(String,String)" names one overload outright. It is the same form
        // app.methods prints, so a signature can be pasted straight back.
        String methodName = spec;
        String[] wanted = null;
        int paren = spec.indexOf('(');
        if (paren > 0 && spec.endsWith(")")) {
            methodName = spec.substring(0, paren).trim();
            String inner = spec.substring(paren + 1, spec.length() - 1).trim();
            wanted = inner.isEmpty() ? new String[0] : inner.split("\\s*,\\s*");
        }

        // Walk up the hierarchy: the receiver's own class often does not declare
        // the method, and an implementation class declares no more than it
        // overrides. The first class with a match at this arity wins, the way
        // Java would resolve it.
        Search found = search(owner, methodName, args.length, wanted);
        if (found.matching == null && statics) {
            // A Class is an object too. app.call(SomeClass, "getName") is asking
            // about the class, not for a static called getName on it, so when the
            // class has no such static, look on java.lang.Class itself.
            statics = false;
            owner = Class.class;
            found = search(Class.class, methodName, args.length, wanted);
        }
        List<Method> matching = found.matching;
        if (matching == null) {
            // A signature that matched nothing is a dead end unless the ones
            // that do exist are visible.
            String available = wanted == null || found.sameShape == null ? ""
                    : " The ones there take: " + signatures(found.sameShape) + ".";
            throw new LuaError("no method " + spec + " on " + searched + " taking " + args.length
                    + " argument(s), in its superclasses either." + available
                    + " app.methods(target, name) lists what is there.");
        }
        if (wanted != null && matching.size() > 1) {
            throw new LuaError("more than one method on " + owner.getName()
                    + " matches that signature: " + signatures(matching));
        }

        Method method;
        if (wanted != null || matching.size() == 1) {
            method = matching.get(0);
        } else {
            // Arity alone cannot decide - ContentValues.put alone has nine
            // two-argument forms, so every script touching a ContentValues would
            // stop dead here. Score the values instead; a genuine tie is still
            // refused rather than guessed.
            method = null;
            int bestScore = -1;
            boolean tied = false;
            for (Method candidate : matching) {
                int score = scoreArgList(args, candidate.getParameterTypes());
                if (score < 0) {
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    method = candidate;
                    tied = false;
                } else if (score == bestScore) {
                    tied = true;
                }
            }
            if (method == null) {
                throw new LuaError("no " + methodName + " on " + owner.getName()
                        + " accepts these argument types: " + signatures(matching));
            }
            if (tied) {
                throw new LuaError(methodName + " is ambiguous for these arguments on "
                        + owner.getName() + ": " + signatures(matching)
                        + ". Name the overload you want the way app.methods prints it, e.g."
                        + " app.call(target, \"" + signatureOf(matching.get(0)) + "\", ...).");
            }
        }

        if (!Modifier.isStatic(method.getModifiers()) && statics) {
            throw new LuaError(methodName + " is an instance method; pass the instance rather"
                    + " than its class");
        }

        // The effective staticness, after the Class fallback above - deciding the
        // receiver from the target's own type would send null to a method that
        // is being called on the Class object.
        Object receiver = statics ? null : target;
        Class<?>[] types = method.getParameterTypes();
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            converted[i] = convert(args[i], types[i]);
        }
        method.setAccessible(true);
        try {
            return method.invoke(receiver, converted);
        } catch (InvocationTargetException e) {
            // The application's own exception is the interesting part.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new LuaError(methodName + " threw " + cause.getClass().getName() + ": "
                    + cause.getMessage());
        } catch (IllegalArgumentException e) {
            throw new LuaError(signatureOf(method) + " will not accept "
                    + describeArgs(converted) + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
        }
    }

    private static String describeArgs(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.length() == 0 ? "no arguments" : sb.toString();
    }

    private static Object construct(Class<?> owner, LuaValue[] args, String[] wanted)
            throws Exception {
        List<Constructor<?>> all = new ArrayList<>();
        List<Constructor<?>> matching = new ArrayList<>();
        for (Constructor<?> c : owner.getDeclaredConstructors()) {
            if (c.getParameterCount() != args.length) {
                continue;
            }
            all.add(c);
            if (wanted == null || matchesSignature(c.getParameterTypes(), wanted)) {
                matching.add(c);
            }
        }
        if (matching.isEmpty()) {
            // As with methods: a signature that matched nothing is a dead end
            // unless the ones that do exist are visible.
            String available = wanted == null || all.isEmpty() ? ""
                    : " The ones there take: " + constructorSignatures(all) + ".";
            throw new LuaError("no constructor on " + owner.getName() + " taking " + args.length
                    + " argument(s)" + available);
        }
        if (wanted != null && matching.size() > 1) {
            throw new LuaError("more than one constructor on " + owner.getName()
                    + " matches that signature: " + constructorSignatures(matching));
        }

        Constructor<?> constructor = matching.get(0);
        if (wanted == null && matching.size() > 1) {
            int bestScore = -1;
            boolean tied = false;
            for (Constructor<?> candidate : matching) {
                int score = scoreArgList(args, candidate.getParameterTypes());
                if (score < 0) {
                    continue;
                }
                if (score > bestScore) {
                    bestScore = score;
                    constructor = candidate;
                    tied = false;
                } else if (score == bestScore) {
                    tied = true;
                }
            }
            if (bestScore < 0) {
                throw new LuaError("no constructor on " + owner.getName()
                        + " accepts these argument types: " + constructorSignatures(matching));
            }
            if (tied) {
                throw new LuaError(owner.getName() + " has several constructors taking "
                        + args.length + " argument(s) that fit these values equally well: "
                        + constructorSignatures(matching) + ". Pick one by signature, e.g."
                        + " app.new(\"" + owner.getName() + "("
                        + typeList(matching.get(0).getParameterTypes()) + ")\", ...).");
            }
        }
        Class<?>[] types = constructor.getParameterTypes();
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            converted[i] = convert(args[i], types[i]);
        }
        constructor.setAccessible(true);
        String signature = "<init>(" + typeList(constructor.getParameterTypes()) + ")";
        try {
            return constructor.newInstance(converted);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new LuaError(owner.getName() + signature + " threw " + cause.getClass().getName()
                    + ": " + cause.getMessage());
        } catch (IllegalArgumentException e) {
            // Thrown directly, not wrapped: reflection could not use what it was
            // given. Saying so - and which signature was chosen - is the whole
            // difference between a self-explaining failure and "null".
            throw new LuaError(owner.getName() + signature + " will not accept "
                    + describeArgs(converted) + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")");
        }
    }

    private static Field field(Object target, String name) throws Exception {
        if (target == null) {
            throw new LuaError("app.get/app.set need a target; got nil");
        }
        Class<?> owner = target instanceof Class ? (Class<?>) target : target.getClass();
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // Keep walking up: a field is often declared on the superclass.
            }
        }
        throw new LuaError("no field " + name + " on " + owner.getName()
                + " (searched its superclasses too)");
    }

    private static LuaTable listFiles(String path) {
        File dir = new File(path);
        // Deliberately loud. Silently returning an empty table is what let a
        // probe conclude "the app has no data" when the listing had simply
        // failed, so an empty table here means exactly one thing: the directory
        // exists and is empty.
        if (!dir.exists()) {
            throw new LuaError("no such directory: " + path);
        }
        if (!dir.isDirectory()) {
            throw new LuaError("not a directory: " + path + " (use app.read for a file)");
        }
        File[] children = dir.listFiles();
        if (children == null) {
            throw new LuaError("cannot list " + path + " - permission or I/O error");
        }
        LuaTable table = new LuaTable();
        int i = 1;
        for (File child : children) {
            LuaTable entry = new LuaTable();
            entry.set("name", LuaValue.valueOf(child.getName()));
            entry.set("dir", LuaValue.valueOf(child.isDirectory()));
            entry.set("size", LuaValue.valueOf(child.length()));
            table.set(i++, entry);
        }
        return table;
    }

    private static String readText(String path, int max) throws Exception {
        File file = new File(path);
        if (!file.exists()) {
            throw new LuaError("no such file: " + path);
        }
        StringBuilder sb = new StringBuilder();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
            char[] buffer = new char[8192];
            int read;
            while (sb.length() < max && (read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, Math.min(read, max - sb.length()));
            }
        }
        return sb.toString();
    }

    // ---- native ------------------------------------------------------------

    /**
     * The native half: what the Java layer cannot reach.
     *
     * <p>Handles, addresses and returned words are <em>hex strings</em>, not
     * numbers. Lua's numbers are doubles here, so a pointer survives the trip
     * into Lua only if it happens to fit in 53 bits - one did not, came back
     * four bytes off, and took the target process down with it at the next
     * dlsym. A string is exact, prints readably, and makes the representation
     * obvious rather than implicit.
     *
     * <p>Arguments and results are machine words, so this calls integer and
     * pointer functions only: a function taking or returning a float, a double
     * or a struct by value has no representation here. Reading or writing a bad
     * address is fatal to the target process, which is the nature of reaching
     * into another program's memory rather than something to guard against.
     */
    private static LuaTable nativeApi() {
        LuaTable api = new LuaTable();
        api.set("status", fn(a -> LuaValue.valueOf(NativeRuntime.status())));
        api.set("error", fn(a -> LuaValue.valueOf(NativeRuntime.lastError())));
        api.set("probe", fn(a -> {
            requireNative();
            return LuaValue.valueOf(NativeRuntime.probe());
        }));
        api.set("open", fn(a -> {
            requireNative();
            int id = NativeRuntime.openLibrary(a.checkjstring(1));
            // An ordinary miss, like app.class: nil, with error() saying why.
            return id == 0 ? LuaValue.NIL : LuaValue.valueOf(id);
        }));
        api.set("symbol", fn(a -> {
            requireNative();
            int id = a.arg(1).isnil() ? 0 : a.arg(1).toint();
            long address = NativeRuntime.findSymbol(id, a.checkjstring(2));
            return address == 0 ? LuaValue.NIL : LuaValue.valueOf(hex(address));
        }));
        api.set("call", fn(a -> {
            requireNative();
            int arity = Math.max(0, a.narg() - 1);
            if (arity > 6) {
                throw new LuaError("app.native.call takes at most six arguments, got " + arity);
            }
            long[] arguments = new long[6];
            for (int i = 0; i < arity; i++) {
                arguments[i] = word(a.arg(2 + i));
            }
            long result = NativeRuntime.call(address(a.arg(1)), arity, arguments[0], arguments[1],
                    arguments[2], arguments[3], arguments[4], arguments[5]);
            return LuaValue.valueOf(hex(result));
        }));
        api.set("add", fn(a -> LuaValue.valueOf(hex(address(a.arg(1)) + word(a.arg(2))))));
        api.set("number", fn(a -> {
            // The exact value, or nothing: silently rounding a 64-bit address into
            // a double is how the pointer above got corrupted in the first place.
            long value = address(a.arg(1));
            double asDouble = (double) value;
            return (long) asDouble == value
                    ? LuaValue.valueOf(value) : LuaValue.NIL;
        }));
        api.set("read", fn(a -> {
            requireNative();
            int length = a.arg(2).toint();
            if (length <= 0 || length > MAX_NATIVE_READ) {
                throw new LuaError("app.native.read takes a length between 1 and "
                        + MAX_NATIVE_READ + ", got " + length);
            }
            byte[] bytes = NativeRuntime.readMemory(address(a.arg(1)), length);
            if (bytes == null) {
                throw new LuaError("nothing read from that address");
            }
            LuaTable out = new LuaTable();
            for (int i = 0; i < bytes.length; i++) {
                out.set(i + 1, LuaValue.valueOf(bytes[i] & 0xFF));
            }
            return out;
        }));
        api.set("write", fn(a -> {
            requireNative();
            LuaValue bytes = a.arg(2);
            if (!bytes.istable()) {
                throw new LuaError("app.native.write takes a table of byte values");
            }
            int length = bytes.length();
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) {
                out[i] = (byte) (bytes.get(i + 1).toint() & 0xFF);
            }
            return LuaValue.valueOf(NativeRuntime.writeMemory(address(a.arg(1)), out));
        }));
        api.set("string", fn(a -> {
            requireNative();
            int max = a.narg() > 1 ? a.arg(2).toint() : 256;
            byte[] bytes = NativeRuntime.readMemory(address(a.arg(1)),
                    Math.min(max, MAX_NATIVE_READ));
            if (bytes == null) {
                throw new LuaError("nothing read from that address");
            }
            int end = 0;
            while (end < bytes.length && bytes[end] != 0) {
                end++;
            }
            return LuaValue.valueOf(new String(bytes, 0, end, StandardCharsets.ISO_8859_1));
        }));
        return api;
    }

    private static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    /** Accepts "0x…", plain hex, or a number, so a literal is still convenient. */
    private static long address(LuaValue value) {
        if (value == null || value.isnil()) {
            return 0;
        }
        if (value.isnumber()) {
            return value.tolong();
        }
        String text = value.checkjstring().trim();
        if (text.isEmpty() || "0".equals(text)) {
            return 0;
        }
        try {
            return Long.parseUnsignedLong(text.startsWith("0x") ? text.substring(2) : text, 16);
        } catch (NumberFormatException e) {
            throw new LuaError("not an address or a number: " + text);
        }
    }

    /** An argument of a native call: a word, from a hex string or a number. */
    private static long word(LuaValue value) {
        return value.isnumber() ? value.tolong() : address(value);
    }

    /** @throws LuaError when the library could not be loaded, saying why */
    private static void requireNative() {
        String problem = NativeRuntime.ensureLoaded();
        if (problem != null) {
            throw new LuaError(problem);
        }
    }

    // ---- databases ---------------------------------------------------------

    /**
     * Opens a SQLite database read-only.
     *
     * <p>Read-only is the point, not a limitation. This is for seeing what an
     * application stores - the fastest way to understand an app whose classes are
     * obfuscated. A handle that cannot write cannot corrupt a database the
     * application still has open, and writing belongs through the application's
     * own APIs anyway: they keep its caches, observers and notifications in step,
     * which a raw UPDATE would not. Reading a database is the same privilege as
     * reading its file, which app.read already offers.
     */
    private static LuaValue openDatabase(String path, List<SQLiteDatabase> opened) {
        File file = new File(path);
        if (!file.exists()) {
            throw new LuaError("no database at " + path);
        }
        requireSqliteFile(file, path);
        SQLiteDatabase database;
        try {
            database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) {
            throw new LuaError("could not open " + path + " read-only: " + messageOf(t));
        }
        opened.add(database);

        LuaTable handle = new LuaTable();
        handle.set("path", LuaValue.valueOf(path));
        handle.set("tables", fn(a -> databaseTables(database)));
        handle.set("schema", fn(a -> databaseSchema(database, a.checkjstring(1))));
        handle.set("query", fn(a -> databaseQuery(database, a.checkjstring(1), rest(a, 1))));
        handle.set("one", fn(a -> {
            LuaTable rows = databaseQuery(database, a.checkjstring(1), rest(a, 1));
            return rows.length() > 0 ? rows.get(1) : LuaValue.NIL;
        }));
        return handle;
    }

    /**
     * Refuses a file that cannot be a database, before a handle is handed back.
     *
     * <p>{@code SQLiteDatabase.openDatabase} is lazy: it does not look at the
     * file until the first statement. Without this, a script walking a
     * {@code databases/} directory gets a handle back for every {@code -wal},
     * {@code -shm} and {@code -journal} file sitting beside the real databases,
     * {@code pcall(app.db, path)} succeeds, and the failure surfaces later at the
     * first query as "file is not a database" blamed on the query. Checking the
     * header puts the refusal where the mistake was made.
     *
     * <p>An empty file is allowed: SQLite leaves one behind before the first
     * write and treats it as an empty database, so refusing it would be wrong.
     */
    private static void requireSqliteFile(File file, String path) {
        if (file.length() == 0) {
            return;
        }
        byte[] header = new byte[SQLITE_MAGIC.length];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.read(header);
        } catch (Throwable t) {
            throw new LuaError("could not read " + path + ": " + messageOf(t));
        }
        if (read == SQLITE_MAGIC.length && Arrays.equals(header, SQLITE_MAGIC)) {
            return;
        }
        throw new LuaError(path + " is not a SQLite database. The -wal, -shm and -journal files"
                + " beside a real one are not databases, and neither is anything else in that"
                + " directory.");
    }

    private static LuaValue databaseTables(SQLiteDatabase database) {
        LuaTable out = new LuaTable();
        try (Cursor cursor = database.rawQuery("select name from sqlite_master where type = 'table'"
                + " and name not like 'sqlite_%' order by name", null)) {
            int i = 1;
            while (cursor.moveToNext()) {
                out.set(i++, LuaValue.valueOf(cursor.getString(0)));
            }
        }
        return out;
    }

    private static LuaValue databaseSchema(SQLiteDatabase database, String table) {
        LuaTable out = new LuaTable();
        try (Cursor cursor = database.rawQuery("pragma table_info(" + quote(table) + ")", null)) {
            if (!cursor.moveToFirst()) {
                throw new LuaError("no table " + table + " here - db.tables() lists them");
            }
            int name = cursor.getColumnIndex("name");
            int type = cursor.getColumnIndex("type");
            int notNull = cursor.getColumnIndex("notnull");
            int primaryKey = cursor.getColumnIndex("pk");
            int i = 1;
            do {
                StringBuilder line = new StringBuilder(cursor.getString(name));
                String declared = cursor.getString(type);
                if (declared != null && !declared.isEmpty()) {
                    line.append(' ').append(declared);
                }
                if (cursor.getInt(notNull) != 0) {
                    line.append(" NOT NULL");
                }
                if (cursor.getInt(primaryKey) != 0) {
                    line.append(" PRIMARY KEY");
                }
                out.set(i++, LuaValue.valueOf(line.toString()));
            } while (cursor.moveToNext());
        }
        return out;
    }

    private static LuaTable databaseQuery(SQLiteDatabase database, String sql, LuaValue[] args) {
        String[] bound = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            bound[i] = args[i].isnil() ? null : args[i].tojstring();
        }
        LuaTable rows = new LuaTable();
        try (Cursor cursor = database.rawQuery(sql, bound)) {
            String[] columns = cursor.getColumnNames();
            int written = 0;
            while (cursor.moveToNext()) {
                if (written == DB_ROW_LIMIT) {
                    // Say the list is a prefix rather than quietly returning one.
                    rows.set("truncated", LuaValue.TRUE);
                    break;
                }
                LuaTable row = new LuaTable();
                for (int c = 0; c < columns.length; c++) {
                    row.set(columns[c], columnValue(cursor, c));
                }
                rows.set(++written, row);
            }
        }
        return rows;
    }

    private static LuaValue columnValue(Cursor cursor, int index) {
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_INTEGER:
                return LuaValue.valueOf(cursor.getLong(index));
            case Cursor.FIELD_TYPE_FLOAT:
                return LuaValue.valueOf(cursor.getDouble(index));
            case Cursor.FIELD_TYPE_STRING:
                return LuaValue.valueOf(cursor.getString(index));
            case Cursor.FIELD_TYPE_BLOB:
                byte[] blob = cursor.getBlob(index);
                // Not something to hand a script as a table of numbers; the size
                // is the useful part and the script can decide what to do next.
                return LuaValue.valueOf("<blob " + (blob == null ? 0 : blob.length) + " bytes>");
            default:
                return LuaValue.NIL;
        }
    }

    /** A bare name would end the pragma's argument list early. */
    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static LuaTable methods(Object target, String filter) {        if (target == null) {
            throw new LuaError("app.methods needs a class or an instance; got nil");
        }
        Class<?> owner = target instanceof Class ? (Class<?>) target : target.getClass();
        LuaTable table = new LuaTable();
        int i = 1;
        // Constructors first, and only for an unfiltered listing: app.new is the
        // harder of the two to guess at, so this is where its options show up.
        if (filter.isEmpty()) {
            for (Constructor<?> c : owner.getDeclaredConstructors()) {
                if (i >= MAX_METHOD_LIST) {
                    break;
                }
                table.set(i++, LuaValue.valueOf("<init>(" + typeList(c.getParameterTypes()) + ")"));
            }
        }
        for (Method m : owner.getDeclaredMethods()) {
            if (!filter.isEmpty() && !m.getName().contains(filter)) {
                continue;
            }
            if (i > MAX_METHOD_LIST) {
                table.set(i, LuaValue.valueOf("... more than " + MAX_METHOD_LIST + " match"));
                break;
            }
            table.set(i++, LuaValue.valueOf(signatureOf(m)
                    + (Modifier.isStatic(m.getModifiers()) ? " static" : "")));
        }
        return table;
    }

    // ---- coercion and rendering -------------------------------------------

    /** Lua values that carry a Java object back out to Java. Type-tested, not coerced. */
    private static Object toJava(LuaValue value) {
        if (value == null) {
            return null;
        }
        switch (value.type()) {
            case LuaValue.TNIL:
                return null;
            case LuaValue.TUSERDATA:
                return ((LuaUserdata) value).m_instance;
            case LuaValue.TSTRING:
                return value.tojstring();
            case LuaValue.TBOOLEAN:
                return value.toboolean();
            case LuaValue.TNUMBER:
                return value.isint() ? (Object) value.toint() : (Object) value.todouble();
            default:
                return null;
        }
    }

    /**
     * Wraps a Java value for Lua.
     *
     * <p>Strings and numbers become Lua values rather than userdata, because a
     * userdata will not concatenate - {@code "id=" .. app.uid()} has to work for
     * the environment to feel like Lua at all.
     *
     * <p>Arrays become real tables rather than LuaJ's array userdata. Gradle-free
     * though that userdata is, it does not survive {@code ipairs}, and "iterate
     * the accounts" is exactly the kind of thing a script is written to do.
     */
    private static LuaValue coerce(Object value) {
        if (value == null) {
            return LuaValue.NIL;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            if (length > MAX_ARRAY_ENTRIES) {
                throw new LuaError("refusing to convert a " + length + "-element array into a"
                        + " table; read it in pieces instead");
            }
            LuaTable table = new LuaTable();
            for (int i = 0; i < length; i++) {
                table.set(i + 1, coerce(Array.get(value, i)));
            }
            return table;
        }
        if (value instanceof String) {
            return LuaValue.valueOf((String) value);
        }
        if (value instanceof Boolean) {
            return LuaValue.valueOf((Boolean) value);
        }
        if (value instanceof Integer) {
            return LuaValue.valueOf((Integer) value);
        }
        if (value instanceof Long) {
            return LuaValue.valueOf((Long) value);
        }
        if (value instanceof Double || value instanceof Float) {
            return LuaValue.valueOf(((Number) value).doubleValue());
        }
        return CoerceJavaToLua.coerce(value);
    }

    private static LuaValue[] rest(Varargs args, int skip) {
        int count = Math.max(0, args.narg() - skip);
        LuaValue[] out = new LuaValue[count];
        for (int i = 0; i < count; i++) {
            out[i] = args.arg(skip + 1 + i);
        }
        return out;
    }

    private static Object describe(LuaValue value) {
        if (value == null) {
            return JSONObject.NULL;
        }
        switch (value.type()) {
            case LuaValue.TNIL:
                return JSONObject.NULL;
            case LuaValue.TBOOLEAN:
                return value.toboolean();
            case LuaValue.TNUMBER:
                return value.isint() ? (Object) value.toint() : (Object) value.todouble();
            case LuaValue.TSTRING:
                return value.tojstring();
            case LuaValue.TTABLE:
                return describeTable((LuaTable) value);
            default:
                return value.tojstring();
        }
    }

    /** One level deep: a probe returning a table wants to see what is in it. */
    private static String describeTable(LuaTable table) {
        StringBuilder sb = new StringBuilder("{");
        LuaValue key = LuaValue.NIL;
        int shown = 0;
        while (true) {
            Varargs pair = table.next(key);
            key = pair.arg1();
            if (key.isnil()) {
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            if (++shown > MAX_TABLE_ENTRIES) {
                sb.append("...");
                break;
            }
            LuaValue entry = pair.arg(2);
            sb.append(key.tojstring()).append('=');
            if (entry.istable()) {
                sb.append("{...}");
            } else if (entry.isuserdata()) {
                sb.append(userdataText(entry));
            } else {
                sb.append(entry.tojstring());
            }
        }
        return sb.append('}').toString();
    }

    private static String userdataText(LuaValue value) {
        Object java = ((LuaUserdata) value).m_instance;
        if (java == null) {
            return "nil";
        }
        try {
            String text = String.valueOf(java);
            return text.length() <= 200 ? text : text.substring(0, 200) + "...";
        } catch (Throwable t) {
            return "<" + java.getClass().getName() + ">";
        }
    }

    /**
     * A sink that stops keeping what it is told once it is full.
     *
     * <p>The output used to be capped by truncating the string afterwards, which
     * meant all of it was in memory first. That is merely wasteful for one script
     * run; for a kept hook it never ended. The closure holds this stream for the
     * life of the process, and after {@code exec} returns nothing reads it again -
     * so a hook that printed on every call grew it without limit, in someone
     * else's process, with the instruction budget no defence at all because each
     * call is a separate run.
     *
     * <p>It counts bytes where the constant says characters, so multi-byte text is
     * cut a little sooner than the number promises. That is the right direction for
     * a guard to be wrong in.
     */
    private static final class CappedOutput extends java.io.OutputStream {
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean dropped;

        CappedOutput(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int b) {
            if (buffer.size() < limit) {
                buffer.write(b);
            } else {
                dropped = true;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            int room = limit - buffer.size();
            if (room <= 0) {
                dropped = true;
                return;
            }
            if (length > room) {
                buffer.write(bytes, offset, room);
                dropped = true;
            } else {
                buffer.write(bytes, offset, length);
            }
        }

        String text() {
            String captured = new String(buffer.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
            return dropped ? captured + "\n... output truncated" : captured;
        }
    }

    private static JSONObject result(boolean ok, Object returned, String error,
            CappedOutput captured, Budget budget, long startedAt,
            List<JSONObject> installed) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", ok);
        if (ok) {
            out.put("returned", returned);
        } else {
            out.put("error", error == null ? "unknown error" : error);
        }
        out.put("output", captured.text());
        out.put("instructions", budget.used());
        out.put("maxInstructions", budget.limit());
        out.put("durationMs", System.currentTimeMillis() - startedAt);
        if (installed != null && !installed.isEmpty()) {
            // Reported even when the script failed afterwards: a hook it managed
            // to install is live in this process whether the script finished or not.
            out.put("hooksInstalled", new org.json.JSONArray(installed));
        }
        return out;
    }

    private static String messageOf(Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null
                ? t.getCause() : t;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Total fit of a set of values against one parameter list; negative means impossible. */
    private static int scoreArgList(LuaValue[] args, Class<?>[] types) {
        int total = 0;
        for (int i = 0; i < args.length; i++) {
            int cost = fit(args[i], types[i]);
            if (cost < 0) {
                return -1;
            }
            total += cost;
        }
        return total;
    }

    /**
     * How well one Lua value fits one Java parameter; negative means it cannot.
     *
     * <p>Dispatch is on {@link LuaValue#type()}, never on {@code isstring()} or
     * {@code isnumber()}: in LuaJ those are <em>coercion</em> predicates, not type
     * tests. A number answers true to {@code isstring()}, and the string "123"
     * answers true to {@code isnumber()}. Branching on them silently prefers
     * Date(String) over Date(long) for a number, makes every numeric overload of
     * Math.max tie, and stores "123" as an Integer.
     */
    private static int fit(LuaValue value, Class<?> type) {
        switch (value.type()) {
            case LuaValue.TNIL:
                // null fits any reference; it cannot fit a primitive.
                return type.isPrimitive() ? -1 : 0;
            case LuaValue.TUSERDATA: {
                Object java = ((LuaUserdata) value).m_instance;
                if (java == null) {
                    return type.isPrimitive() ? -1 : 0;
                }
                if (!type.isInstance(java)) {
                    return -1;
                }
                return type == Object.class ? 20 : 100;
            }
            case LuaValue.TBOOLEAN:
                if (type == boolean.class || type == Boolean.class) {
                    return 101;
                }
                if (type == Object.class) {
                    return 20;
                }
                return type == String.class || type == CharSequence.class ? 15 : -1;
            case LuaValue.TNUMBER: {
                boolean whole = value.isint();
                if (type == int.class || type == Integer.class) return whole ? 101 : 60;
                if (type == long.class || type == Long.class) return whole ? 91 : 65;
                if (type == short.class || type == Short.class) return whole ? 61 : -1;
                if (type == byte.class || type == Byte.class) return whole ? 51 : -1;
                if (type == double.class || type == Double.class) return whole ? 80 : 101;
                if (type == float.class || type == Float.class) return whole ? 70 : 91;
                if (type == char.class || type == Character.class) return whole ? 40 : -1;
                if (type == Object.class) return 20;
                if (type == String.class || type == CharSequence.class) return 25;
                return -1;
            }
            case LuaValue.TSTRING:
                if (type == String.class) {
                    return 100;
                }
                if (type == CharSequence.class) {
                    return 90;
                }
                if (type == Object.class) {
                    return 20;
                }
                if (type == char.class || type == Character.class) {
                    return 40;
                }
                return isNumeric(type) || type == boolean.class || type == Boolean.class ? 15 : -1;
            case LuaValue.TTABLE:
                if (type.isArray()) return 60;
                if (Collection.class.isAssignableFrom(type)) return 40;
                if (Map.class.isAssignableFrom(type)) return 30;
                return type == Object.class ? 20 : -1;
            default:
                return -1;
        }
    }

    /**
     * Converts one Lua value to one Java parameter type.
     *
     * <p>LuaJ's coercion table is keyed by the boxed types, so a primitive
     * parameter has to be boxed first or the lookup misses and returns null -
     * which then fails as "constructor threw IllegalArgumentException: null",
     * a message that says nothing about the real cause. Reflection unboxes on
     * the way in, so handing it a Long for a long parameter is correct.
     */
    private static Object convert(LuaValue value, Class<?> type) {
        return CoerceLuaToJava.coerce(value, boxed(type));
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == Integer.class
                || type == long.class || type == Long.class
                || type == short.class || type == Short.class
                || type == byte.class || type == Byte.class
                || type == double.class || type == Double.class
                || type == float.class || type == Float.class;
    }

    /**
     * What {@code app.call} found when it looked for a method.
     *
     * <p>The search walks up from the receiver's own class, which is the way
     * Java resolves it: an implementation class declares no more than it
     * overrides, and a method is often declared on a superclass.
     */
    private static final class Search {
        /** Candidates, or {@code null} when nothing matched. */
        List<Method> matching;
        /** Everything at this name and arity, ignoring the wanted signature. */
        List<Method> sameShape;
    }

    private static Search search(Class<?> owner, String methodName, int arity, String[] wanted) {
        Search result = new Search();
        for (Class<?> c = owner; c != null && result.matching == null; c = c.getSuperclass()) {
            List<Method> here = new ArrayList<>();
            List<Method> shape = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.getParameterCount() != arity) {
                    continue;
                }
                shape.add(m);
                if (wanted == null || matchesSignature(m.getParameterTypes(), wanted)) {
                    here.add(m);
                }
            }
            if (result.sameShape == null && !shape.isEmpty()) {
                result.sameShape = shape;
            }
            if (!here.isEmpty()) {
                result.matching = here;
            }
        }
        return result;
    }

    /** Matches a parameter list written after a name, e.g. {@code put(String,int)}. */
    private static boolean matchesSignature(Class<?>[] types, String[] wanted) {
        if (types.length != wanted.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            String token = wanted[i];
            if (!token.equals(types[i].getName()) && !token.equals(types[i].getSimpleName())) {
                return false;
            }
        }
        return true;
    }

    /** The form app.methods prints and app.call takes back. */
    private static String signatureOf(Method method) {
        return method.getName() + "(" + typeList(method.getParameterTypes()) + ")";
    }

    private static String constructorSignatures(List<Constructor<?>> constructors) {
        StringBuilder sb = new StringBuilder();
        for (Constructor<?> c : constructors) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("<init>(").append(typeList(c.getParameterTypes())).append(')');
        }
        return sb.toString();
    }

    private static String signatures(List<Method> methods) {
        StringBuilder sb = new StringBuilder();
        for (Method m : methods) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(signatureOf(m));
        }
        return sb.toString();
    }

    private static String typeList(Class<?>[] types) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(types[i].getSimpleName());
        }
        return sb.toString();
    }
}
