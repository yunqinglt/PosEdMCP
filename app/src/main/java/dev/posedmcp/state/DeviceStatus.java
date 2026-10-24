package dev.posedmcp.state;

import android.os.Build;

import org.json.JSONObject;

import dev.posedmcp.Logx;
import dev.posedmcp.root.RootShell;

/**
 * Facts about the device and the module that are expensive or intrusive to
 * re-derive on every call.
 *
 * <p>The libsu probe verifies the available shell once, when the user starts the
 * service. It is deliberately not exposed as a tool: an agent must not be able
 * to trigger a root shell outside the confirmation gate, not even a harmless
 * one, or the gate stops meaning anything.
 */
public final class DeviceStatus {

    private DeviceStatus() {
    }

    public static void probeRootAsync() {
        Thread t = new Thread(() -> {
            boolean available = RootShell.isAvailable();
            if (available) {
                Logx.i("root probe: available via " + RootShell.suPath());
            } else {
                Logx.i("root unavailable; libsu application-shell fallback remains available when reported by health");
            }
        }, "posedmcp-root-probe");
        t.setDaemon(true);
        t.start();
    }

    public static JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("model", Build.MODEL);
            o.put("device", Build.DEVICE);
            o.put("androidRelease", Build.VERSION.RELEASE);
            o.put("sdkInt", Build.VERSION.SDK_INT);
            o.put("securityPatch", Build.VERSION.SECURITY_PATCH);
            o.put("supportedAbis", String.join(",", Build.SUPPORTED_ABIS));
            o.put("buildId", Build.DISPLAY);

            JSONObject current = RootShell.permissionJson();
            o.put("rootAvailable", current.opt("rootGranted"));
            o.put("rootProbedAt", current.optLong("checkedAt"));
            o.put("shell", current);
            o.put("suPath", RootShell.suPath());
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static JSONObject rootJson() {
        JSONObject o = RootShell.permissionJson();
        try {
            o.put("available", o.opt("rootGranted"));
            o.put("suPath", RootShell.suPath());
            o.put("note", "Permission is last verified at service start or shell execution. Health queries never request root."
                    + " Root-only tools fail without root; shell_exec permits app-UID execution after confirmation.");
            o.put("diagnostics", RootShell.diagnostics());
        } catch (Throwable ignored) {
        }
        return o;
    }
}
