package dev.posedmcp.xposed;

import android.content.pm.PackageManager;
import dev.posedmcp.Logx;
import dev.posedmcp.plugin.HookApi;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONObject;

/** Filters PM results at their concrete implementations, retaining Context and permissions. */
final class PackageConcealment {
    private static final List<HookApi.Unhook> HANDLES = new ArrayList<>();
    private static final Set<Method> MEMBERS = new HashSet<>();
    private static final Set<Class<?>> SURFACES = new HashSet<>();
    private static final Set<Method> STDOUT_HOOKS = new HashSet<>();
    private static final Map<Process, OutputView> OUTPUTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> LISTS = Set.of("getInstalledPackages", "getInstalledApplications",
            "getPackagesHoldingPermissions", "getPersistentApplications", "getPreferredPackages",
            "getPackagesForUid", "getAllPackages", "queryIntentActivities", "queryIntentActivityOptions",
            "queryBroadcastReceivers", "queryIntentServices", "queryIntentContentProviders",
            "queryContentProviders", "queryInstrumentation");
    private static final Set<String> LOOKUPS = Set.of("getPackageInfo", "getPackageInfoVersioned",
            "getApplicationInfo", "getPackageUid", "getPackageUidAsUser", "getPackageGids",
            "getPackageGidsAsUser", "getActivityInfo", "getReceiverInfo", "getServiceInfo",
            "getProviderInfo", "getInstrumentationInfo", "getInstallSourceInfo",
            "getInstallerPackageName", "getLaunchIntentForPackage", "getLeanbackLaunchIntentForPackage",
            "isPackageAvailable");
    private static final Set<String> RESOLVES = Set.of("resolveActivity", "resolveService", "resolveContentProvider");
    private static volatile boolean enabled;
    private static volatile String target = "";
    private static boolean installed;
    private static int failed;

    static synchronized void configure(ClassLoader loader, boolean active, String packageName) {
        target = packageName; enabled = active;
        if (!active || installed) return;
        installed = true;
        enabled = false;
        try {
            HookApi api = Framework.hookApi(loader);
            for (String name : new String[]{"android.app.ApplicationPackageManager",
                    "android.content.pm.IPackageManager$Stub$Proxy"}) {
                try { surface(api, Class.forName(name, false, loader)); }
                catch (Throwable t) { failure(name, t); }
            }
            try {
                Method getter = Class.forName("android.app.ContextImpl", false, loader)
                        .getDeclaredMethod("getPackageManager");
                keep(api.hook(getter, new HookApi.Callback() {
                    @Override public void after(HookApi.HookParam p) {
                        // Vendor Context implementations may return a specialized PM.
                        // Keep the real object and register only its declared overrides.
                        if (p.throwable() == null && p.result() != null) surface(api, p.result().getClass());
                    }
                }), getter.toString());
            } catch (Throwable t) { failure("ContextImpl#getPackageManager", t); }
            keep(api.hook(ProcessBuilder.class.getDeclaredMethod("start"), new HookApi.Callback() {
                @Override public void after(HookApi.HookParam p) {
                    if (!enabled || p.throwable() != null || !(p.result() instanceof Process)) return;
                    ProcessBuilder builder = (ProcessBuilder) p.thisObject();
                    if (builder.redirectOutput() != ProcessBuilder.Redirect.PIPE || builder.redirectErrorStream()) return;
                    boolean packages = PackageListFilter.command(builder.command());
                    MountViews.Format mount = !packages && ExtendedConcealment.isEnabled()
                            ? MountViews.command(builder.command()) : null;
                    if ((!packages && mount == null) || !ScopeConcealment.externalCaller()) return;
                    Process process = (Process) p.result();
                    String scopedTarget = target;
                    OutputView stdout = mount != null ? new OutputView(
                            () -> enabled && ExtendedConcealment.isEnabled(),
                            line -> MountViews.hidden(line, mount))
                            : new OutputView(() -> enabled, line -> PackageListFilter.hiddenLine(line, scopedTarget));
                    attachStdout(api, process, stdout);
                }
            }), "ProcessBuilder#start");
        } catch (Throwable t) { failure("package policy", t); }
        finally { enabled = active; }
        Logx.i("package visibility installed (" + HANDLES.size() + " hooks, " + failed + " failed)");
    }

    private static synchronized void surface(HookApi api, Class<?> type) {
        if (!SURFACES.add(type)) return;
        for (Method method : type.getDeclaredMethods()) {
            if (!LISTS.contains(method.getName()) && !LOOKUPS.contains(method.getName())
                    && !RESOLVES.contains(method.getName()) && !method.getName().equals("getNameForUid")) continue;
            if (!MEMBERS.add(method)) continue;
            try { keep(api.hook(method, callback(method)), method.toString()); }
            catch (Throwable t) { failure(method.toString(), t); }
        }
    }

