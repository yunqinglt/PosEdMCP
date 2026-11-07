package dev.posedmcp.xposed;

import dev.posedmcp.Logx;
import dev.posedmcp.plugin.HookApi;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Stream;
import org.json.JSONObject;

/** Opt-in Java read views. Never edits cached stacks, dex fields or files on disk. */
final class ExtendedConcealment {
    private static final Map<Object, MountViews.Format> PROC_FILES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> READING = ThreadLocal.withInitial(() -> false);
    private static volatile boolean enabled;
    private static boolean installed;
    private static int registered, failed, unavailable;

    static synchronized void configure(ClassLoader loader, boolean active) {
        enabled = active;
        if (!active || installed) return;
        installed = true;
        enabled = false;
        try {
            HookApi api = Framework.hookApi(loader);
            hook(api, System.class, "getenv", new Class<?>[]{String.class}, EnvironmentViews.ENV);
            hook(api, System.class, "getenv", new Class<?>[0], EnvironmentViews.ENV);
            hook(api, System.class, "getProperty", new Class<?>[]{String.class}, EnvironmentViews.PROPERTY);
            hook(api, System.class, "getProperty", new Class<?>[]{String.class, String.class}, EnvironmentViews.PROPERTY);
            HookApi.Callback stack = new HookApi.Callback() {
                @Override public void after(HookApi.HookParam p) {
                    if (enabled && p.throwable() == null && p.result() instanceof StackTraceElement[]
                            && ScopeConcealment.externalCaller()) p.setResult(trace((StackTraceElement[]) p.result()));
                }
            };
            hook(api, Throwable.class, "getStackTrace", new Class<?>[0], stack);
            hook(api, Thread.class, "getStackTrace", new Class<?>[0], stack);
            hook(api, Thread.class, "getAllStackTraces", new Class<?>[0], new HookApi.Callback() {
                @Override public void after(HookApi.HookParam p) {
                    if (enabled && p.throwable() == null && p.result() instanceof Map<?, ?>
                            && ScopeConcealment.externalCaller()) p.setResult(traces((Map<?, ?>) p.result()));
                }
            });
            for (Method method : Class.class.getDeclaredMethods()) {
                Class<?>[] args = method.getParameterTypes();
                // The one-argument overload discovers its caller's loader. Hook trampolines
                // change that caller and can break ordinary app class resolution.
                if (!method.getName().equals("forName") || args.length != 3 || args[0] != String.class) continue;
                keep(api, method, new HookApi.Callback() {
                    @Override public void before(HookApi.HookParam p) {
                        if (enabled && lookup(p.args()[0]) && ScopeConcealment.externalCaller()) {
                            p.setThrowable(new ClassNotFoundException((String) p.args()[0]));
                        }
                    }
                });
            }
            hook(api, ClassLoader.class, "findLoadedClass", new Class<?>[]{String.class}, new HookApi.Callback() {
                @Override public void before(HookApi.HookParam p) {
                    if (enabled && lookup(p.args()[0]) && ScopeConcealment.externalCaller()) p.setResult(null);
                }
            });
            for (String name : new String[]{"getResource", "getResources", "getResourceAsStream"}) {
                hook(api, ClassLoader.class, name, new Class<?>[]{String.class}, new HookApi.Callback() {
                    @Override public void before(HookApi.HookParam p) {
                        if (enabled && resource(p.args()[0]) && ScopeConcealment.externalCaller()) {
                            p.setResult(name.equals("getResources") ? Collections.emptyEnumeration() : null);
                        }
                    }
                });
            }
            HookApi.Callback proc = new HookApi.Callback() {
                @Override public void after(HookApi.HookParam p) {
                    if (!enabled || p.throwable() != null || p.args().length == 0) return;
                    MountViews.Format view = MountViews.proc(p.args()[0]);
                    if (view == null || !ScopeConcealment.externalCaller()) return;
                    Object original = p.result();
                    if (original instanceof byte[]) {
                        byte[] bytes = (byte[]) original;
                        // Latin-1 is a reversible byte mapping; unrelated/non-UTF8 bytes survive.
                        String text = new String(bytes, StandardCharsets.ISO_8859_1), filtered = text(text, view);
                        if (filtered != text) p.setResult(filtered.getBytes(StandardCharsets.ISO_8859_1));
                    } else if (original instanceof String) {
                        p.setResult(text((String) original, view));
                    } else if (original instanceof List<?>) {
                        p.setResult(lines((List<?>) original, view));
                    } else if (original instanceof Stream<?>) {
                        // filter preserves the original stream's close handlers and laziness.
                        p.setResult(((Stream<?>) original).filter(line -> !enabled
                                || !(line instanceof String) || !MountViews.hidden((String) line, view)));
                    }
                }
            };
            hook(api, Files.class, "readAllBytes", new Class<?>[]{java.nio.file.Path.class}, proc);
            for (String name : new String[]{"readString", "readAllLines", "lines"}) {
                hook(api, Files.class, name, new Class<?>[]{java.nio.file.Path.class}, proc);
                hook(api, Files.class, name,
                        new Class<?>[]{java.nio.file.Path.class, java.nio.charset.Charset.class}, proc);
            }
            try {
                keep(api.hookAllConstructors(RandomAccessFile.class.getName(), new HookApi.Callback() {
                    @Override public void after(HookApi.HookParam p) {
                        if (!enabled || p.throwable() != null || p.args().length == 0) return;
                        MountViews.Format view = MountViews.proc(p.args()[0]);
                        if (view != null) PROC_FILES.put(p.thisObject(), view);
                    }
                }));
            } catch (Throwable t) { failure("RandomAccessFile constructors", t); }
            hook(api, RandomAccessFile.class, "readLine", new Class<?>[0], new HookApi.Callback() {
                @Override public void after(HookApi.HookParam p) {
                    if (!enabled || READING.get() || p.throwable() != null) return;
                    MountViews.Format view = PROC_FILES.get(p.thisObject());
                    if (!MountViews.hidden((String) p.result(), view)
                            || !ScopeConcealment.externalCaller()) return;
                    READING.set(true);
                    try {
                        String line;
                        do { line = ((RandomAccessFile) p.thisObject()).readLine(); }
                        while (MountViews.hidden(line, view));
                        p.setResult(line);
                    } catch (IOException error) { p.setThrowable(error); }
                    finally { READING.remove(); }
                }
            });
        } finally { enabled = active; }
    }

