package dev.posedmcp.xposed;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.posedmcp.Logx;
import dev.posedmcp.ipc.BridgeAuth;
import dev.posedmcp.ipc.BridgeClient;
import dev.posedmcp.ipc.Wire;
import dev.posedmcp.state.SavedHook;

/**
 * The module's half inside system_server.
 *
 * <p>It answers the privileged ops the app asks for and pushes device events
 * back. The parts that were here first hook nothing: screen capture, input
 * injection and foreground tracking go through public broadcasts and reflective
 * calls into {@code IActivityTaskManager} inside try/catch, so a platform change
 * can degrade a feature but can never take system_server down with it.
 *
 * <p>The hook and scripting ops below are the opposite bargain, and were added
 * on purpose rather than by drift. A hook installed here runs in the one process
 * whose death reboots the device, and a <i>kept</i> hook is put back before the
 * user can reach the app that would take it off. That is what
 * {@code magisk/posedmcp-guard} is for, and why the tool that arms one says so
 * out loud.
 */
public final class SystemHooks {

    private static final long POLL_INTERVAL_MS = 2000L;

    private static final SystemOps OPS = new SystemOps();
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /** system_server's own loader, which is what a hook in here has to resolve against. */
    private static volatile ClassLoader LOADER;

    private static volatile BridgeClient client;
    private static volatile Context systemContext;
    private static volatile String lastForeground = "";
    private static volatile boolean screenOn = true;

    private SystemHooks() {
    }

    public static void install(ClassLoader systemClassLoader) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        LOADER = resolvableLoader(systemClassLoader);
        // Off the main thread: system_server is in the middle of starting its
        // services, and resolving the bridge credentials can block.
        Thread t = new Thread(SystemHooks::installBlocking, "posedmcp-system-install");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The loader every name is resolved against inside system_server.
     *
     * <p>Not the framework's own, which cannot see everything this process runs:
     * measured, {@code com.android.server.am.ActivityManagerService} is not
     * resolvable through it — nor is anything in {@code oplus-services.jar},
     * which is where OPPO's own AMS extensions live. The thread's context loader
     * resolves all of those, and it is the one the code we are aiming at was
     * loaded with.
     *
     * <p>Asked first, the framework's loader still answers for everything it
     * already could, so the hooks that were working keep resolving exactly as
     * before; the context loader is only reached for what it cannot see. Read
     * here, on the thread the framework called us on, because a thread's context
     * loader is inherited at creation and this is the moment it is meaningful.
     */
    private static ClassLoader resolvableLoader(ClassLoader fromFramework) {
        ClassLoader context = null;
        try {
            context = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ignored) {
        }
        if (context == null || context == fromFramework) {
            return fromFramework;
        }
        Logx.i("system_server class lookups will try the framework's loader, then the"
                + " context loader (" + context + ")");
        return new LayeredLoader(fromFramework, context, ClassLoader.getSystemClassLoader());
    }

