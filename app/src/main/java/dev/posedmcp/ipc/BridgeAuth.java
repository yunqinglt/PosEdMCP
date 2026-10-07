package dev.posedmcp.ipc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XSharedPreferences;
import dev.posedmcp.Logx;

/**
 * Reads the bridge token out of the module app's preferences, from inside a
 * hooked process.
 *
 * <p>The token is the only thing standing between another app on the device and
 * the system-privileged bridge, so it is fetched through the mechanism the
 * framework provides for exactly this - asking for the module's own Context and
 * reading its preferences - rather than by loosening file permissions.
 *
 * <p>Results are deliberately <b>not</b> cached. Android 16 relocated
 * SharedPreferences out of {@code /data/data/<pkg>/shared_prefs} into
 * {@code /data/misc/<uuid>/prefs/<pkg>}, and the module is often loaded into a
 * process before the app has written its tokens; caching an early empty read
 * would wedge the bridge permanently.
 */
public final class BridgeAuth {

    public static final String MODULE_PACKAGE = "dev.posedmcp";
    private static final String PREFS_FILE = "posedmcp";
    private static final int SYSTEM_UID = 1000;

    /**
     * Replies from the credential service are delivered here rather than on the
     * main looper. The module is loaded on the host application's main thread,
     * and a main-thread handler would deadlock: bindService's callbacks could
     * never run while the main thread sat waiting on the latch.
     */
    private static final class ReplyLooper {
        private static volatile HandlerThread thread;
        private static volatile Handler handler;

        static Handler get() {
            Handler current = handler;
            if (current != null) {
                return current;
            }
            synchronized (ReplyLooper.class) {
                if (handler == null) {
                    HandlerThread created = new HandlerThread("posedmcp-credential-reply");
                    created.start();
                    thread = created;
                    handler = new Handler(created.getLooper());
                }
                return handler;
            }
        }
    }

    /** Set once, so a reconnect every few seconds does not spam the log. */
    private static final java.util.concurrent.atomic.AtomicReference<String> RESOLVED_VIA =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Told with everything the app handed over, every time it hands it over.
     *
     * <p>The credentials and the hook library travel together, and this is the
     * first point at which either exists: earlier than any socket, and earlier
     * than the app has finished starting. A caller that wants the library has to
     * take it here, because there is no way to ask for it sooner - the app
     * cannot be read from disk (see {@link dev.posedmcp.state.HookMirror}) and
     * its service answers nothing until its process is up.
     *
     * <p>Fired on every resolution rather than once, because the first lookup is
     * usually too early. The module is loaded before the host application
     * exists, so the first attempt finds no Context and resolves nothing; the
     * client then connects anyway and the app approves it from its trust list,
     * which is why "resolved via" is often missing from a process that is
     * nonetheless connected. A listener has to be ready for a later answer.
     */
    public interface OnResolved {
        void resolved(Map<String, String> values);
    }

    private static volatile OnResolved onResolved;

    public static void setOnResolved(OnResolved listener) {
        onResolved = listener;
    }

    private BridgeAuth() {
    }

    public static String token() {
        return read("bridge_token");
    }

    public static int bridgePort() {
        String raw = read("bridge_port");
        if (raw == null) {
            return 8766;
        }
        try {
            return Integer.parseInt(raw);
        } catch (Throwable t) {
            return 8766;
        }
    }

    public static String read(String key) {
        Map<String, String> values = load(key);
        return values.get(key);
    }

