package dev.posedmcp.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.posedmcp.Logx;

/**
 * What the accessibility service can be asked to do, from anywhere in the app.
 *
 * <p>The service object itself only exists while the user has it enabled, so
 * every entry point checks for it and fails with something the caller can act
 * on rather than a null dereference.
 *
 * <p>Two things make this worth having even where root is available. It needs no
 * root and no hidden API, so it keeps working where those do not - on Android 16
 * the system_server screenshot route no longer exists at all. And it is the only
 * one of the three that stays responsive while another app is in front, which is
 * exactly when an agent needs it.
 */
public final class AccessibilityBridge {

    /** Where window events go. Set by the service so events land in the store. */
    public interface EventSink {
        void onEvent(String type, JSONObject data);
    }

    private static final int MAX_TREE_NODES = 800;

    private static volatile AccessibilityService service;
    private static volatile EventSink sink;
    /** The package of the window most recently reported by the service. */
    private static volatile String lastWindowPackage;

    private AccessibilityBridge() {
    }

    static void attach(AccessibilityService instance) {
        service = instance;
    }

    static void detach(AccessibilityService instance) {
        if (service == instance) {
            service = null;
        }
    }

    /** Called by the service whenever the foreground window changes package. */
    static void noteWindow(String pkg) {
        lastWindowPackage = pkg;
    }

    /** The package of the foreground window as the service last saw it, or null. */
    public static String lastWindowPackage() {
        return lastWindowPackage;
    }

    public static void setEventSink(EventSink eventSink) {
        sink = eventSink;
    }

    static void publish(String type, JSONObject data) {
        EventSink current = sink;
        if (current == null) {
            return;
        }
        try {
            current.onEvent(type, data);
        } catch (Throwable ignored) {
            // An event nobody can record must not break the service.
        }
    }

    public static boolean isConnected() {
        return service != null;
    }

    /** Connected and working. */
    public static final String STATE_ON = "on";
    /** Not in the system's enabled list at all - the user has not turned it on. */
    public static final String STATE_OFF = "off";
    /**
     * Listed as enabled, but not running yet.
     *
     * <p>Reported for the first few seconds of a process's life, because that is
     * genuinely what it is: an enabled service takes a moment to attach and the
     * page is a snapshot, not a live view.
     */
    public static final String STATE_CONNECTING = "connecting";
    /**
     * Listed as enabled, not running, and it has had time to attach.
     *
     * <p>This is its own state because its repair is its own thing. When the
     * service's connection drops — the process was killed, and this ROM's
     * cleaner kills it on a schedule — the accessibility framework records it in
     * {@code mCrashedServices} and then refuses to bind it again. The setting
     * still says it is on, the switch in Settings is still on, and the only way
     * back is to switch it off and on again. Reporting that as "not enabled"
     * sends the user to do the one thing that will not work.
     */
    public static final String STATE_FAULTED = "faulted";

    /**
     * When this process first asked. The grace period runs from here rather than
     * from process start, which is not knowable from inside the app.
     */
    private static final long FIRST_ASKED_AT = android.os.SystemClock.elapsedRealtime();
    /** Long enough for a service that is merely slow to attach. */
    private static final long ATTACH_GRACE_MS = 10_000L;

    /** The component the framework tracks, spelled the way the setting spells it. */
    public static String component(Context ctx) {
        return ctx.getPackageName() + "/" + PosEdAccessibilityService.class.getName();
    }