    static boolean lookup(Object value) {
        if (!(value instanceof String)) return false;
        String name = (String) value;
        int at = 0;
        while (at < name.length() && name.charAt(at) == '[') at++;
        if (at > 0) {
            if (at >= name.length() || name.charAt(at) != 'L' || !name.endsWith(";")) return false;
            name = name.substring(at + 1, name.length() - 1);
        }
        return ScopeConcealment.hiddenClass(name);
    }

    static boolean resource(Object value) {
        if (!(value instanceof String)) return false;
        String name = (String) value;
        if (!name.endsWith(".class")) return false;
        if (name.startsWith("/")) name = name.substring(1);
        return ScopeConcealment.hiddenClass(name.substring(0, name.length() - 6).replace('/', '.'));
    }

    static StackTraceElement[] trace(StackTraceElement[] original) {
        return ScopeConcealment.internalView(() -> {
            List<StackTraceElement> kept = new ArrayList<>(original.length);
            for (StackTraceElement frame : original) {
                if (frame == null || !ScopeConcealment.hiddenStackClass(frame.getClassName())) kept.add(frame);
            }
            return kept.size() == original.length ? original : kept.toArray(new StackTraceElement[0]);
        });
    }

    static Map<?, ?> traces(Map<?, ?> original) {
        Map<Object, Object> copy = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<?, ?> entry : original.entrySet()) {
            Object value = entry.getValue(), filtered = value;
            if (value instanceof StackTraceElement[]) filtered = trace((StackTraceElement[]) value);
            copy.put(entry.getKey(), filtered);
            changed |= filtered != value;
        }
        return changed ? copy : original;
    }

    static boolean isEnabled() { return enabled; }

    static List<?> lines(List<?> original, MountViews.Format view) {
        for (Object line : original) if (!(line instanceof String)) return original;
        List<Object> copy = new ArrayList<>();
        for (Object line : original) if (!MountViews.hidden((String) line, view)) copy.add(line);
        return copy.size() == original.size() ? original : copy;
    }

    static String text(String original, MountViews.Format view) {
        StringBuilder copy = null;
        for (int start = 0; start < original.length();) {
            int newline = original.indexOf('\n', start), end = newline < 0 ? original.length() : newline;
            int next = newline < 0 ? end : end + 1;
            String line = original.substring(start, end > start && original.charAt(end - 1) == '\r' ? end - 1 : end);
            if (MountViews.hidden(line, view)) {
                if (copy == null) copy = new StringBuilder(original.length()).append(original, 0, start);
            } else if (copy != null) copy.append(original, start, next);
            start = next;
        }
        return copy == null ? original : copy.toString();
    }

    private static void hook(HookApi api, Class<?> type, String name, Class<?>[] args, HookApi.Callback callback) {
        try { keep(api, type.getDeclaredMethod(name, args), callback); }
        catch (NoSuchMethodException absent) { unavailable++; }
        catch (Throwable t) { failure(type.getName() + "#" + name, t); }
    }
    private static void keep(HookApi api, Method method, HookApi.Callback callback) {
        try { keep(api.hook(method, callback)); }
        catch (Throwable t) { failure(method.toString(), t); }
    }
    private static void keep(HookApi.Unhook handle) {
        if (handle == null || !handle.isActive()) throw new IllegalStateException("hook declined");
        registered++;
    }
    private static void failure(String name, Throwable error) { failed++; Logx.w("extended concealment: " + name + ": " + error); }
    static synchronized JSONObject status() {
        JSONObject out = new JSONObject();
        try { out.put("enabled", enabled).put("installed", installed).put("registered", registered)
                .put("failed", failed).put("unavailable", unavailable); } catch (Exception ignored) {}
        return out;
    }
    private ExtendedConcealment() {}
}
