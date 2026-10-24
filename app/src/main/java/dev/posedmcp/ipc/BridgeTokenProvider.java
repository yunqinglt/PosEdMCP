package dev.posedmcp.ipc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import dev.posedmcp.Logx;

/**
 * Hands the bridge credentials to the module running inside other processes.
 *
 * <p>The module cannot read this app's preferences directly: Android 16 moved
 * them to {@code /data/misc/<uuid>/prefs/<pkg>}, a directory no other uid can
 * even traverse. LSPosed's {@code XSharedPreferences} is tried first, because it
 * is the framework's own mechanism and keeps the value private, but it depends
 * on the framework's daemon having chmod'ed the file, which does not happen
 * until the framework rescans. This provider is the fallback that always works.
 *
 * <h2>What this does and does not protect</h2>
 *
 * <p>Any local app can call this provider and learn the token, so the token is
 * <b>not</b> a confidentiality boundary. That is inherent to the design rather
 * than an oversight: the module is loaded into arbitrary applications, so any
 * app in scope can read the token out of its own process regardless of how it
 * is delivered.
 *
 * <p>What the token does buy is integrity. Without it, any local process could
 * connect to the bridge, inject fabricated events, and race to answer the app's
 * requests to system_server with forged results - a fake screenshot, a fake
 * foreground app. With it, that requires deliberately calling this provider
 * first, which is logged with the caller's uid and package.
 *
 * <p>Neither the token nor this provider grants any capability on its own: every
 * privileged operation the bridge can carry out is separately gated by the
 * confirmation dialog, which needs a human tap and shows the exact command.
 */
public class BridgeTokenProvider extends ContentProvider {

    public static final String AUTHORITY = "dev.posedmcp.bridge";
    public static final String METHOD_GET = "getCredentials";
    public static final String KEY_TOKEN = "bridge_token";
    public static final String KEY_PORT = "bridge_port";

    /** system_server, which must always be able to reach the bridge. */
    private static final int UID_SYSTEM = 1000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!METHOD_GET.equals(method)) {
            return null;
        }
        int callingUid = Binder.getCallingUid();
        String caller = describe(callingUid);
        Logx.i("bridge credentials requested by " + caller);

        Bundle result = BridgeBootstrap.read(getContext(), callingUid);
        // Returned so a future version can refuse unusual callers if the user
        // wants that; recording it now means the decision can be made on data.
        result.putString("caller", caller);
        return result;
    }

    private String describe(int uid) {
        if (uid == UID_SYSTEM) {
            return "system_server (uid 1000)";
        }
        try {
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            if (packages != null && packages.length > 0) {
                return packages[0] + " (uid " + uid + ")";
            }
        } catch (Throwable ignored) {
        }
        return "uid " + uid;
    }

    // ---- unused ContentProvider surface ----------------------------------

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
