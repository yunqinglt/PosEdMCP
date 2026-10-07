package dev.posedmcp.xposed;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.InMemoryDexClassLoader;
import de.robv.android.xposed.XposedHelpers;
import dev.posedmcp.Logx;
import dev.posedmcp.ipc.BridgeClient;
import dev.posedmcp.ipc.BridgeAuth;
import dev.posedmcp.ipc.Wire;
import dev.posedmcp.plugin.HookApi;
import dev.posedmcp.plugin.PluginContext;
import dev.posedmcp.plugin.PluginEntry;

/**
 * Lives inside each scoped application's process.
 *
 * <p>It does two things: keeps a bridge connection open so the app process can
 * be reached, and loads plugins into itself on request. Plugins arrive as DEX
 * bytes over that connection and are handed to an {@link InMemoryDexClassLoader},
 * so nothing has to be written to disk - which matters because the target app's
 * data directory is not writable by the module and pushing files would require
 * a confirmed root shell for every injection.
 */
public final class AppHost {

    private static final Map<String, Loaded> LOADED = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean INSTALLED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile BridgeClient client;

    private AppHost() {
    }

    private static final class Loaded {
        final String className;
        final Class<?> clazz;
        final Object instance;
        /** Non-null when the plugin implements {@link PluginEntry}. */
        final PluginEntry entry;
        final PluginContextImpl context;

        Loaded(String className, Class<?> clazz, Object instance, PluginEntry entry,
                PluginContextImpl context) {
            this.className = className;
            this.clazz = clazz;
            this.instance = instance;
            this.entry = entry;
            this.context = context;
        }
    }

