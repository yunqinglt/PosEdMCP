package dev.posedmcp.xposed;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterfaceWrapper;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Method;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

import dev.posedmcp.Logx;

/**
 * Module entry point for frameworks that speak the modern API, named in
 * {@code META-INF/xposed/java_init.list}.
 *
 * <p>The twin of {@link PosEdMcpModule}, and deliberately a thin one: it decides
 * nothing itself. It records the backend and hands the same class loader to the
 * same {@link SystemHooks} and {@link AppHost} the classic entry hands them, so
 * there is one implementation of what this module does and two ways of being
 * started.
 *
 * <p>Both package callbacks are implemented on purpose. They differ only in
 * which class loader they can offer — {@code onPackageLoaded} has the default
 * one, {@code onPackageReady} the one the app actually ended up with, after any
 * custom {@code AppComponentFactory} has had its say — and relying on just one
 * of them means doing nothing at all if the framework is older than we assumed.
 * {@link AppHost#install} is a one-shot per process, so whichever arrives first
 * wins and the second is a no-op.
 *
 * <p>Nothing throws out of these. A module that crashes the process it was
 * loaded into would be far more damaging than one that quietly does nothing.
 */
public class VectorModule extends XposedModule {

    /** Only {@code ModuleLoadedParam} carries this, so it is kept for the rest. */
    private volatile String processName = "";

    /**
     * The constructor an API-100 framework uses, and the reason this class can
     * be loaded by one at all.
     *
     * <p>Before 101 a module was handed the framework and the load parameters as
     * constructor arguments; from 101 on the framework constructs the module
     * with no arguments and calls {@code attachFramework()} and
     * {@code onModuleLoaded()} itself. Both spellings are here because both
     * frameworks are in circulation.
     *
     * <p>The older one does not check what we declare before reaching for this
     * constructor. Measured: LSPosed 1.10.2 is an API 100 framework, found this
     * class through {@code java_init.list}, died with
     * {@code NoSuchMethodException}, and did not fall back to the classic entry —
     * which left the module loaded nowhere at all, including on the device where
     * it had been working. Declaring {@code minApiVersion=101} did not stop it;
     * being constructible does.
     *
     * <p>Being built this way is also information: it means the framework
     * predates 101, and such a framework's mature, well-trodden API is the
     * classic one. So this route deliberately leaves {@link Framework} on its
     * classic default — the modern hook backend is for the frameworks whose
     * classic support is the thin part, which is exactly the ones that construct
     * modules the 101 way.
     */
    public VectorModule(XposedInterface base, ModuleLoadedParam param) {
        configureBootstrapReader(base);
        attachFrameworkAnyArity(base);
        processName = processNameOf(param);
        // Named as precisely as this framework can be named. Its own classes are
        // the only source for that, and asking is worth a reflective call: on
        // LSPosed this is what turns "classic xposed" into something a reader can
        // act on.
        String named = identifyFramework(base);
        Framework.adoptClassic(named.isEmpty() ? "a pre-101 framework" : named);
        Logx.i("loaded through java_init.list by " + (named.isEmpty() ? "a pre-101 framework" : named)
                + " (" + processName + ") - keeping the classic hook backend, which is its own");
    }

    /** The framework's own name and version, or empty when it has no way to say. */
    private static String identifyFramework(XposedInterface base) {
        String name = "";
        String version = "";
        try {
            name = base.getFrameworkName();
        } catch (Throwable ignored) {
            // A pre-101 framework may not have this at all.
        }
        try {
            version = base.getFrameworkVersion();
        } catch (Throwable ignored) {
        }
        return (name + " " + version).trim();
    }