    private static void installBlocking() {
        try {
            // Screen capture and input injection both go through hidden platform
            // APIs; without this the reflection looks like it found nothing.
            dev.posedmcp.HiddenApi.exempt();
            systemContext = systemContext();

            BridgeClient bridge = new BridgeClient(Wire.ROLE_SYSTEM, "", BridgeAuth.bridgePort());
            bridge.registerHandler("status", args -> status());
            bridge.registerHandler("screenshot", args -> screenshot());
            bridge.registerHandler("input", args -> OPS.inject(args));
            bridge.registerHandler("foreground", args -> foreground());
            bridge.registerHandler("ping", args -> new JSONObject().put("pong", true)
                    .put("framework", Framework.describe())
                    .put("hooks", HookRegistry.snapshot().size()));
            bridge.registerHandler("probe_display", args -> OPS.probeDisplay());
            // The same hook surface an ordinary app gets, on the process that
            // needed it most. HookRegistry, LuaRuntime and MethodInvoker want
            // nothing but a ClassLoader and, for the Lua bindings, a Context that
            // may be null - so registering them here is the whole of it; none of
            // them is reimplemented for this process.
            bridge.registerHandler("hook_method", args -> HookRegistry.install(
                    args.optString("class", ""),
                    args.optString("method", ""),
                    args.optString("params", ""),
                    args.optInt("max_records", 200),
                    args,
                    LOADER));
            bridge.registerHandler("hook_records", args -> HookRegistry.records(
                    args.optString("subject", ""), args.optInt("limit", 100)));
            bridge.registerHandler("hook_clear", args -> HookRegistry.clear(
                    args.optString("subject", "")));
            bridge.registerHandler("invoke_method", args -> MethodInvoker.invoke(
                    args.optString("class", ""),
                    args.optString("method", ""),
                    args.optString("params", ""),
                    args.optJSONArray("args"),
                    args.optString("instance_class", ""),
                    args.optString("instance_field", ""),
                    args.optString("instance_method", ""),
                    LOADER));
            bridge.registerHandler("lua_exec", args -> {
                // Only for scripts that reach the native layer; the library is
                // loaded lazily, not here.
                NativeRuntime.setModuleApk(args.optString("module_apk", ""));
                return LuaRuntime.exec(
                        SavedHook.SYSTEM_PACKAGE,
                        LOADER,
                        systemContext(),
                        args.optString("source", ""),
                        args.optLong("max_instructions", LuaRuntime.DEFAULT_MAX_INSTRUCTIONS));
            });
            bridge.start();
            client = bridge;
            // Same bargain as in an ordinary app, for the opposite reason: this
            // process outlives every app, so its records are the ones most worth
            // keeping, and the app may restart long before they are read back.
            HookRegistry.setRecordSink(new RecordFeed("posedmcp-hook-feed-system",
                    record -> emit(Wire.HOOK_RECORD_EVENT, record)));
            Logx.i("system hooks installed in system_server");

            // Screen state comes from broadcasts, which need the system Context.
            // system_server loads its packages very early, long before that
            // Context exists, so this retries rather than giving up.
            registerScreenReceiverWhenReady(0);
            startForegroundPoller();
        } catch (Throwable t) {
            Logx.e("SystemHooks.install failed", t);
        }
    }

