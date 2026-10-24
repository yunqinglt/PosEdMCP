package dev.posedmcp.display;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import dev.display.api.DisplayContract;
import dev.display.api.IDisplay;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounded binding, shared connection, explicit disposal, no automatic mutation retry.
 * Synchronous Binder execution itself has no timeout; Core must keep calls short.
 */
public final class DisplayClient {
    private static volatile DisplayClient instance;
    private final Context context;
    private final Object lock = new Object();
    private Connection connection;
    private final class Connection implements ServiceConnection {
        final CountDownLatch ready = new CountDownLatch(1);
        volatile IDisplay service;
        boolean bound;
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (lock) {
                if (connection != this) return;
                service = IDisplay.Stub.asInterface(binder);
                ready.countDown();
            }
        }
        public void onServiceDisconnected(ComponentName name) { release(this); }
        public void onBindingDied(ComponentName name) { release(this); }
        public void onNullBinding(ComponentName name) { release(this); }
    }
    private DisplayClient(Context context) { this.context = context.getApplicationContext(); }
    public static DisplayClient get(Context context) {
        DisplayClient local = instance;
        if (local == null) synchronized (DisplayClient.class) {
            local = instance;
            if (local == null) instance = local = new DisplayClient(context);
        }
        return local;
    }
    public static void disconnect() {
        DisplayClient local = instance;
        if (local != null) local.close();
    }
    public void close() { synchronized (lock) { if (connection != null) release(connection); } }
    private void release(Connection c) {
        synchronized (lock) {
            if (connection == c) connection = null;
            c.service = null;
            c.ready.countDown();
            if (c.bound) {
                c.bound = false;
                try { context.unbindService(c); } catch (IllegalArgumentException ignored) {}
            }
        }
    }
    private Connection connect() throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Display AIDL calls must run off the main thread");
        Connection c;
        synchronized (lock) {
            if (connection != null && connection.service != null && !connection.service.asBinder().isBinderAlive())
                release(connection);
            c = connection;
            if (c == null) {
                PackageManager pm = context.getPackageManager();
                if (pm.checkSignatures(context.getPackageName(), DisplayContract.PACKAGE) != PackageManager.SIGNATURE_MATCH)
                    throw new SecurityException("Display is missing or is not signed with the PosEdMCP certificate");
                connection = c = new Connection();
                try {
                    // Android requires releasing a ServiceConnection even when bindService
                    // returns false. Track the attempt before calling into the framework.
                    c.bound = true;
                    boolean requested = context.bindService(new Intent().setComponent(new ComponentName(
                            DisplayContract.PACKAGE, DisplayContract.SERVICE)), Context.BIND_AUTO_CREATE,
                            context.getMainExecutor(), c);
                    if (!requested) {
                        release(c);
                        throw new IllegalStateException("Display service could not start. Check that Display is enabled; on HyperOS allow Display in Background autostart settings, then retry");
                    }
                } catch (RuntimeException e) { release(c); throw e; }
            }
        }
        try {
            if (!c.ready.await(4, TimeUnit.SECONDS)) { release(c); throw new IllegalStateException("Display binding timed out"); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); release(c); throw e; }
        if (c.service == null) throw new IllegalStateException("Display disconnected during binding");
        return c;
    }
    public interface Call { String run(IDisplay service) throws RemoteException; }
    public JSONObject call(Call op) {
        Connection c = null;
        try {
            c = connect();
            IDisplay service = c.service;
            if (service == null) throw new IllegalStateException("Display disconnected");
            return DisplayContract.decodeReply(op.run(service));
        } catch (RemoteException e) {
            if (c != null) release(c);
            return DisplayContract.error("TRANSPORT_ERROR", "Binder call failed; mutation outcome may be unknown. Query state before retrying with the same requestId");
        } catch (SecurityException e) {
            if (c != null) release(c);
            return DisplayContract.error("UNAUTHORIZED", e.getMessage());
        } catch (Exception e) {
            return DisplayContract.error("UNAVAILABLE", e.getMessage() == null ? "Display unavailable" : e.getMessage());
        }
    }
    public JSONObject status() { return call(IDisplay::status); }
}
