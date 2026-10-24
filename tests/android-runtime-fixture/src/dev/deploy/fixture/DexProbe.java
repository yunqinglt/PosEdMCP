package dev.deploy.fixture;

import android.content.Context;
import dalvik.system.DexClassLoader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Compares the returned view with the real cached array through an unhooked raw control. */
final class DexProbe {
    static JSONObject collect(Context context, String moduleApk) throws Exception {
        JSONObject out = new JSONObject();
        if (moduleApk == null) return out.put("skipped", "module_apk extra absent");
        DexClassLoader loader = new DexClassLoader(moduleApk + ":" + context.getApplicationInfo().sourceDir,
                context.getCodeCacheDir().getAbsolutePath(), null, context.getClassLoader());
        Field pathListField = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
        pathListField.setAccessible(true);
        Object pathList = pathListField.get(loader);
        Field elements = pathList.getClass().getDeclaredField("dexElements");
        elements.setAccessible(true);
        Object view = elements.get(pathList);
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        long offset = (Long) unsafeType.getMethod("objectFieldOffset", Field.class).invoke(unsafe, elements);
        Method rawGet = unsafeType.getMethod("getObject", Object.class, long.class);
        Object cached = rawGet.invoke(unsafe, pathList, offset);
        return out.put("fieldPresent", elements.getName().equals("dexElements"))
                .put("viewCount", Array.getLength(view)).put("cachedCount", Array.getLength(cached))
                .put("sameComponentType", view.getClass() == cached.getClass())
                .put("cachedUnchanged", cached == rawGet.invoke(unsafe, pathList, offset));
    }
    private DexProbe() {}
}
