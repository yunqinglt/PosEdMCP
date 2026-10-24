package dev.posedmcp.ipc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import dev.posedmcp.Logx;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import io.github.libxposed.service.IXposedService;

/** Framework-owned preferences avoid package-visibility restrictions in scoped apps. */
public final class FrameworkPreferencesProvider extends ContentProvider {
    public static final String GROUP = "posedmcp.bootstrap.v1";
    public static final String SNAPSHOT = "snapshot";
    private IXposedService framework;

    @Override public boolean onCreate() { return true; }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if ("SendBinder".equals(method)) {
            // Framework delivery comes from its root daemon or system_server.
            // Never publish private configuration to an arbitrary supplied Binder.
            if (uid != 0 && uid != 1000) throw new SecurityException("Framework UID required");
            IBinder binder = extras == null ? null : extras.getBinder("binder");
            try {
                if (binder == null || !"io.github.libxposed.service.IXposedService"
                        .equals(binder.getInterfaceDescriptor())) return Bundle.EMPTY;
                IXposedService service = IXposedService.Stub.asInterface(binder);
                int api = service.getApiVersion();
                if (api < 100 || api > 102) return Bundle.EMPTY;
                framework = service;
                Logx.i("bootstrap framework preferences connected (API " + api + ")");
                publish();
            } catch (Exception e) { Logx.w("framework preferences delivery failed: " + e); }
            return Bundle.EMPTY;
        }
        if ("publish".equals(method)) {
            if (uid != android.os.Process.myUid()) throw new SecurityException("Module UID required");
            publish();
            return Bundle.EMPTY;
        }
        return super.call(method, arg, extras);
    }

    private void publish() {
        if (framework == null) return;
        try {
            java.io.File file = new java.io.File(getContext().getFilesDir(), "bridge-bootstrap.json");
            if (!file.isFile()) return;
            String snapshot = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            HashMap<String, Object> put = new HashMap<>();
            put.put(SNAPSHOT, snapshot);
            Bundle diff = new Bundle();
            diff.putSerializable("put", put);
            diff.putSerializable("delete", new HashSet<String>());
            framework.updateRemotePreferences(GROUP, diff);
        } catch (Exception e) { Logx.w("framework bootstrap publish failed: " + e); }
    }

    public static void publish(android.content.Context context) {
        try {
            context.getContentResolver().call(Uri.parse("content://dev.posedmcp.XposedService"),
                    "publish", null, null);
        } catch (Exception e) { Logx.w("framework bootstrap provider unavailable: " + e); }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
