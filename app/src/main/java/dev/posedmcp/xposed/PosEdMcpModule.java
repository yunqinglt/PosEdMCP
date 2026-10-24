package dev.posedmcp.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.posedmcp.Logx;

/**
 * Module entry point, named in {@code assets/xposed_init}.
 *
 * <p>Scope decides what happens:
 * <ul>
 *   <li><b>System Framework</b> - {@link SystemHooks} runs inside system_server
 *       and provides screen capture, input injection, foreground tracking and
 *       screen-state events.</li>
 *   <li><b>any other scoped app</b> - {@link AppHost} runs inside that app's
 *       process and becomes a target for injected plugins.</li>
 * </ul>
 *
 * <p>Nothing throws out of the callbacks. A module that crashes the process it
 * was loaded into would be far more damaging than one that quietly does
 * nothing, so every failure is logged and swallowed.
 */
public class PosEdMcpModule implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    @Override
    public void initZygote(StartupParam startupParam) {
        if (startupParam != null) NativeRuntime.setModuleApk(startupParam.modulePath);
        // Nothing is done this early: API 101+ forbids injecting into zygote
        // itself, and everything here is scoped per-process anyway.
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        try {
            // Recorded before anything else, because it decides which HookApi
            // every later hook is built on - and this is a framework older than
            // the modern one, or the module would not have been started here.
            Framework.adoptClassic("Xposed API " + XposedBridge.getXposedVersion());

            String packageName = lpparam.packageName;
            String processName = lpparam.processName;

            if (Framework.isSystemServer(packageName, processName)) {
                log("installing system hooks in " + processName + " (for " + packageName + ")");
                SystemHooks.install(lpparam.classLoader);
                return;
            }

            log("installing app host in " + packageName);
            AppHost.install(packageName, lpparam.classLoader, lpparam.appInfo);
        } catch (Throwable t) {
            Logx.e("handleLoadPackage failed for " + safeName(lpparam), t);
        }
    }

    private static String safeName(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            return lpparam.packageName + "/" + lpparam.processName;
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    private static void log(String message) {
        try {
            XposedBridge.log("PosEdMCP: " + message);
        } catch (Throwable ignored) {
        }
        Logx.i(message);
    }
}
