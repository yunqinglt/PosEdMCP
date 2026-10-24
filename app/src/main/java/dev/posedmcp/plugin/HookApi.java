package dev.posedmcp.plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

/**
 * Hook installation for plugins, so an injected plugin does not have to touch
 * the Xposed API directly and can be compiled against a single small interface.
 */
public interface HookApi {

    /** The call being intercepted. */
    interface HookParam {
        Object thisObject();

        Object[] args();

        /** Overrides the return value. Only meaningful from {@link Callback#before}. */
        void setResult(Object result);

        /** Chooses an exception instead of a result, in either callback phase. */
        default void setThrowable(Throwable throwable) {
            throw new UnsupportedOperationException("This hook backend cannot replace exceptions");
        }

        Object result();

        /** Sets a field on {@code thisObject}, or on the class for statics. */
        void setObjectField(String name, Object value);

        Object getObjectField(String name);

        /**
         * The exception the call is unwinding with, or {@code null}.
         *
         * <p>Only meaningful from {@link Callback#after}: knowing that a hooked
         * method threw, and with what, is often the whole point of observing it.
         */
        Throwable throwable();
    }

    interface Callback {
        /** Runs before the original method. */
        default void before(HookParam param) throws Throwable {
        }

        /** Runs after the original method, or after it threw. */
        default void after(HookParam param) throws Throwable {
        }    }

    /** The handle for an installed hook. */
    interface Unhook {
        void unhook();

        /** False for an unresolved or failed installation. */
        default boolean isActive() { return true; }
    }

    Unhook hookAllMethods(String className, String methodName, Callback callback);

    Unhook hookMethod(String className, String methodName, Class<?>[] parameterTypes,
            Callback callback);

    Unhook hookAllConstructors(String className, Callback callback);

    /** Hooks an already-resolved member; used when the class is only reachable reflectively. */
    Unhook hook(Member member, Callback callback);

    Unhook hook(Constructor<?> constructor, Callback callback);

    /** Convenience: resolves a method if present, returning null instead of throwing. */
    Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes);
}