    private static Map<String, String> load(String key) {
        Map<String, String> out = new HashMap<>();

        // 1. The published mirror. This is the only route that works from inside
        //    another app's process: LSPosed's XSharedPreferences reads the legacy
        //    /data/data/<pkg>/shared_prefs path and elevates access through its
        //    daemon, which the app's own (Android 16) prefs directory cannot be.
        try {
            XSharedPreferences xsp =
                    new XSharedPreferences(MODULE_PACKAGE, BridgeCredentials.FILE);
            xsp.reload();
            Map<String, ?> mirror = xsp.getAll();
            if (mirror != null && mirror.containsKey(key)) {
                for (Map.Entry<String, ?> e : mirror.entrySet()) {
                    Object v = e.getValue();
                    if (v != null) {
                        out.put(e.getKey(), String.valueOf(v));
                    }
                }
                return resolved(out, "XSharedPreferences mirror");
            }
        } catch (Throwable t) {
            Logx.w("XSharedPreferences(bridge) unavailable: " + t);
        }

        // 2. External media. This is the only app-specific directory Android
        //    intends other applications to read: it carries no per-app SELinux
        //    categories, so unlike the app's own data it is reachable from an
        //    arbitrary process without any framework cooperation.
        try {
            Map<String, String> fromMedia = readExternalMedia();
            if (fromMedia != null && fromMedia.containsKey(key)) {
                return resolved(fromMedia, "external media");
            }
        } catch (Throwable t) {
            Logx.w("external media credentials unreadable: " + t);
        }

        // 3. Plain Binder. Works for system applications and for anything that
        //    can see this package; package visibility hides it from ordinary
        //    third-party apps, and their manifests are not ours to change.
        try {
            Bundle fromService = queryService();
            if (fromService != null && fromService.containsKey(key)) {
                for (String k : fromService.keySet()) {
                    Object v = fromService.get(k);
                    if (v != null) {
                        out.put(k, String.valueOf(v));
                    }
                }
                return resolved(out, "Binder service");
            }
        } catch (Throwable t) {
            Logx.w("credential service unavailable: " + t);
        }

        // 4. The app's provider, for callers that can see its package.
        try {
            Bundle fromProvider = queryProvider();
            if (fromProvider != null && fromProvider.containsKey(key)) {
                for (String k : fromProvider.keySet()) {
                    Object v = fromProvider.get(k);
                    if (v != null) {
                        out.put(k, String.valueOf(v));
                    }
                }
                return resolved(out, "ContentProvider");
            }
        } catch (Throwable t) {
            Logx.w("bridge provider unavailable: " + t);
        }

        // 3. Same-process case, and anything else that can legitimately reach the
        //    app's own preferences.
        try {
            Context moduleContext = contextFor(MODULE_PACKAGE);
            if (moduleContext != null) {
                SharedPreferences sp = moduleContext.getSharedPreferences(PREFS_FILE,
                        Context.MODE_PRIVATE);
                if (sp.contains(key)) {
                    for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                        out.put(e.getKey(), String.valueOf(e.getValue()));
                    }
                    return resolved(out, "module context prefs");
                }
            }
        } catch (Throwable t) {
            Logx.w("module context unavailable: " + t);
        }

        // 3. Older frameworks that kept the module's real prefs at the legacy path.
        try {
            XSharedPreferences xsp = new XSharedPreferences(MODULE_PACKAGE, PREFS_FILE);
            xsp.reload();
            Map<String, ?> all = xsp.getAll();
            if (all != null && all.containsKey(key)) {
                for (Map.Entry<String, ?> e : all.entrySet()) {
                    Object v = e.getValue();
                    if (v != null) {
                        out.put(e.getKey(), String.valueOf(v));
                    }
                }
                return resolved(out, "legacy XSharedPreferences");
            }
        } catch (Throwable t) {
            Logx.w("XSharedPreferences(" + PREFS_FILE + ") unavailable: " + t);
        }

        // 4. Last resort: parse the files ourselves, checking every location the
        //    platform has used for app preferences.
        for (String path : candidatePrefsPaths(key)) {
            try {
                File f = new File(path);
                if (!f.canRead()) {
                    continue;
                }
                try (FileInputStream in = new FileInputStream(f)) {
                    Map<String, String> parsed = new HashMap<>();
                    parseXml(in, parsed);
                    if (parsed.containsKey(key)) {
                        return resolved(parsed, "file " + path);
                    }
                }
            } catch (Throwable t) {
                Logx.w("prefs read failed at " + path + ": " + t);
            }
        }

