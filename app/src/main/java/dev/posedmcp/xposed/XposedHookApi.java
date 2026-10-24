package dev.posedmcp.xposed;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import dev.posedmcp.plugin.HookApi;
import dev.posedmcp.plugin.HookApi.HookParam;
import dev.posedmcp.plugin.HookApi.Unhook;

/**
 * Bridges the plugin-facing {@link HookApi} onto the classic Xposed API.
 *
 * <p>Keeping plugins behind this interface means a plugin never has to link
 * against {@code de.robv.android.xposed}, so the same DEX works on any
 * framework that provides the hook primitives, and a broken plugin cannot throw
 * an Xposed type into a caller that never asked for one.
 */
public final class XposedHookApi implements HookApi {

    // ART verification can re-enter a ClassLoader/Method hook. Force adapter
    // verification before installing one, to avoid waiting on its own verifier.
    private static final HookParamAdapter PARAM_ADAPTER_WARMUP = new HookParamAdapter(null);

    private final ClassLoader classLoader;

    public XposedHookApi(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public Unhook hookAllMethods(String className, String methodName, Callback callback) {
        Class<?> clazz = findClass(className);
        if (clazz == null) {
            return NoopUnhook.INSTANCE;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) HookVisibility.note(method);
        }
        Set<XC_MethodHook.Unhook> unhooks =
                XposedBridge.hookAllMethods(clazz, methodName, adapter(callback));
        return wrap(unhooks);
    }

    @Override
    public Unhook hookMethod(String className, String methodName, Class<?>[] parameterTypes,
            Callback callback) {
        Class<?> clazz = findClass(className);
        if (clazz == null) {
            return NoopUnhook.INSTANCE;
        }
        Method method = XposedHelpers.findMethodExactIfExists(clazz, methodName, (Object[]) parameterTypes);
        if (method == null) {
            return NoopUnhook.INSTANCE;
        }
        HookVisibility.note(method);
        return wrap(XposedBridge.hookMethod(method, adapter(callback)));
    }

    @Override
    public Unhook hookAllConstructors(String className, Callback callback) {
        Class<?> clazz = findClass(className);
        if (clazz == null) {
            return NoopUnhook.INSTANCE;
        }
        return wrap(XposedBridge.hookAllConstructors(clazz, adapter(callback)));
    }

    @Override
    public Unhook hook(Member member, Callback callback) {
        if (member == null) {
            return NoopUnhook.INSTANCE;
        }
        if (member instanceof Method) HookVisibility.note((Method) member);
        return wrap(XposedBridge.hookMethod(member, adapter(callback)));
    }

    @Override
    public Unhook hook(Constructor<?> constructor, Callback callback) {
        return hook((Member) constructor, callback);
    }

    @Override
    public Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        if (clazz == null) {
            return null;
        }
        Method method = XposedHelpers.findMethodExactIfExists(clazz, name, (Object[]) parameterTypes);
        return method;
    }

    private Class<?> findClass(String className) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    private static XC_MethodHook adapter(Callback callback) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                callback.before(new HookParamAdapter(param));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                callback.after(new HookParamAdapter(param));
            }
        };
    }

    private static Unhook wrap(XC_MethodHook.Unhook unhook) {
        if (unhook == null) {
            return NoopUnhook.INSTANCE;
        }
        return () -> {
            try {
                unhook.unhook();
            } catch (Throwable ignored) {
            }
        };
    }

    private static Unhook wrap(Set<XC_MethodHook.Unhook> unhooks) {
        if (unhooks == null || unhooks.isEmpty()) {
            return NoopUnhook.INSTANCE;
        }
        List<XC_MethodHook.Unhook> copy = new ArrayList<>(new LinkedHashSet<>(unhooks));
        return () -> {
            for (XC_MethodHook.Unhook unhook : copy) {
                try {
                    unhook.unhook();
                } catch (Throwable ignored) {
                }
            }
        };
    }

    /** Adapts the framework's param object to the plugin-facing one. */
    private static final class HookParamAdapter implements HookParam {
        private final XC_MethodHook.MethodHookParam delegate;

        HookParamAdapter(XC_MethodHook.MethodHookParam delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object thisObject() {
            return delegate.thisObject;
        }

        @Override
        public Object[] args() {
            return delegate.args;
        }

        @Override
        public void setResult(Object result) {
            delegate.setResult(result);
        }

        @Override public void setThrowable(Throwable throwable) {
            delegate.setThrowable(throwable);
        }

        @Override
        public Object result() {
            return delegate.getResult();
        }

        @Override
        public Throwable throwable() {
            return delegate.getThrowable();
        }

        @Override
        public void setObjectField(String name, Object value) {
            Object target = delegate.thisObject != null ? delegate.thisObject
                    : delegate.method.getDeclaringClass();
            XposedHelpers.setObjectField(target, name, value);
        }

        @Override
        public Object getObjectField(String name) {
            Object target = delegate.thisObject != null ? delegate.thisObject
                    : delegate.method.getDeclaringClass();
            return XposedHelpers.getObjectField(target, name);
        }
    }

    private enum NoopUnhook implements Unhook {
        INSTANCE;

        @Override public boolean isActive() { return false; }

        @Override
        public void unhook() {
        }
    }
}
