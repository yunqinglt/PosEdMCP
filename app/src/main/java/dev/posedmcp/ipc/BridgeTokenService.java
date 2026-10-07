package dev.posedmcp.ipc;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import dev.posedmcp.Logx;
import dev.posedmcp.state.HookMirror;
import dev.posedmcp.state.HookStore;
import dev.posedmcp.state.Prefs;

/**
 * Serves the bridge credentials to hooked processes over plain Binder.
 *
 * <p>This is the channel that survives Android's app sandbox. The three obvious
 * alternatives do not:
 * <ul>
 *   <li>an abstract-namespace socket is refused outright - SELinux forbids
 *       {@code connectto} between two {@code untrusted_app} domains in different
 *       categories, and that is a deliberate platform boundary, not a setting;</li>
 *   <li>a ContentProvider cannot be resolved because package visibility hides
 *       this app from the applications the module runs inside, and their
 *       manifests are not ours to edit;</li>
 *   <li>reading this app's files is impossible for the same reason Android 16
 *       moved preferences into {@code /data/misc/<uuid>/prefs}.</li>
 * </ul>
 *
 * <p>Any local app can bind and ask for the token. That exposure is inherent -
 * the module is loaded into arbitrary applications, so anything in scope can
 * read the token out of its own process regardless of how it arrived. What the
 * token buys is integrity, not confidentiality; the confirmation dialog is what
 * gates privileged work.
 */
public class BridgeTokenService extends Service {

    public static final String PACKAGE = "dev.posedmcp";
    public static final String CLASS = "dev.posedmcp.ipc.BridgeTokenService";
    public static final int MSG_GET_CREDENTIALS = 1;
    public static final String KEY_TOKEN = "bridge_token";
    public static final String KEY_PORT = "bridge_port";

    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        Handler handler = new Handler(getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what != MSG_GET_CREDENTIALS) {
                    super.handleMessage(msg);
                    return;
                }
                Prefs prefs = Prefs.of(BridgeTokenService.this);
                prefs.ensureTokens();
                Bundle reply = new Bundle();
                reply.putString(KEY_TOKEN, prefs.bridgeToken());
                reply.putInt(KEY_PORT, prefs.bridgePort());
                // The hook library rides along. Every injected process already
                // makes this call at startup, and a process that has its hooks
                // without waiting for the socket - and without the app having
                // finished starting - is armed before the screen those hooks are
                // for has been drawn.
                reply.putString(HookMirror.KEY,
                        HookMirror.json(HookStore.of(BridgeTokenService.this).all()));
                if (msg.replyTo != null) {
                    try {
                        Message response = Message.obtain(null, MSG_GET_CREDENTIALS);
                        response.setData(reply);
                        msg.replyTo.send(response);
                    } catch (RemoteException e) {
                        Logx.w("could not reply with credentials: " + e);
                    }
                }
            }
        };
        messenger = new Messenger(handler);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Logx.i("bridge credentials requested over Binder");
        return messenger.getBinder();
    }
}
