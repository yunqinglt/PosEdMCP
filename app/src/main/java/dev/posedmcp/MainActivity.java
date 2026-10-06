package dev.posedmcp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import dev.posedmcp.Logx;
import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.a11y.AccessibilityRepair;
import dev.posedmcp.ipc.BridgeCredentials;
import dev.posedmcp.mcp.McpTool;
import dev.posedmcp.state.HookGuard;
import dev.posedmcp.state.HookStore;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.state.SavedHook;
import dev.posedmcp.state.SavedScript;
import dev.posedmcp.state.ScriptStore;
import dev.posedmcp.xposed.LuaRuntime;

/**
 * Status, the automation library, and the hooks that are kept.
 *
 * <p>Material 3 throughout: the screen is the only surface this app draws, and a
 * plain grey list next to an agent's worth of capability looked like a debug
 * build. Colour comes from the theme, which means the wallpaper palette on
 * Android 12+ and the Material defaults everywhere else - nothing here names a
 * colour of its own.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_NOTIFICATIONS = 100;

    private Prefs prefs;
    private View root;
    private LinearLayout statusContent;
    private LinearLayout scriptsContent;
    private LinearLayout hooksContent;
    private ScrollView statusScroll;
    private ScrollView scriptsScroll;
    private ScrollView hooksScroll;
    private FrameLayout tabContent;
    /** Which apps are open on the hook page. Collapsed by default. */
    private final java.util.Set<String> expandedApps = new java.util.LinkedHashSet<>();

    /** The word the user has to type to arm hand-off mode. */
    private static final String HANDOFF_WORD = "HANDOFF";

    /** Ticks the hand-off countdown while the status tab is on screen. */
    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private TextView handoffStatus;

    /** Redraws once, after the grace period, so a real fault is not missed. */
    private final Runnable a11yRecheck = new Runnable() {
        @Override
        public void run() {
            renderStatus();
        }
    };

    private final Runnable handoffTick = new Runnable() {
        @Override
        public void run() {
            if (prefs.handoffRemainingMs() <= 0L) {
                // It just lapsed. Redraw the tab rather than leave the page
                // claiming a mode that is over.
                renderStatus();
                return;
            }
            if (handoffStatus != null) {
                handoffStatus.setText(handoffLine());
            }
            ui.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Before super.onCreate, so the wallpaper palette is in place before any
        // view resolves a colour against the theme.
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        prefs = Prefs.of(this);
        prefs.ensureTokens();
        BridgeCredentials.publish(this, prefs.bridgeToken(), prefs.bridgePort());

        statusContent = column();
        scriptsContent = column();
        hooksContent = column();
        statusScroll = scrolled(statusContent);
        scriptsScroll = scrolled(scriptsContent);
        hooksScroll = scrolled(hooksContent);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.app_name);

        TabLayout tabs = new TabLayout(this);
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_FILL);
        tabs.addTab(tabs.newTab().setText("Status"));
        tabs.addTab(tabs.newTab().setText("Scripts"));
        tabs.addTab(tabs.newTab().setText("Hooks"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                selectTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        tabContent = new FrameLayout(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        // Apps targeting Android 15+ draw edge to edge; without this the toolbar
        // sits under the status bar and taps at the top of the screen go to the
        // system instead of to the app.
        layout.setFitsSystemWindows(true);
        layout.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(tabs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.addView(tabContent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));
        root = layout;
        setContentView(layout);

        // After setContentView, so the tint is resolved against a view that is
        // already carrying the theme.
        toolbar.inflateMenu(R.menu.main);
        MenuItem about = toolbar.getMenu().findItem(R.id.action_about);
        if (about != null && about.getIcon() != null) {
            about.getIcon().setTint(color(com.google.android.material.R.attr.colorOnSurface));
        }
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_about) {
                showAbout();
                return true;
            }
            return false;
        });

        selectTab(0);

        ensureNotificationPermission();

        // Opening the app is the user asking for the server to be up; there is
        // no other way to start it without adb.
        McpService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderStatus();
        renderScripts();
        renderHooks();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Nothing on screen to count down for.
        ui.removeCallbacks(handoffTick);
        ui.removeCallbacks(a11yRecheck);
    }

    private void selectTab(int index) {
        tabContent.removeAllViews();
        tabContent.addView(index == 0 ? statusScroll : index == 1 ? scriptsScroll : hooksScroll);
    }

    // ---- about -------------------------------------------------------------

    private static final String PROFILE_ID = "YunQingLT";
    private static final String PROJECT_URL = "https://github.com/yunqinglt/PosEdMCP";

    /**
     * Who made this, where it lives, and which framework is running it.
     *
     * <p>The framework line is the one that earns its place. This app is loaded
     * by one of two Xposed generations, they differ in ways that have already
     * cost real time here, and until now the only way to find out which one a
     * handset was on was to read a log. The answer comes from a module instance,
     * because that is the only thing that knows what actually got injected -
     * this process cannot even see the module's own classes.
     */
    private void showAbout() {
        LinearLayout content = column();
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(12), dp(24), 0);

        ShapeableImageView avatar = new ShapeableImageView(this);
        avatar.setImageResource(R.drawable.myprofile);
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        avatar.setShapeAppearanceModel(new ShapeAppearanceModel.Builder()
                .setAllCorners(CornerFamily.ROUNDED, dp(64))
                .build());
        avatar.setLayoutParams(new LinearLayout.LayoutParams(dp(96), dp(96)));
        content.addView(avatar);

        TextView who = title(PROFILE_ID);
        who.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams whoParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        whoParams.topMargin = dp(12);
        who.setLayoutParams(whoParams);
        content.addView(who);

        TextView project = body(PROJECT_URL);
        project.setGravity(Gravity.CENTER);
        project.setTextColor(color(androidx.appcompat.R.attr.colorPrimary));
        project.setPaintFlags(project.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        project.setOnClickListener(v -> openUrl(PROJECT_URL));
        content.addView(project);

        TextView heading = section("ACTIVE FRAMEWORK");
        heading.setGravity(Gravity.CENTER);
        content.addView(heading);

        final TextView framework = title(frameworkLine());
        framework.setGravity(Gravity.CENTER);
        content.addView(framework);

        final TextView source = caption(frameworkSource());
        source.setGravity(Gravity.CENTER);
        content.addView(source);

        new MaterialAlertDialogBuilder(this)
                .setView(content)
                .setPositiveButton("Close", null)
                .show();

        // Asked for rather than waited on: the answer is a bridge round trip, and
        // a dialog that opened half a second late would be worse than one that
        // fills its last line in.
        McpService service = McpService.instance();
        if (service != null && service.isRunning()) {
            new Thread(() -> {
                service.refreshFramework();
                runOnUiThread(() -> {
                    framework.setText(frameworkLine());
                    source.setText(frameworkSource());
                });
            }, "posedmcp-about").start();
        }
    }

    private String frameworkLine() {
        McpService service = McpService.instance();
        if (service == null || !service.isRunning()) {
            return "the service is not running";
        }
        String reported = service.frameworkReport();
        return reported.isEmpty() ? "not reported yet" : reported;
    }

    private String frameworkSource() {
        McpService service = McpService.instance();
        String from = service == null ? "" : service.frameworkFrom();
        if (!from.isEmpty()) {
            return "reported by " + from;
        }
        return "start any application in this module's scope and it will say";
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("Nothing on this device can open " + url);
        }
    }

    // ---- status tab --------------------------------------------------------

    private void renderStatus() {
        statusContent.removeAllViews();

        McpService service = McpService.instance();
        boolean running = service != null && service.isRunning();

        statusContent.addView(section("STATUS"));
        statusContent.addView(keyValue("Service", running ? "running" : "stopped"));
        if (running) {
            statusContent.addView(keyValue("MCP endpoint",
                    "http://127.0.0.1:" + service.mcpPort() + "/mcp"));
            statusContent.addView(keyValue("Bridge port", String.valueOf(service.bridgePort())));
            statusContent.addView(keyValue("System bridge",
                    service.systemBridgeConnected() ? "connected" : "offline"));
            statusContent.addView(keyValue("Module processes",
                    service.connectedPeers() + " connected"));
        }
        statusContent.addView(keyValue("Overlay permission",
                Settings.canDrawOverlays(this) ? "granted" : "NOT granted"));
        statusContent.addView(keyValue("Battery",
                isBatteryExempt() ? "unrestricted" : "OPTIMISED - the service will freeze"));
        String a11y = AccessibilityBridge.state(this);
        boolean a11yOn = AccessibilityBridge.STATE_ON.equals(a11y);
        boolean a11yConnecting = AccessibilityBridge.STATE_CONNECTING.equals(a11y);
        boolean a11yStuck = AccessibilityBridge.STATE_FAULTED.equals(a11y);
        statusContent.addView(keyValue("Accessibility",
                a11yOn ? "enabled"
                        : a11yConnecting ? "switched on, not connected yet"
                                : a11yStuck ? "ON BUT NOT RUNNING" : "NOT enabled"));

        if (a11yStuck) {
            // The distinction matters more than it looks. From the app's side this
            // is identical to "off", and the obvious fix - turning it on in
            // Settings - does nothing, because the setting already reads as on.
            statusContent.addView(body("It is still switched on; the system is simply not"
                    + " running it, and it will not reconnect on its own. This usually means the"
                    + " accessibility framework marked the service malfunctioning when this app's"
                    + " process was killed - which this ROM does on its own schedule - after"
                    + " which it stops binding it. The switch in Settings already reads as on,"
                    + " which is why turning it \"on\" there changes nothing: it has to be"
                    + " switched off and on. Repair does exactly that."));
            statusContent.addView(outlinedButton("Repair accessibility…",
                    v -> repairAccessibility()));
        } else if (a11yConnecting) {
            statusContent.addView(body("It is switched on and has not attached yet. This page is"
                    + " a snapshot rather than a live view, so it says this for a moment after the"
                    + " app restarts. If it still says it in a minute, it is the malfunction"
                    + " state and Repair is the answer."));
            // Look again once the grace period is up, so a real fault is not
            // hidden behind a snapshot taken too early.
            ui.removeCallbacks(a11yRecheck);
            ui.postDelayed(a11yRecheck, 11_000L);
        } else if (!a11yOn) {
            statusContent.addView(body("Accessibility is what keeps this app running: an"
                    + " application hosting an enabled accessibility service holds a system"
                    + " binding, so it is not frozen once it leaves the screen. Without it the MCP"
                    + " endpoint goes silent exactly when an agent in another app tries to use it."
                    + " It is also what provides screen capture, gestures and the view tree"
                    + " without root."));
        } else {
            statusContent.addView(body("This is also what keeps the app alive: an application"
                    + " hosting an enabled accessibility service holds a system binding, so it is"
                    + " not frozen once it leaves the screen."));
        }
        if (!isBatteryExempt()) {
            statusContent.addView(body("Battery optimisation also freezes the process in the"
                    + " background. Grant unrestricted battery use, and on ColorOS also allow"
                    + " background activity for 奈何桥 in the battery settings."));
        }
        statusContent.addView(body("Without the overlay permission, approval prompts fall back to"
                + " a notification. If that also fails, privileged calls are refused."));

        statusContent.addView(section("ENDPOINT"));
        final String url = "http://127.0.0.1:" + prefs.mcpPort() + "/mcp";
        statusContent.addView(monoBlock(url));
        statusContent.addView(caption("Bearer token"));
        statusContent.addView(monoBlock(prefs.mcpToken()));

        LinearLayout tokenRow = row();
        tokenRow.addView(tonalButton("Copy URL", v -> copy("奈何桥 URL", url)));
        tokenRow.addView(tonalButton("Copy token", v -> copy("奈何桥 token", prefs.mcpToken())));
        tokenRow.addView(outlinedButton("Rotate", v -> {
            prefs.rotateTokens();
            if (McpService.instance() != null) {
                McpService.stop(this);
                McpService.start(this);
            }
            toast("Tokens rotated");
            renderStatus();
        }));
        statusContent.addView(tokenRow);

        statusContent.addView(body("The listener binds to 127.0.0.1 only. To reach it from a PC"
                + " over USB: adb forward tcp:" + prefs.mcpPort() + " tcp:" + prefs.mcpPort()));

        statusContent.addView(section("ACTIONS"));
        LinearLayout serviceRow = row();
        serviceRow.addView(filledButton(running ? "Stop service" : "Start service", v -> {
            if (McpService.instance() != null && McpService.instance().isRunning()) {
                McpService.stop(this);
            } else {
                McpService.start(this);
            }
            statusContent.postDelayed(this::renderStatus, 600L);
        }));
        serviceRow.addView(tonalButton("Overlay", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                toast("Could not open overlay settings");
            }
        }));
        serviceRow.addView(tonalButton("Battery", v -> openBatterySettings()));
        serviceRow.addView(tonalButton("Accessibility", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("Turn on 奈何桥 in the list");
            } catch (Throwable t) {
                toast("Could not open accessibility settings");
            }
        }));
        statusContent.addView(serviceRow);

        statusContent.addView(section("CONFIRMATION POLICY"));
        statusContent.addView(body("Root shell commands always prompt and cannot be turned off."
                + " The others can be relaxed, because they use the module's platform access"
                + " rather than a shell."));
        statusContent.addView(toggle("Confirm screen capture and UI dumps", prefs.confirmScreen(),
                checked -> prefs.setConfirm("confirm_screen", checked)));
        statusContent.addView(toggle("Confirm injected input", prefs.confirmInput(),
                checked -> prefs.setConfirm("confirm_input", checked)));
        statusContent.addView(toggle("Confirm plugin loading and calls", prefs.confirmPlugin(),
                checked -> prefs.setConfirm("confirm_plugin", checked)));
        statusContent.addView(toggle("Start automatically after reboot", prefs.autostart(),
                checked -> prefs.setAutostart(checked)));

        statusContent.addView(section("HAND-OFF MODE"));
        renderHandoff();

        statusContent.addView(section("TOOLS"));
        statusContent.addView(body("Read-only tools never prompt. Everything else asks the user"
                + " before it runs."));
        statusContent.addView(monoBlock(toolSummary()));
    }

    /** Read off the registry rather than kept as a second list that goes stale. */
    private String toolSummary() {
        McpService service = McpService.instance();
        List<McpTool> tools = service == null ? Collections.emptyList() : service.tools();
        if (tools.isEmpty()) {
            return "(the service is not running)";
        }
        StringBuilder sb = new StringBuilder();
        for (McpTool tool : tools) {
            String kind = tool.readOnly ? "read-only"
                    : ("root_shell_exec".equals(tool.name) ? "ALWAYS prompts" : "prompts");
            sb.append(pad(tool.name, 20)).append(kind).append('\n');
        }
        return sb.toString().trim();
    }

    // ---- hand-off mode -----------------------------------------------------

    /**
     * The one control that removes the gate, and it is built to be hard to open.
     *
     * <p>Everything else in this app is a setting; this is the user standing up
     * and saying "I will not be reading these for a while". So it is not a
     * switch that can be brushed in a pocket: three dialogs that each name a
     * different consequence, then a word to type. Past that it still expires on
     * its own, because the expensive failure here is not opening it - it is
     * forgetting it is open.
     */
    private void renderHandoff() {
        if (prefs.handoffRemainingMs() > 0L) {
            handoffStatus = title(handoffLine());
            handoffStatus.setTextColor(color(androidx.appcompat.R.attr.colorError));
            statusContent.addView(handoffStatus);
            statusContent.addView(body("Every tool the agent calls is running the moment it is"
                    + " asked for, with nothing checking it first - root shell commands"
                    + " included. It switches itself off when the time runs out, and a reboot"
                    + " ends it early. This app being killed and restarted does not - this ROM"
                    + " does that on its own, and losing the window to a memory sweep would help"
                    + " nobody."));

            LinearLayout actions = row();
            actions.addView(tonalButton("Extend " + (Prefs.HANDOFF_EXTEND_MS / 60_000L) + " min",
                    v -> extendHandoff()));
            actions.addView(filledButton("Turn off now", v -> turnOffHandoff()));
            statusContent.addView(actions);

            ui.removeCallbacks(handoffTick);
            ui.postDelayed(handoffTick, 500L);
            return;
        }

        handoffStatus = null;
        statusContent.addView(body("The approval dialog is the only thing standing between the"
                + " agent and this device. Hand-off mode takes it away: for a while, every"
                + " action runs the moment it is asked for."));
        statusContent.addView(body("It is for the case where you are deliberately letting the"
                + " agent work through a long sequence without you. It is also the mode that can"
                + " leave the phone unbootable, so it expires by itself and there is no way to"
                + " make it stick - only to arm it again."));
        statusContent.addView(outlinedButton("Arm hand-off mode…", v -> handoffWarningOne()));
    }

    private String handoffLine() {
        long remaining = Math.max(0L, prefs.handoffRemainingMs());
        long minutes = remaining / 60_000L;
        long seconds = (remaining % 60_000L) / 1000L;
        return "ON — " + minutes + ":" + (seconds < 10 ? "0" : "") + seconds + " left";
    }

    private void handoffWarningOne() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Hand-off mode: no more prompts")
                .setMessage("While this is on, everything the agent calls runs immediately."
                        + " Root shell commands, injected taps and text, code loaded into other"
                        + " apps, screenshots of whatever is on screen - none of it will ask you"
                        + " first.")
                .setPositiveButton("I understand", (d, w) -> handoffWarningTwo())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handoffWarningTwo() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("That includes root")
                .setMessage("The root shell is the one thing that could never be switched off,"
                        + " on purpose - it is why this project exists at all. Hand-off mode"
                        + " covers it too.")
                .setPositiveButton("I understand", (d, w) -> handoffWarningThree())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handoffWarningThree() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("This can break the phone")
                .setMessage("A command that deletes files, disables a system component or"
                        + " writes to a partition will run exactly as the agent typed it, with"
                        + " nothing in between. That can leave this device unable to boot, and"
                        + " there is no undo - the audit log records what ran, but it cannot put"
                        + " anything back.")
                .setPositiveButton("I understand", (d, w) -> handoffTypedConfirmation())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handoffTypedConfirmation() {
        LinearLayout box = column();
        box.setPadding(dp(24), 0, dp(24), 0);
        box.addView(body("Type " + HANDOFF_WORD + " below to enable the button. A pocket, a"
                + " mis-tap or a stray touch cannot do this - only you, reading this, can."));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(HANDOFF_WORD);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = dp(12);
        input.setPadding(pad, pad, pad, pad);
        box.addView(input);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("Type " + HANDOFF_WORD + " to arm it")
                .setView(box)
                .setPositiveButton("Arm for " + (Prefs.HANDOFF_DEFAULT_MS / 60_000L) + " min",
                        (d, w) -> armHandoff())
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(shown -> {
            Button arm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            arm.setEnabled(false);
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    arm.setEnabled(HANDOFF_WORD.equalsIgnoreCase(s.toString().trim()));
                }
            });
        });
        dialog.show();
    }

    private void armHandoff() {
        McpService service = McpService.instance();
        if (service == null || !service.isRunning()) {
            toast("Start the service first");
            return;
        }
        service.armHandoff(Prefs.HANDOFF_DEFAULT_MS);
        toast("Hand-off armed for " + (Prefs.HANDOFF_DEFAULT_MS / 60_000L) + " minutes");
        renderStatus();
    }

    private void extendHandoff() {
        McpService service = McpService.instance();
        if (service == null || !service.isRunning()) {
            toast("Start the service first");
            return;
        }
        long total = Math.max(0L, prefs.handoffRemainingMs()) + Prefs.HANDOFF_EXTEND_MS;
        service.armHandoff(total);
        toast("Extended - " + (total / 60_000L) + " minutes left");
        renderStatus();
    }

    private void turnOffHandoff() {
        McpService service = McpService.instance();
        if (service != null && service.isRunning()) {
            service.disarmHandoff();
        } else {
            prefs.clearHandoff();
        }
        toast("Hand-off off - prompts are back");
        renderStatus();
    }

    // ---- scripts tab -------------------------------------------------------

    private void renderScripts() {
        scriptsContent.removeAllViews();
        scriptsContent.addView(headline("Saved scripts"));
        scriptsContent.addView(body("Scripts the agent filed for you. Running one from here is"
                + " your own tap, so it runs without an approval prompt. The result of the last"
                + " run is kept under each script."));

        List<SavedScript> scripts = ScriptStore.of(this).all();
        if (scripts.isEmpty()) {
            scriptsContent.addView(section("NOTHING SAVED YET"));
            scriptsContent.addView(body("Ask the agent to save a script and it appears here,"
                    + " with a line saying what it does."));
            return;
        }

        scriptsContent.addView(section(scripts.size() + (scripts.size() == 1
                ? " SCRIPT" : " SCRIPTS")));
        for (SavedScript script : scripts) {
            scriptsContent.addView(scriptCard(script));
        }
    }

    private View scriptCard(SavedScript script) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(12);
        card.setLayoutParams(cardParams);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        inner.setPadding(pad, pad, pad, dp(8));

        inner.addView(title(script.name));
        inner.addView(caption("in " + script.packageName));
        if (script.effect != null && !script.effect.isEmpty()) {
            inner.addView(body(script.effect));
        }
        TextView last = caption(lastRunLine(script));
        last.setTypeface(Typeface.MONOSPACE);
        inner.addView(last);

        LinearLayout actions = row();
        actions.addView(filledButton("Run", v -> runScript(script)));
        actions.addView(tonalButton("Open", v -> showSource(script)));
        actions.addView(outlinedButton("Delete", v -> confirmDelete(script)));
        inner.addView(actions);

        card.addView(inner);
        return card;
    }

    private String lastRunLine(SavedScript script) {
        if (script.lastRunAt == 0) {
            return "never run";
        }
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(script.lastRunAt));
        String outcome = script.lastOutcome == null || script.lastOutcome.isEmpty()
                ? "" : "\n" + script.lastOutcome;
        return (script.lastRunOk ? "ok  " : "FAILED  ") + when + outcome;
    }

    private void runScript(SavedScript script) {
        McpService service = McpService.instance();
        if (service == null || !service.isRunning()) {
            toast("Start the service first");
            return;
        }
        // Running a script means bringing its app forward, which puts this one in
        // the background. Without the accessibility service the platform freezes
        // it there within seconds, mid-request, and the run never finishes - so
        // say why rather than let it fail in a way that looks like the script.
        if (!AccessibilityBridge.isConnected()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Accessibility is off")
                    .setMessage("Running a script brings its app to the front, which puts this"
                            + " one in the background. Without the accessibility service the"
                            + " system freezes this app there and the run never finishes."
                            + " Turn it on, then run again.")
                    .setPositiveButton("Accessibility settings", (dialog, which) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Throwable t) {
                            toast("Could not open accessibility settings");
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        toast("Running " + script.name);
        // The bridge call blocks until the script finishes, so it cannot be on
        // the thread drawing this screen. Bringing the target forward happens on
        // this thread because starting an activity has to.
        new Thread(() -> {
            boolean ok;
            String summary;
            try {
                bringToFront(script.packageName);
                JSONObject result = service.runScript(script.packageName, script.source,
                        LuaRuntime.DEFAULT_MAX_INSTRUCTIONS);
                ok = result.optBoolean("ok", false);
                summary = summarize(result);
            } catch (Throwable t) {
                ok = false;
                summary = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            ScriptStore.of(this).recordRun(script.id, ok, trim(summary, 400));
            runOnUiThread(this::renderScripts);
        }, "posedmcp-script-run").start();
    }

    /**
     * Starts the target app and waits for its module to answer.
     *
     * <p>A backgrounded app is frozen within seconds on this ROM and a frozen
     * process does not answer the bridge, so a script that ran anyway would just
     * time out. Cold-starting it also takes a moment before the module inside it
     * connects, which is what the wait is for.
     */
    private void bringToFront(String pkg) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                startActivity(intent);
            } catch (Throwable t) {
                Logx.w("could not bring " + pkg + " to the front: " + t);
            }
        }
        McpService service = McpService.instance();
        // A cold start has to fork the process, run the app's own startup and
        // then let the module inside it connect, which on this device takes
        // longer than it looks - the first attempt at six seconds was not enough.
        for (int i = 0; i < 60; i++) {
            if (service == null || service.hasPeer(pkg)) {
                break;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Even with the module already attached, the window needs a beat to come
        // up and for the platform to unfreeze the process behind it.
        try {
            Thread.sleep(700L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** What the user needs to see: the value or the text it printed, or why it failed. */
    private static String summarize(JSONObject result) {
        String output = result.optString("output", "");
        if (!result.optBoolean("ok", false)) {
            String error = result.optString("error", "unknown error");
            return output.isEmpty() ? error : error + "\n" + output;
        }
        Object returned = result.opt("returned");
        String value = returned == null || returned == JSONObject.NULL
                ? "" : String.valueOf(returned);
        if (value.isEmpty()) {
            return output.isEmpty() ? "(finished, no output)" : output;
        }
        return output.isEmpty() ? value : value + "\n" + output;
    }

    private void showSource(SavedScript script) {
        TextView body = text(script.source);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(12);
        body.setTextIsSelectable(true);
        int pad = dp(20);
        body.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);

        new MaterialAlertDialogBuilder(this)
                .setTitle(script.name)
                .setView(scroll)
                .setPositiveButton("Close", null)
                .setNeutralButton("Run", (dialog, which) -> runScript(script))
                .show();
    }

    private void confirmDelete(SavedScript script) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + script.name + "?")
                .setMessage("It is removed from the library. This cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    ScriptStore.of(this).delete(script.id);
                    renderScripts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- hooks tab ---------------------------------------------------------

    private void renderHooks() {
        hooksContent.removeAllViews();
        hooksContent.addView(headline("Kept hooks"));
        hooksContent.addView(body("Hooks the agent registered and you kept. Unlike a hook you"
                + " watch for a minute, these are put back into the app automatically every time"
                + " it starts - so this page is where you see what is running without asking"
                + " again, and where you stop it. Switching one off or deleting it reaches into"
                + " the app that is running now, not just the record."));

        // Stated before the list, because the answer to "why is nothing
        // happening" may be that the rescue module switched these off.
        String suspension = HookGuard.suspension(this);
        if (!suspension.isEmpty()) {
            hooksContent.addView(hookSuspensionNotice(suspension));
        } else {
            hooksContent.addView(caption(HookGuard.guardInstalled()
                    ? "Rescue module: installed. If a system hook ever stops the device"
                            + " finishing boot, it suspends them on its own."
                    : "Rescue module: not installed. Nothing but recovery would stop a system"
                            + " hook that leaves the phone unable to boot."));
        }

        HookStore store = HookStore.of(this);
        List<String> packages = store.packages();
        if (packages.isEmpty()) {
            hooksContent.addView(section("NOTHING REGISTERED YET"));
            hooksContent.addView(body("Ask the agent to hook a method and it appears here,"
                    + " grouped by the application it lives in."));
            return;
        }

        hooksContent.addView(section(packages.size() + (packages.size() == 1 ? " APP" : " APPS")));
        for (String pkg : packages) {
            hooksContent.addView(hookCard(pkg, store.forPackage(pkg)));
        }
    }

    private View hookCard(String pkg, List<SavedHook> hooks) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(12);
        card.setLayoutParams(cardParams);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        inner.setPadding(pad, pad, pad, dp(12));

        boolean expanded = expandedApps.contains(pkg);
        int on = 0;
        for (SavedHook hook : hooks) {
            if (hook.enabled) {
                on++;
            }
        }

        // A Button rather than a clickable TextView: a short synthetic tap is
        // ignored by a plain TextView's click handling on this ROM.
        MaterialButton header = tonalButton((expanded ? "▾  " : "▸  ") + appLabel(pkg)
                + "   ·   " + on + " of " + hooks.size() + " on", v -> {
            if (expandedApps.contains(pkg)) {
                expandedApps.remove(pkg);
            } else {
                expandedApps.add(pkg);
            }
            renderHooks();
        });
        header.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setGravity(Gravity.START);
        inner.addView(header);
        inner.addView(caption(pkg));

        if (expanded) {
            for (SavedHook hook : hooks) {
                inner.addView(hookBlock(hook));
            }
        }

        card.addView(inner);
        return card;
    }

    private View hookBlock(SavedHook hook) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        block.setLayoutParams(lp);

        // What kind of hook this is, and what its body can do. "dex" is the only
        // layer there is; the field is here because the next one is native.
        block.addView(caption(hook.layer.toUpperCase(java.util.Locale.ROOT) + "  ·  "
                + hook.body.toUpperCase(java.util.Locale.ROOT)));
        TextView target = monoBlock(hook.target());
        target.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        target.setTypeface(Typeface.MONOSPACE);
        block.addView(target);
        if (hook.effect != null && !hook.effect.isEmpty()) {
            block.addView(body(hook.effect));
        }
        block.addView(caption(statusLine(hook)));

        LinearLayout actions = row();
        actions.addView(toggle(hook.enabled,
                checked -> setHookEnabled(hook, checked)));
        if (SavedHook.BODY_LUA.equals(hook.body)) {
            actions.addView(tonalButton("Source", v -> showHookSource(hook)));
        }
        actions.addView(outlinedButton("Delete", v -> confirmDeleteHook(hook)));
        block.addView(actions);
        return block;
    }

    private String statusLine(SavedHook hook) {
        if (!hook.enabled) {
            return "off — kept, but not put into the app";
        }
        if (hook.lastError != null && !hook.lastError.isEmpty()) {
            return "FAILED — " + trim(hook.lastError, 200);
        }
        if (hook.lastAppliedAt == 0) {
            return "on — not armed yet; it goes in when the app next starts";
        }
        return "on — last armed " + DateFormat.getDateTimeInstance(DateFormat.SHORT,
                DateFormat.SHORT).format(new Date(hook.lastAppliedAt));
    }

    /**
     * Turns a hook on or off, and makes the running app agree.
     *
     * <p>Off is the one that matters: the point of this page is that the user can
     * stop something that is otherwise re-arming itself, so the switch has to
     * reach into the process rather than only edit a record.
     */
    private void setHookEnabled(SavedHook hook, boolean enabled) {
        HookStore.of(this).setEnabled(hook.id, enabled);
        McpService service = McpService.instance();
        if (service == null || !service.isRunning() || !service.hasPeer(hook.packageName)) {
            toast(enabled
                    ? "Saved. It will be armed when the app next starts."
                    : "Saved. The app is not running, so there was nothing to stop.");
            renderHooks();
            return;
        }

        new Thread(() -> {
            String message;
            try {
                if (enabled) {
                    JSONObject result = service.armHook(hook);
                    int applied = result == null ? 0 : result.optInt("applied", 0);
                    message = applied > 0 ? "Armed in " + applied + " process(es)"
                            : "Saved, but no process took it";
                } else {
                    service.disarmHook(hook);
                    message = "Stopped in the running app";
                }
            } catch (Throwable t) {
                message = "Could not reach the app: " + t.getMessage();
            }
            String finalMessage = message;
            runOnUiThread(() -> {
                toast(finalMessage);
                renderHooks();
            });
        }, "posedmcp-hook-toggle").start();
    }

    private void confirmDeleteHook(SavedHook hook) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete this hook?")
                .setMessage(hook.target() + "\n\nIt is removed from the library and unhooked in"
                        + " the running app. It will not come back when the app restarts.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    HookStore.of(this).delete(hook.id);
                    McpService service = McpService.instance();
                    if (service != null && service.isRunning()
                            && service.hasPeer(hook.packageName)) {
                        new Thread(() -> {
                            try {
                                service.disarmHook(hook);
                            } catch (Throwable t) {
                                Logx.w("could not unhook " + hook.target() + ": " + t);
                            }
                            runOnUiThread(this::renderHooks);
                        }, "posedmcp-hook-delete").start();
                    } else {
                        renderHooks();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * The notice that system hooks have been switched off from outside this app.
     *
     * <p>Shown whether or not the list holds a system hook: the reason someone is
     * on this page may be that something they expected is not running, and this
     * notice is the only thing that would say so.
     */
    private View hookSuspensionNotice(String reason) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(12);
        card.setLayoutParams(cardParams);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        inner.setPadding(pad, pad, pad, dp(12));

        TextView heading = title("System hooks are suspended");
        heading.setTextColor(color(androidx.appcompat.R.attr.colorError));
        inner.addView(heading);
        inner.addView(body("The posedmcp-guard rescue module stopped system hooks being armed,"
                + " because this device failed to finish booting more than once with one in place."
                + " Nothing is being put into system_server until you lift this; the hooks"
                + " themselves are still kept and are listed below."));
        inner.addView(caption(reason));

        LinearLayout actions = row();
        actions.addView(outlinedButton("Lift the suspension", v -> confirmLiftSuspension()));
        inner.addView(actions);

        card.addView(inner);
        return card;
    }

    /**
     * Lifting the suspension puts back whatever may have stopped the device
     * booting, so it is asked about rather than just done - the button is one tap
     * away from a phone that does not come back.
     */
    private void confirmLiftSuspension() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Lift the suspension?")
                .setMessage("System hooks will be armed again when system_server next starts. If"
                        + " one of them is what stopped the phone booting, it may not boot again -"
                        + " delete that hook first if you are not sure.")
                .setPositiveButton("Lift it", (dialog, which) -> new Thread(() -> {
                    String problem = HookGuard.liftSuspension(this);
                    runOnUiThread(() -> {
                        toast(problem == null
                                ? "Lifted. They arm when system_server next restarts."
                                : problem);
                        renderHooks();
                    });
                }, "posedmcp-lift-suspension").start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showHookSource(SavedHook hook) {
        TextView content = text(hook.source);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextSize(12);
        content.setTextIsSelectable(true);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        new MaterialAlertDialogBuilder(this)
                .setTitle(hook.target())
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private String appLabel(String pkg) {
        if (SavedHook.isSystem(pkg)) {
            // It has no application label because it is not an application; left
            // to the fallback below it would be shown as the bare word "android",
            // which tells the user nothing about what they are looking at.
            return "System Framework";
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            String label = String.valueOf(getPackageManager().getApplicationLabel(info));
            return label.equals(pkg) ? pkg : label;
        } catch (Throwable t) {
            return pkg;
        }
    }

    // ---- type scale --------------------------------------------------------

    private TextView headline(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_HeadlineSmall);
        return tv;
    }

    private TextView title(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleMedium);
        return tv;
    }

    private TextView body(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** A monospace block: endpoints, tokens, source, the tool table. */
    private TextView monoBlock(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerHighest));
        int p = dp(12);
        tv.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView caption(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodySmall);
        tv.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView section(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_TitleSmall);
        // colorPrimary is declared by AppCompat; the Material-specific roles
        // (onSurfaceVariant, surfaceContainerHighest) live in Material's R.
        tv.setTextColor(color(androidx.appcompat.R.attr.colorPrimary));
        tv.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView keyValue(String key, String value) {
        TextView tv = new TextView(this);
        tv.setText(key + ":  " + value);
        tv.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        tv.setPadding(0, dp(3), 0, 0);
        return tv;
    }

    private TextView text(String value) {
        TextView tv = new TextView(this);
        tv.setText(value);
        return tv;
    }

    // ---- layout helpers ----------------------------------------------------

    private int color(int attribute) {
        return MaterialColors.getColor(root == null ? getWindow().getDecorView() : root, attribute);
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        column.setPadding(pad, pad, pad, dp(32));
        return column;
    }

    private ScrollView scrolled(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);
        return row;
    }

    private MaterialButton filledButton(String label, View.OnClickListener listener) {
        // The theme's button style is the filled one in Material 3; naming it
        // keeps "primary action" explicit rather than a constructor default.
        return button(label, listener, com.google.android.material.R.attr.materialButtonStyle);
    }

    private MaterialButton tonalButton(String label, View.OnClickListener listener) {
        return button(label, listener,
                com.google.android.material.R.attr.materialButtonTonalStyle);
    }

    private MaterialButton outlinedButton(String label, View.OnClickListener listener) {
        return button(label, listener,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
    }

    private MaterialButton button(String label, View.OnClickListener listener, int style) {
        MaterialButton button = new MaterialButton(this, null, style);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        button.setLayoutParams(lp);
        return button;
    }

    private interface OnChecked {
        void onChecked(boolean checked);
    }

    private View toggle(boolean initial, OnChecked listener) {
        return toggle("", initial, listener);
    }

    private View toggle(String label, boolean initial, OnChecked listener) {
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setText(label);
        toggle.setChecked(initial);
        toggle.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        int pad = dp(6);
        toggle.setPadding(0, pad, 0, pad);
        toggle.setOnCheckedChangeListener((v, checked) -> listener.onChecked(checked));
        return toggle;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private static String pad(String value, int width) {
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String trim(String value, int limit) {
        if (value == null) {
            return "";
        }
        String flat = value.trim();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }

    private void copy(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            toast("Copied");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private boolean isBatteryExempt() {
        try {
            android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void openBatterySettings() {
        // Asking directly is the shortest path; some ROMs refuse it, so fall
        // back to the list the user can pick from.
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            toast("Could not open battery settings");
        }
    }

    /**
     * Switches a faulted accessibility service off and on, which is the only
     * thing that clears it.
     *
     * <p>A button rather than a tool. An agent quietly re-granting itself an
     * accessibility service is the shape of thing the confirmation gate exists
     * to stop, and an agent that thought it needed this could already ask for a
     * root shell and be told no.
     */
    private void repairAccessibility() {
        AccessibilityRepair.Plan plan = AccessibilityRepair.plan(this);
        if (plan == null) {
            toast("Could not read the accessibility settings");
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Repair the accessibility service?")
                .setMessage("This switches it off and on again. That is the only thing that"
                        + " clears the system's \"malfunctioning\" mark - the switch in Settings"
                        + " cannot, because it already reads as on.\n\nTwo root commands, and"
                        + " nothing else runs:\n\n" + plan.commandWithout() + "\n\n"
                        + plan.commandFull()
                        + "\n\nEvery other accessibility service in that list is carried through"
                        + " unchanged.")
                .setPositiveButton("Repair", (dialog, which) -> runAccessibilityRepair(plan))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runAccessibilityRepair(AccessibilityRepair.Plan plan) {
        toast("Repairing…");
        new Thread(() -> {
            String failure = AccessibilityRepair.apply(plan);
            try {
                // Let the framework bind it before the page is redrawn, so what
                // it shows is the result rather than the state it was in.
                Thread.sleep(1_500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runOnUiThread(() -> {
                toast(failure == null ? "Accessibility repaired" : failure);
                renderStatus();
            });
        }, "posedmcp-a11y-repair").start();
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }
}
