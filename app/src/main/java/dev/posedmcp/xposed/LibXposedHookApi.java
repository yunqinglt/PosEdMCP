package dev.posedmcp.xposed;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.Chain;
import io.github.libxposed.api.XposedInterface.HookHandle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.posedmcp.Logx;
import dev.posedmcp.plugin.HookApi;

/**
 * {@link HookApi} on the API Vector is built on, rather than on the bridge that
 * emulates the 2012 one.
 *
 * <p>Only two things here are genuinely different from {@link XposedHookApi},
 * and both are worth knowing when reading it.
 *
 * <p><b>There is no {@code setResult}.</b> The modern API is an interceptor
 * chain: a hooker either calls {@code chain.proceed()} or answers for the method
 * by returning a value without calling it. Classic Xposed expressed the same two
 * outcomes as "call setResult in before" and "call it in after", so this class
 * buffers the value and decides afterwards whether the original runs at all.
 *
 * <p><b>Arguments travel with the call, not in a shared array.</b> Classic hooks
 * mutate {@code param.args} in place; here the array is handed to
 * {@code proceed(args)}. The adapter therefore keeps one array, gives it to the
 * callback to mutate, and passes it along — which is the same contract seen from
 * the other side.
 *
 * <p>Everything the rest of the module uses — {@code HookRegistry}, the
 * {@code app.hook} table in Lua, the plugin {@code HookApi} — is written against
 * the interface, so none of it notices which of these two is underneath.
 */
public final class LibXposedHookApi implements HookApi {

    private static final Unhook NOOP = () -> {
    };

    private final XposedInterface framework;
    private final ClassLoader loader;

    public LibXposedHookApi(XposedInterface framework, ClassLoader loader) {
        this.framework = framework;
        this.loader = loader;
    }

    // ---- installation ------------------------------------------------------