    /**
     * The raw enabled-services setting, or {@code null} if it cannot be read.
     *
     * <p>Queried rather than read through {@code Settings.Secure.getString},
     * which is a mistake worth recording: that helper keeps a per-process
     * name/value cache that a write from outside the process does not reliably
     * invalidate. Measured, an app that removed this service from the setting
     * saw the removal at once and then did not see it put back — so a user who
     * repaired accessibility in Settings would have been told forever that it
     * was still off. The provider is the one that took the write, so asking it
     * directly is the read that actually reflects what happened.
     */
    public static String enabledSetting(Context ctx) {
        try (android.database.Cursor cursor = ctx.getContentResolver().query(
                android.provider.Settings.Secure.CONTENT_URI,
                new String[]{android.provider.Settings.Secure.VALUE},
                android.provider.Settings.Secure.NAME + "=?",
                new String[]{android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES},
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Which of the four states the service is in. */
    public static String state(Context ctx) {
        if (isConnected()) {
            return STATE_ON;
        }
        String enabled = enabledSetting(ctx);
        if (enabled == null || !enabled.contains(component(ctx))) {
            return STATE_OFF;
        }
        // Listed but not running. Two quite different things look identical from
        // in here - still attaching after a restart, or marked crashed with the
        // framework refusing to bind it - and nothing the app can call tells
        // them apart. So a service that has only just been asked about gets the
        // boring answer, and only one that should have attached long ago is
        // called a fault.
        return android.os.SystemClock.elapsedRealtime() - FIRST_ASKED_AT < ATTACH_GRACE_MS
                ? STATE_CONNECTING : STATE_FAULTED;
    }

    /** Human-readable state for the app UI and the status tool. */
    public static String describe() {
        return service == null ? "not enabled" : "enabled";
    }

    /** As above, but able to tell "off" apart from "the system gave up on it". */
    public static String describe(Context ctx) {
        switch (state(ctx)) {
            case STATE_ON:
                return "enabled";
            case STATE_CONNECTING:
                return "enabled, not connected yet";
            case STATE_FAULTED:
                return "ENABLED but NOT RUNNING - the system is not going to reconnect it on its"
                        + " own, most likely because it marked the service malfunctioning when"
                        + " this app's process was killed. It has to be switched off and on"
                        + " again; the switch in Settings already reads as on.";
            default:
                return "not enabled";
        }
    }

    private static AccessibilityService require() throws IOException {
        AccessibilityService current = service;
        if (current == null) {
            throw new IOException("the accessibility service is not enabled."
                    + " Open PosEdMCP and turn it on under \"Accessibility\", then retry");
        }
        return current;
    }

    // ---- screenshots ------------------------------------------------------

    /**
     * Captures the display through the accessibility API.
     *
     * <p>Asynchronous underneath; the caller gets the bitmap or an error, and
     * never a half-built screen.
     */
    public static Bitmap screenshot(long timeoutMs) throws IOException {
        AccessibilityService current = require();
        CountDownLatch done = new CountDownLatch(1);
        Bitmap[] result = {null};
        String[] failure = {null};
        Executor executor = command -> new Handler(Looper.getMainLooper()).post(command);

        try {
            current.takeScreenshot(Display.DEFAULT_DISPLAY, executor,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult shot) {
                            try {
                                HardwareBuffer buffer = shot.getHardwareBuffer();
                                ColorSpace colorSpace = shot.getColorSpace();
                                try {
                                    Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                                    // The wrapped bitmap aliases the buffer, which
                                    // is released below.
                                    result[0] = wrapped == null ? null
                                            : wrapped.copy(Bitmap.Config.ARGB_8888, false);
                                    if (wrapped != null) {
                                        wrapped.recycle();
                                    }
                                } finally {
                                    buffer.close();
                                }
                            } catch (Throwable t) {
                                failure[0] = String.valueOf(t);
                            } finally {
                                done.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            failure[0] = "accessibility screenshot failed (code " + errorCode + ")"
                                    + (errorCode == 1 ? "; the platform rate-limits captures,"
                                    + " wait a moment" : "");
                            done.countDown();
                        }
                    });
        } catch (Throwable t) {
            throw new IOException("could not request a screenshot: " + t);
        }

        try {
            if (!done.await(Math.max(1000L, timeoutMs), TimeUnit.MILLISECONDS)) {
                throw new IOException("the accessibility screenshot timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while capturing");
        }
        if (result[0] == null) {
            throw new IOException(failure[0] == null ? "no image was returned" : failure[0]);
        }
        return result[0];
    }

    // ---- input ------------------------------------------------------------

    /**
     * Sends a tap, swipe or long press.
     *
     * <p>Returns once the gesture has actually been dispatched, so "injected"
     * means it happened rather than that it was queued.
     */
    public static void gesture(float fromX, float fromY, float toX, float toY, int durationMs)
            throws IOException {
        AccessibilityService current = require();
        Path path = new Path();
        path.moveTo(fromX, fromY);
        if (fromX != toX || fromY != toY) {
            path.lineTo(toX, toY);
        }
        GestureDescription description = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0,
                        Math.max(1, durationMs)))
                .build();

        CountDownLatch done = new CountDownLatch(1);
        String[] failure = {null};
        try {
            boolean accepted = current.dispatchGesture(description,
                    new AccessibilityService.GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            done.countDown();
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            failure[0] = "the gesture was cancelled";
                            done.countDown();
                        }
                    }, null);
            if (!accepted) {
                throw new IOException("the system refused the gesture");
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("could not dispatch the gesture: " + t);
        }

        try {
            if (!done.await(10, TimeUnit.SECONDS)) {
                throw new IOException("the gesture did not complete");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while gesturing");
        }
        if (failure[0] != null) {
            throw new IOException(failure[0]);
        }
    }

    /** HOME, BACK, RECENTS and the like, which only the service may perform. */
    public static void globalAction(int action) throws IOException {
        AccessibilityService current = require();
        if (!current.performGlobalAction(action)) {
            throw new IOException("the system refused the global action " + action);
        }
    }

    /** Types into whatever field currently has focus. */
    public static void setText(String text) throws IOException {
        AccessibilityService current = require();
        AccessibilityNodeInfo focused = current.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) {
            focused = current.getRootInActiveWindow();
        }
        AccessibilityNodeInfo editable = findEditable(focused, new AtomicInteger(0));
        if (editable == null) {
            throw new IOException("no focused text field to type into");
        }
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            throw new IOException("the field refused the text");
        }
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node,
            AtomicInteger budget) {
        if (node == null || budget.incrementAndGet() > MAX_TREE_NODES) {
            return null;
        }
        if (node.isEditable()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i), budget);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---- view tree --------------------------------------------------------

    /**
     * The active window's controls, in the same shape the uiautomator dump uses.
     *
     * <p>Same shape on purpose: an agent that learned to read one should not have
     * to learn a second format because the route changed.
     */
    public static JSONObject activeWindowTree(int maxNodes, boolean simplify) throws IOException {
        AccessibilityService current = require();
        AccessibilityNodeInfo root = current.getRootInActiveWindow();
        if (root == null) {
            throw new IOException("no active window to read");
        }
        int cap = maxNodes <= 0 ? 300 : Math.min(maxNodes, MAX_TREE_NODES);
        AtomicInteger emitted = new AtomicInteger(0);
        JSONObject container = new JSONObject();
        try {
            JSONArray children = new JSONArray();
            walk(root, children, simplify, cap, emitted);
            JSONObject out = new JSONObject();
            out.put("source", "accessibility");
            out.put("node", children.length() > 0 ? children.get(0) : new JSONObject());
            if (emitted.get() >= cap) {
                out.put("truncated", true);
                out.put("note", "Stopped after " + cap + " nodes.");
            }
            return out;
        } catch (Throwable t) {
            throw new IOException("could not read the view tree: " + t);
        }
    }

    private static void walk(AccessibilityNodeInfo node, JSONArray into, boolean simplify, int cap,
            AtomicInteger emitted) throws Exception {
        if (node == null || emitted.get() >= cap) {
            return;
        }
        JSONObject entry = describe(node);
        boolean interesting = entry.has("text") || entry.has("desc") || entry.has("id")
                || entry.optBoolean("clickable", false) || entry.optBoolean("scrollable", false);

        JSONArray children = new JSONArray();
        for (int i = 0; i < node.getChildCount() && emitted.get() < cap; i++) {
            walk(node.getChild(i), children, simplify, cap, emitted);
        }
        if (children.length() > 0) {
            entry.put("children", children);
        }
        if (simplify && !interesting && children.length() == 0) {
            return;
        }
        emitted.incrementAndGet();
        into.put(entry);
    }

    private static JSONObject describe(AccessibilityNodeInfo node) throws Exception {
        JSONObject entry = new JSONObject();
        String className = node.getClassName() == null ? "" : node.getClassName().toString();
        if (!className.isEmpty()) {
            entry.put("class", className);
        }
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            entry.put("text", text.toString());
        }
        CharSequence description = node.getContentDescription();
        if (description != null && description.length() > 0) {
            entry.put("desc", description.toString());
        }
        if (node.getViewIdResourceName() != null) {
            entry.put("id", node.getViewIdResourceName());
        }
        if (node.getPackageName() != null) {
            entry.put("package", node.getPackageName().toString());
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        entry.put("bounds", "[" + bounds.left + "," + bounds.top + "]"
                + "[" + bounds.right + "," + bounds.bottom + "]");
        JSONObject center = new JSONObject();
        center.put("x", bounds.centerX());
        center.put("y", bounds.centerY());
        entry.put("center", center);
        if (node.isClickable()) {
            entry.put("clickable", true);
        }
        if (node.isScrollable()) {
            entry.put("scrollable", true);
        }
        if (node.isEditable()) {
            entry.put("editable", true);
        }
        if (!node.isEnabled()) {
            entry.put("enabled", false);
        }
        return entry;
    }

    /** Recent window transitions, for the app UI to show that events are flowing. */
    static JSONObject describeEvent(android.view.accessibility.AccessibilityEvent event) {
        JSONObject data = new JSONObject();
        try {
            if (event.getPackageName() != null) {
                data.put("package", event.getPackageName().toString());
            }
            if (event.getClassName() != null) {
                data.put("class", event.getClassName().toString());
            }
            if (event.getText() != null && event.getText().size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (CharSequence part : event.getText()) {
                    sb.append(part);
                }
                String text = sb.toString();
                data.put("text", text.length() > 120 ? text.substring(0, 120) + "..." : text);
            }
        } catch (Throwable ignored) {
        }
        return data;
    }

    public static void logState() {
        Logx.i("accessibility service " + describe());
    }
}
