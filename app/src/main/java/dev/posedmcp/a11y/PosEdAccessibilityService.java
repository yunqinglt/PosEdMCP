package dev.posedmcp.a11y;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONObject;

import dev.posedmcp.Logx;
import dev.posedmcp.McpService;

/**
 * The app's accessibility service.
 *
 * <p>It exists for two reasons that reinforce each other.
 *
 * <p>Capability: screen capture, gesture injection and the view tree all come
 * from here without root and without hidden APIs. On Android 16 that is not a
 * convenience - the system_server route for screenshots no longer exists, and
 * reading the view tree otherwise costs a confirmed root command every time.
 *
 * <p>Liveness: an application hosting an enabled accessibility service holds a
 * binding from the system, so it stays resident instead of being frozen the
 * moment it leaves the screen. That is precisely the state an on-device agent
 * leaves it in, and while frozen the MCP endpoint answers nothing at all.
 */
public class PosEdAccessibilityService extends AccessibilityService {

    /** The last package seen, so window transitions dedupe naturally. */
    private volatile String lastPackage;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityBridge.attach(this);
        lastPackage = null;
        Logx.i("accessibility service connected");

        // Being bound keeps this process alive; starting the server here also
        // brings the endpoint back if it had ever been stopped.
        try {
            McpService.start(this);
        } catch (Throwable t) {
            Logx.w("could not start the MCP service from accessibility: " + t);
        }
        AccessibilityBridge.publish("a11y.connected", new JSONObject());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        try {
            CharSequence packageName = event.getPackageName();
            if (packageName == null) {
                return;
            }
            String current = packageName.toString();
            // Only a change of foreground app is worth reporting: window events
            // fire continuously during animations, and forwarding each one would
            // drown the feed the agent polls.
            if (current.equals(lastPackage)) {
                return;
            }
            lastPackage = current;
            AccessibilityBridge.noteWindow(current);
            JSONObject data = AccessibilityBridge.describeEvent(event);
            data.put("previous", lastPackage == null ? "" : lastPackage);
            AccessibilityBridge.publish("a11y.window", data);
        } catch (Throwable t) {
            Logx.w("accessibility event handling failed: " + t);
        }
    }

    @Override
    public void onInterrupt() {
        // Nothing is in progress that needs cancelling.
    }

    @Override
    public boolean onUnbind(Intent intent) {
        AccessibilityBridge.detach(this);
        AccessibilityBridge.publish("a11y.disconnected", new JSONObject());
        Logx.i("accessibility service disconnected");
        return super.onUnbind(intent);
    }
}