    private static void registerScreenReceiverWhenReady(int attempt) {
        if (systemContext != null) {
            registerScreenReceiver();
            return;
        }
        systemContext = systemContext();
        if (systemContext != null) {
            registerScreenReceiver();
            return;
        }
        if (attempt >= 12) {
            Logx.w("no system context after " + attempt + " attempts; screen events unavailable");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            registerScreenReceiverWhenReady(attempt + 1);
        }, "posedmcp-system-context");
        t.setDaemon(true);
        t.start();
    }

    private static void emit(String type, JSONObject data) {
        BridgeClient bridge = client;
        if (bridge != null) {
            bridge.emit(type, data);
        }
    }

    // =====================================================================
    // Ops
    // =====================================================================

    private static JSONObject status() throws Exception {
        JSONObject out = new JSONObject();
        out.put("process", "system_server");
        out.put("sdkInt", android.os.Build.VERSION.SDK_INT);
        out.put("release", android.os.Build.VERSION.RELEASE);
        out.put("foreground", lastForeground);
        out.put("screenOn", screenOn);
        out.put("screenshotPath", screenshotAvailable());
        out.put("lastScreenshotError", OPS.lastCaptureError());
        out.put("bridgePort", BridgeAuth.bridgePort());
        return out;
    }

    private static String screenshotAvailable() {
        if (Reflect.findClass("android.window.ScreenCapture") != null) {
            return "ScreenCapture";
        }
        if (Reflect.findClass("android.view.SurfaceControl") != null) {
            return "SurfaceControl";
        }
        return "unavailable";
    }

    private static JSONObject screenshot() throws Exception {
        JSONObject out = new JSONObject();
        out.put("png_base64", OPS.screenshotBase64());
        return out;
    }

    private static JSONObject foreground() throws Exception {
        JSONObject out = new JSONObject();
        String current = lastForeground;
        if (current == null || current.isEmpty()) {
            current = queryForeground();
        }
        out.put("foreground", current == null ? "" : current);
        out.put("screenOn", screenOn);
        return out;
    }

    // =====================================================================
    // Foreground tracking
    // =====================================================================

    /**
     * Polls the top activity instead of registering a task-stack listener.
     *
     * <p>Registering a listener means reaching into {@code ActivityTaskManager}'s
     * private singleton and an AIDL interface whose shape moves between
     * releases, and a mistake there is a system_server crash. A two-second poll
     * is boring, version-proof, and only runs while something is actually
     * listening.
     */
    private static void startForegroundPoller() {
        Thread poller = new Thread(() -> {
            while (true) {
                try {
                    BridgeClient bridge = client;
                    if (bridge != null && bridge.isConnected()) {
                        String current = queryForeground();
                        if (current != null && !current.equals(lastForeground)) {
                            String previous = lastForeground;
                            lastForeground = current;
                            OPS.rememberForeground(current);
                            emit("foreground.changed", new JSONObject()
                                    .put("foreground", current)
                                    .put("previous", previous));
                        }
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable failure) {
                    Logx.w("foreground poll failed: " + failure);
                }
            }
        }, "posedmcp-foreground-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private static String queryForeground() {
        try {
            Object service = Reflect.callStatic("android.app.ActivityTaskManager", "getService",
                    new Class<?>[0]);
            if (service == null) {
                return null;
            }
            Object tasks = getTasks(service);
            if (!(tasks instanceof List)) {
                return null;
            }
            List<?> list = (List<?>) tasks;
            for (Object task : list) {
                String description = describeTask(task);
                if (description != null && !description.isEmpty()) {
                    return description;
                }
            }
        } catch (Throwable t) {
            Logx.w("queryForeground failed: " + t);
        }
        return null;
    }

    /** {@code getTasks} gained parameters over several releases; try each shape. */
    private static Object getTasks(Object service) {
        Object[][] attempts = {
                {1, false, false, 0},
                {1, false, false},
                {1, false},
                {1},
        };
        for (Object[] args : attempts) {
            Class<?>[] types;
            switch (args.length) {
                case 4:
                    types = new Class<?>[]{int.class, boolean.class, boolean.class, int.class};
                    break;
                case 3:
                    types = new Class<?>[]{int.class, boolean.class, boolean.class};
                    break;
                case 2:
                    types = new Class<?>[]{int.class, boolean.class};
                    break;
                default:
                    types = new Class<?>[]{int.class};
            }
            Object result = Reflect.call(service, "getTasks", types, args);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static String describeTask(Object task) {
        for (String field : new String[]{"topActivity", "baseActivity", "origActivity"}) {
            Object value = field(task, field);
            if (value instanceof ComponentName) {
                ComponentName component = (ComponentName) value;
                return component.getPackageName() + "/" + component.getClassName();
            }
        }
        Object packageName = field(task, "packageName");
        return packageName == null ? null : String.valueOf(packageName);
    }

    private static Object field(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getField(name);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // Screen state
    // =====================================================================

    private static void registerScreenReceiver() {
        Context ctx = systemContext;
        if (ctx == null) {
            Logx.w("no system context; screen events will not be reported");
            return;
        }        screenOn = isInteractive(ctx);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (action == null) {
                    return;
                }
                switch (action) {
                    case Intent.ACTION_SCREEN_ON:
                        screenOn = true;
                        emitQuietly("screen.on", null);
                        break;
                    case Intent.ACTION_SCREEN_OFF:
                        screenOn = false;
                        emitQuietly("screen.off", null);
                        break;
                    case Intent.ACTION_USER_PRESENT:
                        emitQuietly("user.present", null);
                        break;
                    default:
                        break;
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            ctx.registerReceiver(receiver, filter, null, new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            Logx.w("could not register screen receiver: " + t);
        }
    }

    private static void emitQuietly(String type, JSONObject data) {
        try {
            emit(type, data == null ? new JSONObject().put("screenOn", screenOn) : data);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isInteractive(Context ctx) {
        Object result = Reflect.call(ctx.getSystemService(Context.POWER_SERVICE), "isInteractive");
        return result instanceof Boolean ? (Boolean) result : true;
    }

    // =====================================================================
    // System context
    // =====================================================================

    /** The Context system_server itself uses; needed to register receivers. */
    static Context systemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                Object app = Reflect.callStatic("android.app.AppGlobals", "getInitialApplication",
                        new Class<?>[0]);
                return app instanceof Context ? (Context) app : null;
            }
            for (String method : new String[]{"getSystemContext", "getSystemUiContext"}) {
                Object ctx = Reflect.call(thread, method);
                if (ctx instanceof Context) {
                    return (Context) ctx;
                }
            }
        } catch (Throwable t) {
            Logx.w("system context lookup failed: " + t);
        }
        return null;
    }
}