    /**
     * Attaches the framework, whichever arity this framework's API has.
     *
     * <p>API 102 changed this method: it takes a {@code Runnable} for hot reload,
     * where 101 and everything before it took only the framework. This module
     * compiles against 102's classes, so the direct call is a
     * {@code NoSuchMethodError} on the framework before it — measured, on
     * LSPosed 1.10.2, whose bundled libxposed is API 100, and whose 102-era jar
     * this module is built against. Compiling against 101 instead only moves the
     * failure to the other device.
     *
     * <p>So the one call that differs between the two generations is made
     * reflectively, and everything else is ordinary code. The cost is a lookup
     * in a constructor that runs once per process.
     */
    private void attachFrameworkAnyArity(XposedInterface base) {
        Method found = null;
        for (Method method : XposedInterfaceWrapper.class.getMethods()) {
            if ("attachFramework".equals(method.getName())) {
                found = method;
                break;
            }
        }
        if (found == null) {
            Logx.w("this framework's API has no attachFramework at all");
            return;
        }
        try {
            if (found.getParameterCount() == 2) {
                // API 102's detach hook. This module has nothing to tear down
                // when it is swapped out, so a no-op is the honest implementation
                // rather than a stub.
                found.invoke(this, base, (Runnable) () -> {
                });
            } else {
                found.invoke(this, base);
            }
        } catch (Throwable t) {
            Logx.w("could not attach the framework (" + found + "): " + t);
        }
    }

    /** The API 101+ route: the framework attaches itself, then calls back. */
    public VectorModule() {
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        configureBootstrapReader(this);
        try { NativeRuntime.setModuleApk(getModuleApplicationInfo().sourceDir); }
        catch (Throwable ignored) { /* The bootstrap service can supply it later. */ }
        try {
            String framework = describeFramework();
            Framework.adoptLibXposed(
                    loader -> new LibXposedHookApi(VectorModule.this, loader), framework);
            processName = processNameOf(param);
            log(Log.INFO, "PosEdMCP", "loaded via libxposed in " + processName + " under " + framework);
        } catch (Throwable t) {
            Logx.e("onModuleLoaded failed", t);
        }
    }

    private void configureBootstrapReader(XposedInterface source) {
        dev.posedmcp.ipc.BridgeAuth.setFrameworkSnapshot(() -> {
            android.content.SharedPreferences preferences = source.getRemotePreferences(
                    dev.posedmcp.ipc.FrameworkPreferencesProvider.GROUP);
            return preferences == null ? null : preferences.getString(
                    dev.posedmcp.ipc.FrameworkPreferencesProvider.SNAPSHOT, null);
        });
    }

    /** Never throws: a framework that is missing one of these is still usable. */
    private String describeFramework() {
        String named = identifyFramework(this);
        int api = 0;
        try {
            api = getApiVersion();
        } catch (Throwable ignored) {
            // Older than the method.
        }
        if (api <= 0) {
            return named;
        }
        return named.isEmpty() ? "libxposed API " + api : named + ", libxposed API " + api;
    }

    private static String processNameOf(ModuleLoadedParam param) {
        try {
            return param == null ? "" : param.getProcessName();
        } catch (Throwable t) {
            // The pre-101 param may not have it; /proc answers the same question.
            return "";
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        try {
            log(Log.INFO, "PosEdMCP", "installing system hooks in system_server (libxposed)");
            SystemHooks.install(param.getClassLoader());
        } catch (Throwable t) {
            Logx.e("onSystemServerStarting failed", t);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        route(param.getPackageName(), param.getDefaultClassLoader(), param.getApplicationInfo());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        route(param.getPackageName(), param.getClassLoader(), param.getApplicationInfo());
    }

    private void route(String packageName, ClassLoader classLoader,
            android.content.pm.ApplicationInfo applicationInfo) {
        try {
            if (packageName == null) {
                return;
            }
            if (Framework.isSystemServer(packageName, processName)) {
                // In system server the first phase arrives as
                // onSystemServerStarting; this catches the later package loads.
                SystemHooks.install(classLoader);
                return;
            }
            AppHost.install(packageName, classLoader, applicationInfo);
        } catch (Throwable t) {
            Logx.e("could not install into " + packageName, t);
        }
    }
}