    @Override
    public Unhook hookAllMethods(String className, String methodName, Callback callback) {
        Class<?> clazz = findClass(className);
        if (clazz == null || methodName == null || methodName.isEmpty()) {
            return NOOP;
        }
        // The classic API hooks a name across the whole hierarchy, so this does
        // too - and deduplicates, because the same Executable can be reached
        // twice through declared/bridge methods and hooking it twice would
        // double every callback.
        Set<Executable> targets = new LinkedHashSet<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    targets.add(method);
                }
            }
        }
        return installAll(targets, callback);
    }

    @Override
    public Unhook hookMethod(String className, String methodName, Class<?>[] parameterTypes,
            Callback callback) {
        Class<?> clazz = findClass(className);
        if (clazz == null || methodName == null) {
            return NOOP;
        }
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Method method = findMethod(c, methodName,
                    parameterTypes == null ? new Class<?>[0] : parameterTypes);
            if (method != null) {
                return install(method, callback);
            }
        }
        return NOOP;
    }

    @Override
    public Unhook hookAllConstructors(String className, Callback callback) {
        Class<?> clazz = findClass(className);
        if (clazz == null) {
            return NOOP;
        }
        Set<Executable> targets = new LinkedHashSet<>();
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            targets.add(constructor);
        }
        return installAll(targets, callback);
    }

    @Override
    public Unhook hook(Member member, Callback callback) {
        return member instanceof Executable ? install((Executable) member, callback) : NOOP;
    }

    @Override
    public Unhook hook(Constructor<?> constructor, Callback callback) {
        return constructor == null ? NOOP : install(constructor, callback);
    }

    @Override
    public Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        if (clazz == null || name == null) {
            return null;
        }
        try {
            Method found = clazz.getDeclaredMethod(name,
                    parameterTypes == null ? new Class<?>[0] : parameterTypes);
            found.setAccessible(true);
            return found;
        } catch (Throwable t) {
            return null;
        }
    }

    private Unhook installAll(Set<Executable> targets, Callback callback) {
        List<Unhook> handles = new ArrayList<>(targets.size());
        for (Executable target : targets) {
            Unhook handle = install(target, callback);
            if (handle != NOOP) {
                handles.add(handle);
            }
        }
        if (handles.isEmpty()) {
            return NOOP;
        }
        return () -> {
            for (Unhook handle : handles) {
                try {
                    handle.unhook();
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private Unhook install(Executable target, Callback callback) {
        try {
            HookHandle handle = framework.hook(target)
                    // The default, and the one this module wants: a hook that
                    // throws must not take the application's call down with it.
                    .intercept(chain -> intercept(chain, callback));
            return handle::unhook;
        } catch (Throwable t) {
            // Reported rather than swallowed: a hook that silently failed to
            // install looks exactly like a hook that matches nothing.
            Logx.w("libxposed could not hook " + target + ": " + t);
            return NOOP;
        }
    }

    private Object intercept(Chain chain, Callback callback) throws Throwable {
        Params params = new Params(chain);

        callback.before(params);
        if (params.answered) {
            // setResult() from before() means "do not run the original", which is
            // the one thing the chain model expresses by simply not proceeding.
            //
            // after() still has to run, and that is not a formality: the record of
            // a call is closed there. Returning straight from here left a hook
            // that answers for a method with no trace in hook_records at all -
            // measured on a HyperOS one-tap clean, where the countermeasure
            // refused a force-stop and the log said so while the record list held
            // only the calls that had been let through. A blocked call looking
            // exactly like one that never arrived is the answer this module exists
            // not to give. The classic backend calls both halves, so this only
            // brings the two level.
            Object chosen = params.result;
            callback.after(params);
            return params.answered ? params.result : chosen;
        }

        try {
            params.result = chain.proceed(params.args);
        } catch (Throwable thrown) {
            params.thrown = thrown;
            params.answered = false;
            callback.after(params);
            if (params.answered) {
                // after() chose a value over the throw, as classic Xposed allows.
                return params.result;
            }
            throw thrown;
        }

        params.answered = false;
        callback.after(params);
        return params.result;
    }

    private Class<?> findClass(String className) {
        try {
            return Class.forName(className, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The classic {@code HookParam}, rebuilt on top of a chain.
     *
     * <p>Small on purpose: it exists to make the modern call shape look like the
     * old one for long enough that one body of hook code can serve both, and
     * anything beyond that belongs in the module rather than here.
     */
    private static final class Params implements HookParam {
        private final Chain chain;
        /** The live array the callback may mutate; handed to proceed() as-is. */
        private final Object[] args;

        Object result;
        Throwable thrown;
        /** Set when the callback chose an outcome instead of letting the call run. */
        boolean answered;

        Params(Chain chain) {
            this.chain = chain;
            List<Object> incoming = chain.getArgs();
            this.args = incoming == null ? new Object[0] : incoming.toArray();
        }

        @Override
        public Object thisObject() {
            return chain.getThisObject();
        }

        @Override
        public Object[] args() {
            return args;
        }

        @Override
        public void setResult(Object value) {
            result = value;
            answered = true;
        }

        @Override
        public Object result() {
            return result;
        }

        @Override
        public Throwable throwable() {
            return thrown;
        }

        @Override
        public void setObjectField(String name, Object value) {
            Field field = field(name);
            if (field == null) {
                return;
            }
            try {
                field.set(receiver(), value);
            } catch (Throwable t) {
                Logx.w("could not set " + name + ": " + t);
            }
        }

        @Override
        public Object getObjectField(String name) {
            Field field = field(name);
            if (field == null) {
                return null;
            }
            try {
                return field.get(receiver());
            } catch (Throwable t) {
                return null;
            }
        }

        /** The instance, or the declaring class when the member is static. */
        private Object receiver() {
            Object instance = chain.getThisObject();
            return instance != null ? instance : chain.getExecutable().getDeclaringClass();
        }

        private Field field(String name) {
            Object target = receiver();
            for (Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
                    c != null; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (Throwable ignored) {
                    // Keep walking; the field may be declared further up.
                }
            }
            Logx.w("no field '" + name + "' on " + target);
            return null;
        }
    }
}
