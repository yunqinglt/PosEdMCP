package dev.posedmcp.xposed;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Remembers original Java methods, without changing real JNI method modifiers. */
final class HookVisibility {
    private static final Set<Method> JAVA_METHODS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    static void note(Method method) {
        if (!Modifier.isNative(method.getModifiers())) JAVA_METHODS.add(method);
    }
    static boolean maskNative(Method method) { return JAVA_METHODS.contains(method); }
    private HookVisibility() {}
}
