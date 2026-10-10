package dev.deploy;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONException;
import org.json.JSONObject;

import dev.deploy.api.IDeploy;
import dev.deploy.core.DeployCore;

/**
 * The AIDL frontend: PosEdMCP (and any app that can see this package) binds
 * here to reach the analysis backend.
 *
 * <p>Exported and implementation-only. It holds a {@code DeployCore} and turns
 * every AIDL call into a JSON string, so the wire format is identical to what
 * the MCP service returns - the two frontends are two doors into one room.
 */
public final class DeployAidlService extends Service {

    private final DeployCore core = new DeployCore();

    private final IDeploy.Stub binder = new IDeploy.Stub() {
        @Override
        public String ping() {
            return core.ping();
        }

        @Override
        public String status() {
            return wrap(core::status);
        }

        @Override
        public String analyze(String path, String op, String argsJson) {
            return wrap(() -> core.analyze(path, op, parse(argsJson)));
        }
    };

    private static JSONObject parse(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(json);
    }

    private interface Op {
        JSONObject run() throws Exception;
    }

    /** Every AIDL reply is a JSON object, including failures. */
    private static String wrap(Op op) {
        try {
            return op.run().toString();
        } catch (Throwable t) {
            JSONObject e = new JSONObject();
            try {
                e.put("error", t.getMessage() == null ? t.toString() : t.getMessage());
            } catch (JSONException ignored) {
            }
            return e.toString();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
