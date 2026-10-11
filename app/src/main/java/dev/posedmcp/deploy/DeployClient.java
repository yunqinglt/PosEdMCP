package dev.posedmcp.deploy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import dev.deploy.api.DeployContract;

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

    public JSONObject assemble(String arch, String source, String address) {
        return call(s -> s.assemble(arch, source, address));
    }

    public JSONObject analyzeBytes(byte[] bytes, String arch, String address, JSONObject args) {
        if (bytes == null || bytes.length < 1 || bytes.length > 4096) return error("buffer must contain 1..4096 bytes");
        return call(s -> {
            java.io.File file = java.io.File.createTempFile("deploy-memory-", ".bin", context.getCacheDir());
            try {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(bytes); }
                try (ParcelFileDescriptor data = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    if (!file.delete()) throw new IOException("could not unlink memory snapshot");
                    return s.analyzeBuffer(data, arch, address, args.toString());
                }
            } finally { file.delete(); }
        });
    }

    public static final class Library {
        public final JSONObject metadata;
        public final byte[] bytes;
        Library(JSONObject metadata, byte[] bytes) { this.metadata = metadata; this.bytes = bytes; }
    }

    /** Reads the immutable descriptor, outside Binder's transaction size limit. */
    public Library prepareLibrary(String path) throws Exception {
        IDeploy s = ensureBound(4_000L);
        if (s == null) throw new IOException("Deploy companion is not installed or not running");
        Bundle result = new Bundle();
        ParcelFileDescriptor fd = s.prepareLibrary(path, result);
        if (fd == null) throw new IOException(result.getString("error", "Deploy returned no snapshot descriptor"));
        byte[] bytes;
        try (ParcelFileDescriptor.AutoCloseInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (result.containsKey("error")) throw new IOException(result.getString("error"));
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > DeployContract.MAX_LIBRARY_BYTES)
                    throw new IOException("native library exceeds 8 MiB transport limit");
                out.write(buffer, 0, n);
            }
            bytes = out.toByteArray();
        }
        JSONObject info = new JSONObject(result.getString("metadata", "{}"));
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!hash.toString().equals(info.optString("sha256")) || info.optLong("size") != bytes.length)
            throw new IOException("Deploy library snapshot integrity check failed");
        return new Library(info, bytes);
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