    static HookApi.Callback callback(Method method) {
        String name = method.getName();
        boolean proxy = method.getDeclaringClass().getName().contains("IPackageManager$");
        return new HookApi.Callback() {
            @Override public void before(HookApi.HookParam p) {
                if (!enabled || !LOOKUPS.contains(name) || p.args().length == 0
                        || !ConcealmentPackages.hidden(ConcealmentPackages.packageOf(p.args()[0]), target)
                        || !ScopeConcealment.externalCaller()) return;
                if (method.getReturnType() == boolean.class) p.setResult(false);
                else if (name.contains("LaunchIntent")) p.setResult(null);
                else if (proxy) p.setResult(method.getReturnType() == int.class ? -1 : null);
                else if (name.equals("getInstallerPackageName")) {
                    p.setThrowable(new IllegalArgumentException("Unknown package"));
                } else p.setThrowable(new PackageManager.NameNotFoundException(
                        String.valueOf(ConcealmentPackages.packageOf(p.args()[0]))));
            }

            @Override public void after(HookApi.HookParam p) throws Exception {
                if (!enabled || p.throwable() != null || p.result() == null
                        || !ScopeConcealment.externalCaller()) return;
                if (LISTS.contains(name)) p.setResult(filtered(p.result(), target));
                else if ((RESOLVES.contains(name) || name.equals("getNameForUid"))
                        && ConcealmentPackages.hidden(ConcealmentPackages.packageOf(p.result()), target)) {
                    p.setResult(null);
                }
            }
        };
    }

    static Object filtered(Object original, String target) throws Exception {
        if (original instanceof String[]) {
            String[] array = (String[]) original;
            String[] out = Arrays.stream(array).filter(s -> !ConcealmentPackages.hidden(s, target))
                    .toArray(String[]::new);
            return out.length == array.length ? original : out.length == 0 ? null : out;
        }
        if (original instanceof List<?>) return filteredList((List<?>) original, target);
        // Hidden Binder APIs return ParceledListSlice rather than List. Never
        // mutate the cached original slice or the objects it contains.
        if (original.getClass().getName().equals("android.content.pm.ParceledListSlice")) {
            List<?> values = (List<?>) original.getClass().getMethod("getList").invoke(original);
            List<?> out = filteredList(values, target);
            if (out == values) return original;
            return original.getClass().getConstructor(List.class).newInstance(out);
        }
        return original;
    }

    private static List<?> filteredList(List<?> original, String target) {
        List<Object> out = null;
        for (int i = 0; i < original.size(); i++) {
            Object entry = original.get(i);
            if (ConcealmentPackages.hidden(ConcealmentPackages.packageOf(entry), target)) {
                if (out == null) out = new ArrayList<>(original.subList(0, i));
            } else if (out != null) out.add(entry);
        }
        return out == null ? original : out;
    }

    /** Keep the actual Process and all of its platform-specific lifecycle methods. */
    private static synchronized void attachStdout(HookApi api, Process process, OutputView view) {
        try {
            Method getter = process.getClass().getMethod("getInputStream");
            if (!STDOUT_HOOKS.contains(getter)) {
                HookApi.Unhook hook = api.hook(getter, new HookApi.Callback() {
                    @Override public void after(HookApi.HookParam p) {
                        if (p.throwable() != null || !(p.result() instanceof InputStream)) return;
                        OutputView policy = OUTPUTS.get(p.thisObject());
                        if (policy != null) p.setResult(policy.stream((InputStream) p.result()));
                    }
                });
                if (hook == null || !hook.isActive()) throw new IllegalStateException("hook declined");
                HANDLES.add(hook);
                STDOUT_HOOKS.add(getter);
            }
            OUTPUTS.put(process, view);
        } catch (Throwable t) { failure("process stdout", t); }
    }

    private static final class OutputView {
        final BooleanSupplier active;
        final Predicate<String> hidden;
        private InputStream view;
        OutputView(BooleanSupplier active, Predicate<String> hidden) {
            this.active = active;
            this.hidden = hidden;
        }
        synchronized InputStream stream(InputStream original) {
            if (view == null) {
                if (!active.getAsBoolean()) return original;
                view = new PackageListFilter(original, active, hidden);
            }
            return view;
        }
    }

    private static void keep(HookApi.Unhook handle, String subject) {
        if (handle != null && handle.isActive()) HANDLES.add(handle);
        else failure(subject, new IllegalStateException("hook backend declined installation"));
    }
    private static void failure(String subject, Throwable t) {
        failed++; Logx.w("package visibility: " + subject + ": " + t);
    }
    static synchronized JSONObject status() {
        JSONObject out = new JSONObject();
        try { out.put("enabled", enabled).put("installed", installed).put("registered", HANDLES.size())
                .put("failed", failed).put("hiddenPackages", ConcealmentPackages.forTarget(target).length); }
        catch (Exception ignored) {}
        return out;
    }
    private PackageConcealment() {}
}