        return out;
    }

    private static java.util.List<String> candidatePrefsPaths(String key) {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(BridgeCredentials.FILE + ".xml");
        names.add(PREFS_FILE + ".xml");

        java.util.List<String> roots = new java.util.ArrayList<>();
        roots.add("/data/data/" + MODULE_PACKAGE + "/shared_prefs/");
        roots.add("/data/user/0/" + MODULE_PACKAGE + "/shared_prefs/");
        try {
            File misc = new File("/data/misc");
            File[] uuids = misc.listFiles();
            if (uuids != null) {
                for (File uuid : uuids) {
                    roots.add(new File(uuid, "prefs/" + MODULE_PACKAGE).getAbsolutePath() + "/");
                }
            }
        } catch (Throwable ignored) {
        }

        java.util.List<String> paths = new java.util.ArrayList<>();
        for (String root : roots) {
            for (String name : names) {
                paths.add(root + name);
            }
        }
        return paths;
    }

    /**
     * Binds this app's credential service and asks it for the bridge token.
     *
     * <p>Runs on whatever thread the caller is on; the Binder callbacks land on
     * the process's main thread, so the answer is collected through a latch with
     * a short timeout rather than by blocking the main thread.
     */
    private static Bundle queryService() {
        Context base = baseContext();
        if (base == null) {
            return null;
        }

        final Bundle[] answer = {null};
        final java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(1);

        Messenger replyTo = new Messenger(new Handler(ReplyLooper.get().getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == BridgeTokenService.MSG_GET_CREDENTIALS && msg.getData() != null) {
                    answer[0] = msg.getData();
                }
                done.countDown();
            }
        });

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    Message request = Message.obtain(null,
                            BridgeTokenService.MSG_GET_CREDENTIALS);
                    request.replyTo = replyTo;
                    new Messenger(binder).send(request);
                } catch (Throwable t) {
                    Logx.w("credential request failed: " + t);
                    done.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                done.countDown();
            }
        };

        Intent intent = new Intent().setComponent(
                new ComponentName(BridgeTokenService.PACKAGE, BridgeTokenService.CLASS));
        boolean bound = false;
        try {
            bound = base.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                // Expected for an ordinary third-party app: package visibility
                // hides this app from it. The connection handshake is the way in.
                return null;
            }
            done.await(4, java.util.concurrent.TimeUnit.SECONDS);
            return answer[0];
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            Logx.w("bindService failed: " + t);
            return null;
        } finally {
            if (bound) {
                try {
                    base.unbindService(connection);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Records which channel delivered the credentials, and tells the listener. */
    private static Map<String, String> resolved(Map<String, String> values, String route) {
        if (RESOLVED_VIA.compareAndSet(null, route)) {
            Logx.i("bridge credentials resolved via " + route);
        }
        OnResolved listener = onResolved;
        if (listener != null) {
            try {
                listener.resolved(values);
            } catch (Throwable t) {
                // This runs inside the lookup the bridge itself depends on; a
                // listener that throws must not cost us the credentials.
                Logx.w("credential listener threw: " + t);
            }
        }
        return values;
    }

    /** Reads the copy the app writes to its shared external media directory. */
    private static Map<String, String> readExternalMedia() {
        String[] candidates = {
                BridgeCredentials.MEDIA_DIR + MODULE_PACKAGE + "/" + BridgeCredentials.MEDIA_FILE,
                "/sdcard/Android/media/" + MODULE_PACKAGE + "/" + BridgeCredentials.MEDIA_FILE,
                "/mnt/sdcard/Android/media/" + MODULE_PACKAGE + "/" + BridgeCredentials.MEDIA_FILE,
        };
        for (String path : candidates) {
            try {
                File file = new File(path);
                if (!file.canRead()) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader in = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(file),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        sb.append(line);
                    }
                }
                org.json.JSONObject json = new org.json.JSONObject(sb.toString());
                Map<String, String> out = new HashMap<>();
                java.util.Iterator<String> names = json.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    out.put(name, String.valueOf(json.get(name)));
                }
                return out;
            } catch (Throwable t) {
                Logx.w("external media read failed at " + path + ": " + t);
            }
        }
        return null;
    }

    /** Asks the app's provider for the bridge credentials. */
    private static Bundle queryProvider() {
        Context base = baseContext();
        if (base == null) {
            return null;
        }
        return base.getContentResolver().call(
                Uri.parse("content://" + BridgeTokenProvider.AUTHORITY),
                BridgeTokenProvider.METHOD_GET, null, null);
    }

    /**
     * A Context belonging to whichever process we are running in.
     *
     * <p>The system context is only usable when we really are system_server. In
     * an app process it carries the package name {@code android} while the uid is
     * the app's, and anything that cross-checks the two - such as calling our own
     * ContentProvider - rejects it with a SecurityException. The module is loaded
     * before the host Application exists, so this can legitimately return null;
     * callers retry.
     */
    private static Context baseContext() {
        try {
            Class<?> helper = Class.forName("android.app.AndroidAppHelper");
            Object app = helper.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }

        if (android.os.Process.myUid() != SYSTEM_UID) {
            return null;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null);
            if (thread != null) {
                for (String method : new String[]{"getSystemContext", "getSystemUiContext"}) {
                    Object ctx = ReflectCall.call(thread, method);
                    if (ctx instanceof Context) {
                        return (Context) ctx;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context contextFor(String pkg) {
        Context base = baseContext();
        if (base == null) {
            return null;
        }
        if (pkg.equals(base.getPackageName())) {
            return base;
        }
        try {
            return base.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable t) {
            Logx.w("createPackageContext(" + pkg + ") failed: " + t);
            return null;
        }
    }

    /** Tiny reflection shim, so this class does not depend on the rest of the module. */
    private static final class ReflectCall {
        static Object call(Object target, String method) {
            try {
                java.lang.reflect.Method m = target.getClass().getMethod(method);
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** Minimal reader for the flat {@code <map><string .../></map>} prefs format. */
    private static void parseXml(FileInputStream in, Map<String, String> out) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        org.w3c.dom.Document doc;
        javax.xml.parsers.DocumentBuilderFactory f =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
        try {
            // Not every Android parser knows this feature; the file is local and
            // written by our own process, so refusal is not fatal.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Throwable ignored) {
        }
        f.setExpandEntityReferences(false);
        doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                sb.toString().getBytes("UTF-8")));
        org.w3c.dom.NodeList nodes = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            org.w3c.dom.Node node = nodes.item(i);
            if (node.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                continue;
            }
            if (!"string".equals(node.getNodeName()) && !"int".equals(node.getNodeName())
                    && !"long".equals(node.getNodeName()) && !"boolean".equals(node.getNodeName())) {
                continue;
            }
            org.w3c.dom.Element el = (org.w3c.dom.Element) node;
            String name = el.getAttribute("name");
            if (name.isEmpty()) {
                continue;
            }
            if ("string".equals(node.getNodeName())) {
                out.put(name, el.getTextContent());
            } else {
                out.put(name, el.getAttribute("value"));
            }
        }
    }
}
