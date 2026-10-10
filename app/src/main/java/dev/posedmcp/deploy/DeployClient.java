package dev.posedmcp.deploy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.deploy.api.IDeploy;
import dev.posedmcp.Logx;

/**
 * App-side handle on the Deploy companion's AIDL service.
 *
 * <p>Deploy is a separate, non-root APK, so - like {@code DexClient} - this
 * binds lazily and re-checks the binding on every call: the ROM's background
 * policy kills it on its own schedule, and a bind is what restarts it, so no
 * caller may assume the previous handle survived.
 */
public final class DeployClient {

    private static final String DEPLOY_PACKAGE = "dev.deploy";
    private static final String DEPLOY_SERVICE = "dev.deploy.DeployAidlService";

    private static volatile DeployClient instance;

    private final Context context;
    private volatile IDeploy service;

    private DeployClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public static DeployClient get(Context context) {
        DeployClient local = instance;
        if (local == null) {
            synchronized (DeployClient.class) {
                local = instance;
                if (local == null) {
                    local = new DeployClient(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Backend status, or an {@code {error}} object when Deploy is not reachable. */
    public JSONObject status() {
        return call((s) -> s.status());
    }

    /** One op against one file, or an {@code {error}} object when unreachable. */
    public JSONObject analyze(String path, String op, JSONObject args) {
        return call((s) -> s.analyze(path, op, args == null ? "{}" : args.toString()));
    }

    private interface Op {
        String run(IDeploy service) throws Exception;
    }

    private JSONObject call(Op op) {
        IDeploy s = ensureBound(4_000L);
        if (s == null) {
            return error("the Deploy companion is not installed or not running. It ships as a"
                    + " separate APK (dev.deploy) beside PosEdMCP.");
        }
        try {
            return new JSONObject(op.run(s));
        } catch (Throwable t) {
            return error(t.getMessage() == null ? t.toString() : t.getMessage());
        }
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private IDeploy ensureBound(long timeoutMs) {
        IDeploy current = service;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (service != null) {
                return service;
            }
            CountDownLatch connected = new CountDownLatch(1);
            ServiceConnection connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    service = IDeploy.Stub.asInterface(binder);
                    connected.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    service = null;
                }
            };
            boolean requested;
            try {
                requested = context.bindService(
                        new Intent().setComponent(
                                new ComponentName(DEPLOY_PACKAGE, DEPLOY_SERVICE)),
                        connection, Context.BIND_AUTO_CREATE);
            } catch (Throwable t) {
                Logx.e("could not bind Deploy", t);
                return null;
            }
            if (!requested) {
                // Not installed, or the package is not visible to this app.
                return null;
            }
            try {
                connected.await(Math.max(500L, timeoutMs / 2), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            return service;
        }
    }
}