    /**
     * Called from {@code handleLoadPackage} for every non-system scoped package.
     *
     * <p>Everything happens on a background thread: this runs while the host
     * application is still starting, and looking up the bridge credentials can
     * block (binding a service, waiting on a handshake). Doing that on the main
     * thread would delay or deadlock the application we are a guest in.
     */
    public static void install(String packageName, ClassLoader appClassLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> installBlocking(packageName, appClassLoader),
                "posedmcp-apphost-" + packageName);
        t.setDaemon(true);
        t.start();
    }

    private static void installBlocking(String packageName, ClassLoader appClassLoader) {
        try {
            dev.posedmcp.HiddenApi.exempt();
            BridgeClient bridge = new BridgeClient(Wire.ROLE_APP, packageName,
                    BridgeAuth.bridgePort());
            bridge.registerHandler("load_plugin", args -> loadPlugin(packageName, appClassLoader, args));
            bridge.registerHandler("invoke_plugin",
                    args -> invokePlugin(packageName, appClassLoader, args));
            bridge.registerHandler("list_plugins", args -> listPlugins());
            bridge.registerHandler("unload_plugin", args -> unloadPlugin(args));
            // Runtime observation and alteration: the dynamic half of static
            // analysis. No plugin DEX involved - the module installs the hook
            // directly, so an agent can go from a smali listing to changing what
            // a method does without compiling anything.
            bridge.registerHandler("hook_method", args -> HookRegistry.install(
                    args.optString("class", ""),
                    args.optString("method", ""),
                    args.optString("params", ""),
                    args.optInt("max_records", 200),
                    args,
                    appClassLoader));
            bridge.registerHandler("hook_records", args -> HookRegistry.records(
                    args.optString("subject", ""), args.optInt("limit", 100)));
            bridge.registerHandler("hook_clear", args -> HookRegistry.clear(
                    args.optString("subject", "")));
            // The other half of injection: make a call as the application, with
            // its class loader and its privileges, so private and unexported
            // methods are reachable without a plugin DEX.
            bridge.registerHandler("invoke_method", args -> MethodInvoker.invoke(
                    args.optString("class", ""),
                    args.optString("method", ""),
                    args.optString("params", ""),
                    args.optJSONArray("args"),
                    args.optString("instance_class", ""),
                    args.optString("instance_field", ""),
                    args.optString("instance_method", ""),
                    appClassLoader));
            // Logic, rather than a single call. The interpreter is part of this
            // module, so it is already here in the target process - nothing is
            // compiled and nothing is pushed over the bridge.
            bridge.registerHandler("lua_exec", args -> {
                // Only the app it belongs to knows where the module APK is, and
                // only the module needs it - to load its own native library from
                // inside someone else's process.
                NativeRuntime.setModuleApk(args.optString("module_apk", ""));
                return LuaRuntime.exec(
                        packageName,
                        appClassLoader,
                        currentApplication(),
                        args.optString("source", ""),
                        args.optLong("max_instructions", LuaRuntime.DEFAULT_MAX_INSTRUCTIONS));
            });
            bridge.registerHandler("ping", args -> new JSONObject().put("pong", true)
                    // Which hook framework loaded us, and therefore which HookApi
                    // every hook in this process is built on. Read here rather
                    // than in the app process on purpose: this class runs inside
                    // the injected process, where the framework's own classes
                    // exist. The app process has neither API on its classpath, so
                    // asking it would mean loading classes that are not there.
                    .put("framework", Framework.describe())
                    .put("hooks", HookRegistry.snapshot().size()));
            bridge.start();
            client = bridge;
            // Records are pushed out as they are made rather than read back on
            // demand: this app may be reclaimed seconds from now, and a record
            // that only exists inside it is a record nobody will ever see.
            HookRegistry.setRecordSink(
                    new RecordFeed("posedmcp-hook-feed-" + packageName,
                            record -> emit(Wire.HOOK_RECORD_EVENT, record)));
        } catch (Throwable t) {
            Logx.e("AppHost.install failed for " + packageName, t);
        }
    }

    public static void emit(String type, JSONObject data) {
        BridgeClient bridge = client;
        if (bridge != null) {
            bridge.emit(type, data);
        }
    }

    /**
     * The target application's own Context, or {@code null} while it is still
     * starting.
     *
     * <p>Asked of the platform first, and of the hook framework second. The
     * framework route is the obvious one - it is what the API is for - but it is
     * also the one that varies: on Vector, which loads classic modules through a
     * compatibility bridge, {@code AndroidAppHelper.currentApplication()}
     * returns null, so every script that needed a Context found nil here. The
     * platform route has no such dependency: the Application is a public object
     * that {@code ActivityThread} has been willing to hand out since forever.
     * Measured working under both frameworks.
     *
     * <p>No caching. It is null before the Application exists, and the module is
     * often loaded before that; a cached null would be permanent.
     */
    static Context currentApplication() {
        Context viaActivityThread = applicationFromActivityThread();
        return viaActivityThread != null ? viaActivityThread : applicationFromAppHelper();
    }

    /** The Application straight out of the platform's own activity thread. */
    private static Context applicationFromActivityThread() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Object application = thread.getClass().getMethod("getApplication").invoke(thread);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The framework's helper, for frameworks where it works. */
    private static Context applicationFromAppHelper() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.AndroidAppHelper"), "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- ops --------------------------------------------------------------

    private static JSONObject loadPlugin(String packageName, ClassLoader appClassLoader,
            JSONObject args) throws Exception {
        String className = args.optString("class_name", "");
        String base64 = args.optString("dex_base64", "");
        if (className.isEmpty() || base64.isEmpty()) {
            throw new IllegalArgumentException("class_name and dex_base64 are required");
        }

        byte[] dex;
        try {
            dex = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        } catch (Throwable t) {
            throw new IllegalArgumentException("dex_base64 is not valid base64");
        }
        if (dex.length < 8 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') {
            throw new IllegalArgumentException("payload is not a DEX file");
        }

        // Parent is this module's loader, so a plugin can reference
        // dev.posedmcp.plugin.* without recompiling it into its own DEX.
        ClassLoader dexLoader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex),
                AppHost.class.getClassLoader());

        Class<?> clazz = Class.forName(className, true, dexLoader);
        Object instance = clazz.getDeclaredConstructor().newInstance();

        PluginContextImpl context = new PluginContextImpl(packageName, appClassLoader, dexLoader);
        PluginEntry entry = instance instanceof PluginEntry ? (PluginEntry) instance : null;

        try {
            if (entry != null) {
                entry.onLoad(context);
            } else {
                invokeReflectively(instance, clazz, "onLoad",
                        new Class<?>[]{PluginContext.class}, new Object[]{context});
            }
        } catch (Throwable t) {
            throw new IllegalStateException("plugin onLoad() threw: " + t, t);
        }

        Loaded previous = LOADED.put(className,
                new Loaded(className, clazz, instance, entry, context));
        if (previous != null) {
            unloadQuietly(previous);
        }

        emit("plugin.loaded", new JSONObject()
                .put("package", packageName)
                .put("class", className)
                .put("dexBytes", dex.length));

        JSONObject out = new JSONObject();
        out.put("loaded", true);
        out.put("class", className);
        out.put("dexBytes", dex.length);
        out.put("api", entry != null ? "PluginEntry" : "reflection");
        return out;
    }

    private static JSONObject invokePlugin(String packageName, ClassLoader appClassLoader,
            JSONObject args) throws Exception {
        String className = args.optString("class_name", "");
        String method = args.optString("method", "");
        String argsJson = args.optString("args_json", "[]");

        Loaded loaded = LOADED.get(className);
        if (loaded == null) {
            throw new IllegalStateException("plugin '" + className + "' is not loaded in "
                    + packageName + "; call plugin_load first");
        }

        Object result;
        if (loaded.entry != null) {
            result = loaded.entry.invoke(method, argsJson);
        } else {
            result = invokeReflectively(loaded.instance, loaded.clazz, method,
                    new Class<?>[]{String.class, String.class}, new Object[]{method, argsJson});
        }

        JSONObject out = new JSONObject();
        out.put("class", className);
        out.put("method", method);
        out.put("result", result == null ? JSONObject.NULL : String.valueOf(result));
        return out;
    }

    private static JSONObject listPlugins() throws Exception {
        JSONArray array = new JSONArray();
        for (Loaded loaded : LOADED.values()) {
            JSONObject o = new JSONObject();
            o.put("class", loaded.className);
            o.put("api", loaded.entry != null ? "PluginEntry" : "reflection");
            array.put(o);
        }
        JSONObject out = new JSONObject();
        out.put("plugins", array);
        return out;
    }

    private static JSONObject unloadPlugin(JSONObject args) throws Exception {
        String className = args.optString("class_name", "");
        Loaded removed = LOADED.remove(className);
        if (removed != null) {
            unloadQuietly(removed);
        }
        JSONObject out = new JSONObject();
        out.put("unloaded", removed != null);
        return out;
    }

    private static void unloadQuietly(Loaded loaded) {
        try {
            if (loaded.entry != null) {
                loaded.entry.onUnload();
            }
        } catch (Throwable t) {
            Logx.w("plugin onUnload threw: " + t);
        }
    }

    private static Object invokeReflectively(Object instance, Class<?> clazz, String method,
            Class<?>[] parameterTypes, Object[] arguments) throws Exception {
        try {
            Method m = clazz.getMethod(method, parameterTypes);
            m.setAccessible(true);
            return m.invoke(instance, arguments);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("plugin '" + clazz.getName()
                    + "' has no method " + method + " with the expected signature");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("plugin " + method + "() threw: " + cause, cause);
        }
    }

    /** The context handed to plugins. */
    private static final class PluginContextImpl implements PluginContext {
        private final String packageName;
        private final ClassLoader appClassLoader;
        private final ClassLoader pluginClassLoader;
        private final HookApi hooks;

        PluginContextImpl(String packageName, ClassLoader appClassLoader,
                ClassLoader pluginClassLoader) {
            this.packageName = packageName;
            this.appClassLoader = appClassLoader;
            this.pluginClassLoader = pluginClassLoader;
            this.hooks = new XposedHookApi(appClassLoader);
        }

        @Override
        public String packageName() {
            return packageName;
        }

        @Override
        public Context appContext() {
            return currentApplication();
        }

        @Override
        public ClassLoader appClassLoader() {
            return appClassLoader;
        }

        @Override
        public ClassLoader pluginClassLoader() {
            return pluginClassLoader;
        }

        @Override
        public void log(String message) {
            Logx.i("[" + packageName + "] " + message);
        }

        @Override
        public HookApi hooks() {
            return hooks;
        }
    }
}
