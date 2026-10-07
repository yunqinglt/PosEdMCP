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
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
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
import dev.posedmcp.root.ProbeWindow;
import dev.posedmcp.state.HookGuard;
import dev.posedmcp.state.HookStore;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.state.SavedHook;
import dev.posedmcp.state.SavedScript;
import dev.posedmcp.state.ScriptStore;
import dev.posedmcp.tools.SpecialHooks;
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
        tabs.addTab(tabs.newTab().setText(R.string.tab_status));
        tabs.addTab(tabs.newTab().setText(R.string.tab_scripts));
        tabs.addTab(tabs.newTab().setText(R.string.tab_hooks));
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
        MenuItem more = toolbar.getMenu().findItem(R.id.action_more);
        if (more != null && more.getIcon() != null) {
            more.getIcon().setTint(color(com.google.android.material.R.attr.colorOnSurface));
        }
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_more) {
                showMoreInfo();
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

    // ---- more info ---------------------------------------------------------

    /**
     * The two things behind the toolbar button, in a sheet.
     *
     * <p>A sheet rather than two toolbar icons, because the two are not alike.
     * About is read once and forgotten; Special settings can stop the system
     * killing this app, and that is a control that should take a deliberate step
     * to reach rather than sit one tap away on the toolbar.
     */
    private void showMoreInfo() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(12));
        content.addView(sheetRow(R.string.about, sheet, this::showAbout));
        content.addView(sheetRow(R.string.special_settings, sheet, this::showSpecialSettings));
        sheet.setContentView(content);
        sheet.show();
    }

    private View sheetRow(int labelRes, BottomSheetDialog sheet, Runnable action) {
        TextView row = new TextView(this);
        row.setText(labelRes);
        row.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        row.setPadding(dp(24), dp(18), dp(24), dp(18));
        row.setClickable(true);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(v -> {
            sheet.dismiss();
            action.run();
        });
        return row;
    }

    // ---- special settings --------------------------------------------------

    /** Kept so a change can replace the dialog it was made in. */
    private AlertDialog specialDialog;

    /**
     * The countermeasures that ship inside this APK.
     *
     * <p>What is offered depends on the phone: each one is written against a
     * particular manufacturer's background-app killer, and a switch for a killer
     * this phone does not have would be a switch that does nothing. So this asks
     * whether the component the countermeasure is about is installed, rather
     * than trying to read a ROM name out of a property.
     */
    private void showSpecialSettings() {
        if (specialDialog != null) {
            specialDialog.dismiss();
            specialDialog = null;
        }

        LinearLayout content = column();
        content.addView(body(getString(R.string.special_intro)));

        List<SpecialHooks.Spec> specs = SpecialHooks.available(this);
        if (specs.isEmpty()) {
            content.addView(section(getString(R.string.special_settings)));
            content.addView(body(getString(R.string.special_none)));
        } else {
            for (SpecialHooks.Spec spec : specs) {
                content.addView(specialCard(spec));
            }
        }

        content.addView(section(getString(R.string.probe_section)));
        content.addView(body(getString(R.string.probe_intro)));
        content.addView(probeCard());

        specialDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.special_settings)
                .setView(scrolled(content))
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    /**
     * One countermeasure: what it does, whether it is on, and the one control.
     *
     * <p>There is no delete here, and the Hooks page does not show these either.
     * Turning one off leaves it in the library and out of every process; a
     * countermeasure somebody can remove by accident is one they then have to
     * remember how to put back.
     */
    private View specialCard(SpecialHooks.Spec spec) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(16);
        card.setLayoutParams(cardParams);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        inner.setPadding(pad, pad, pad, dp(12));

        inner.addView(title(getString(spec.titleRes)));
        inner.addView(body(getString(spec.blurbRes)));

        SavedHook stored = SpecialHooks.stored(this, spec);
        boolean on = stored != null && stored.enabled;
        String state = stored == null ? getString(R.string.special_state_never)
                : getString(on ? R.string.special_state_on : R.string.special_state_off);
        inner.addView(caption(state));

        MaterialButton action = tonalButton(
                getString(on ? R.string.special_pause
                        : stored == null ? R.string.special_arm_confirm
                                : R.string.special_resume),
                v -> {
                    if (on) {
                        setSpecial(spec, false);
                    } else {
                        confirmSpecial(spec);
                    }
                });
        inner.addView(action);

        card.addView(inner);
        return card;
    }

    /**
     * What the user has to have done before this switch can mean anything.
     *
     * <p>Said here rather than in the blurb because it is the part that is easy
     * to skip: the hook lives in the system framework, so the module has to be
     * scoped to that process and the phone has to have been restarted since.
     * Otherwise the switch reads "on" while nothing is hooked, which is the
     * worst of both answers.
     */
    private void confirmSpecial(SpecialHooks.Spec spec) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.special_arm_title)
                .setMessage(getString(R.string.special_arm_body,
                        getString(R.string.special_scope_system)))
                .setNegativeButton(R.string.special_arm_cancel, null)
                .setPositiveButton(R.string.special_arm_confirm, (d, w) -> setSpecial(spec, true))
                .show();
    }

    private void setSpecial(SpecialHooks.Spec spec, boolean on) {
        McpService service = McpService.instance();
        boolean running = service != null && service.isRunning();

        if (on) {
            SavedHook hook = SpecialHooks.turnOn(this, spec);
            if (hook == null) {
                toast(getString(R.string.toast_special_missing));
                return;
            }
            // Into the processes that are up right now: a switch that only took
            // effect at the next restart would be describing a state it is not in.
            if (running) {
                service.armHook(hook);
            }
            toast(getString(R.string.toast_special_on));
        } else {
            SavedHook hook = SpecialHooks.turnOff(this, spec);
            if (hook != null && running) {
                service.disarmHook(hook);
            }
            toast(getString(R.string.toast_special_off));
        }
        renderHooks();
        showSpecialSettings();
    }

    /**
     * The manual probe: the floating button that freezes the app in front.
     *
     * <p>Everything about starting a freeze lives behind that button - nothing
     * on this card, and no tool, can start one. The agent's side of the probe
     * (reading the frozen state, asking for the release) is a separate matter;
     * this card is the user's side only.
     */
    private View probeCard() {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardElevation(dp(1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = dp(16);
        card.setLayoutParams(cardParams);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        inner.setPadding(pad, pad, pad, pad);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText(R.string.probe_window_label);
        label.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyLarge);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setChecked(Prefs.of(this).probeWindowEnabled());
        toggle.setOnCheckedChangeListener((v, on) -> {
            if (on && !Settings.canDrawOverlays(this)) {
                // The window cannot exist without this; offering the switch as
                // if it could would be describing a state the phone is not in.
                toast(getString(R.string.toast_probe_refused_overlay));
                ((MaterialSwitch) v).setChecked(false);
                return;
            }
            Prefs.of(this).setProbeWindowEnabled(on);
            if (on) {
                ProbeWindow.show(this);
            } else {
                ProbeWindow.dismiss();
            }
        });
        row.addView(toggle);
        inner.addView(row);

        inner.addView(caption(getString(R.string.probe_duration_label)));

        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(this);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        int[] seconds = Prefs.PROBE_FREEZE_CHOICES;
        int[] labels = {R.string.probe_duration_30s, R.string.probe_duration_60s,
                R.string.probe_duration_300s};
        for (int i = 0; i < seconds.length; i++) {
            MaterialButton choice = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            choice.setText(labels[i]);
            choice.setAllCaps(false);
            group.addView(choice, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (Prefs.of(this).probeFreezeSeconds() == seconds[i]) {
                choice.setChecked(true);
            }
            final int secs = seconds[i];
            choice.setOnClickListener(v -> Prefs.of(this).setProbeFreezeSeconds(secs));
        }
        inner.addView(group, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        card.addView(inner);
        return card;
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

        TextView heading = section(getString(R.string.about_framework));
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
                .setPositiveButton(R.string.action_close, null)
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
            return getString(R.string.about_not_running);
        }
        String reported = service.frameworkReport();
        return reported.isEmpty() ? getString(R.string.about_not_reported) : reported;
    }

    private String frameworkSource() {
        McpService service = McpService.instance();
        String from = service == null ? "" : service.frameworkFrom();
        if (!from.isEmpty()) {
            return getString(R.string.about_reported_by, from);
        }
        return getString(R.string.about_start_an_app);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast(getString(R.string.toast_no_app_for_url, url));
        }
    }

    // ---- status tab --------------------------------------------------------

    private void renderStatus() {
        statusContent.removeAllViews();

        McpService service = McpService.instance();
        boolean running = service != null && service.isRunning();

        statusContent.addView(section(getString(R.string.section_status)));
        statusContent.addView(keyValue(getString(R.string.label_service),
                getString(running ? R.string.value_running : R.string.value_stopped)));
        if (running) {
            statusContent.addView(keyValue(getString(R.string.label_mcp_endpoint),
                    "http://127.0.0.1:" + service.mcpPort() + "/mcp"));
            statusContent.addView(keyValue(getString(R.string.label_bridge_port),
                    String.valueOf(service.bridgePort())));
            statusContent.addView(keyValue(getString(R.string.label_system_bridge),
                    getString(service.systemBridgeConnected()
                            ? R.string.value_connected : R.string.value_offline)));
            statusContent.addView(keyValue(getString(R.string.label_module_processes),
                    getString(R.string.value_processes_connected, service.connectedPeers())));
        }
        statusContent.addView(keyValue(getString(R.string.label_overlay_permission),
                getString(Settings.canDrawOverlays(this)
                        ? R.string.value_granted : R.string.value_not_granted)));
        statusContent.addView(keyValue(getString(R.string.label_battery),
                getString(isBatteryExempt()
                        ? R.string.value_battery_unrestricted : R.string.value_battery_optimised)));
        String a11y = AccessibilityBridge.state(this);
        boolean a11yOn = AccessibilityBridge.STATE_ON.equals(a11y);
        boolean a11yConnecting = AccessibilityBridge.STATE_CONNECTING.equals(a11y);
        boolean a11yStuck = AccessibilityBridge.STATE_FAULTED.equals(a11y);
        statusContent.addView(keyValue(getString(R.string.label_accessibility),
                getString(a11yOn ? R.string.value_a11y_enabled
                        : a11yConnecting ? R.string.value_a11y_connecting
                                : a11yStuck ? R.string.value_a11y_stuck
                                        : R.string.value_a11y_off)));

        if (a11yStuck) {
            // The distinction matters more than it looks. From the app's side this
            // is identical to "off", and the obvious fix - turning it on in
            // Settings - does nothing, because the setting already reads as on.
            statusContent.addView(body(getString(R.string.body_a11y_stuck)));
            statusContent.addView(outlinedButton(getString(R.string.action_repair_accessibility),
                    v -> repairAccessibility()));
        } else if (a11yConnecting) {
            statusContent.addView(body(getString(R.string.body_a11y_connecting)));
            // Look again once the grace period is up, so a real fault is not
            // hidden behind a snapshot taken too early.
            ui.removeCallbacks(a11yRecheck);
            ui.postDelayed(a11yRecheck, 11_000L);
        } else if (!a11yOn) {
            statusContent.addView(body(getString(R.string.body_a11y_off)));
        } else {
            statusContent.addView(body(getString(R.string.body_a11y_on)));
        }
        if (!isBatteryExempt()) {
            statusContent.addView(body(getString(R.string.body_battery_optimisation)));
        }
        statusContent.addView(body(getString(R.string.body_overlay_permission)));

        statusContent.addView(section(getString(R.string.section_endpoint)));
        final String url = "http://127.0.0.1:" + prefs.mcpPort() + "/mcp";
        statusContent.addView(monoBlock(url));
        statusContent.addView(caption(getString(R.string.label_bearer_token)));
        statusContent.addView(monoBlock(prefs.mcpToken()));

        LinearLayout tokenRow = row();
        tokenRow.addView(tonalButton(getString(R.string.action_copy_url),
                v -> copy(getString(R.string.clip_url), url)));
        tokenRow.addView(tonalButton(getString(R.string.action_copy_token),
                v -> copy(getString(R.string.clip_token), prefs.mcpToken())));
        tokenRow.addView(outlinedButton(getString(R.string.action_rotate), v -> {
            prefs.rotateTokens();
            if (McpService.instance() != null) {
                McpService.stop(this);
                McpService.start(this);
            }
            toast(getString(R.string.toast_tokens_rotated));
            renderStatus();
        }));
        statusContent.addView(tokenRow);

        statusContent.addView(body(getString(R.string.body_endpoint_localhost, prefs.mcpPort())));

        statusContent.addView(section(getString(R.string.section_actions)));
        MaterialButton serviceButton = filledButton(getString(running
                        ? R.string.action_stop_service : R.string.action_start_service), v -> {
            if (McpService.instance() != null && McpService.instance().isRunning()) {
                McpService.stop(this);
            } else {
                McpService.start(this);
            }
            statusContent.postDelayed(this::renderStatus, 600L);
        });
        MaterialButton overlayButton = tonalButton(getString(R.string.action_overlay), v -> {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (Throwable t) {
                toast(getString(R.string.toast_overlay_settings_failed));
            }
        });
        MaterialButton batteryButton = tonalButton(getString(R.string.action_battery),
                v -> openBatterySettings());
        MaterialButton accessibilityButton = tonalButton(getString(R.string.action_accessibility),
                v -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        toast(getString(R.string.toast_turn_on_in_list));
                    } catch (Throwable t) {
                        toast(getString(R.string.toast_a11y_settings_failed));
                    }
                });
        statusContent.addView(buttonGridRow(serviceButton, overlayButton));
        statusContent.addView(buttonGridRow(batteryButton, accessibilityButton));

        statusContent.addView(section(getString(R.string.section_confirmation_policy)));
        statusContent.addView(body(getString(R.string.body_confirmation_policy)));
        statusContent.addView(toggle(getString(R.string.toggle_confirm_screen), prefs.confirmScreen(),
                checked -> prefs.setConfirm("confirm_screen", checked)));
        statusContent.addView(toggle(getString(R.string.toggle_confirm_input), prefs.confirmInput(),
                checked -> prefs.setConfirm("confirm_input", checked)));
        statusContent.addView(toggle(getString(R.string.toggle_confirm_plugin), prefs.confirmPlugin(),
                checked -> prefs.setConfirm("confirm_plugin", checked)));
        statusContent.addView(toggle(getString(R.string.toggle_autostart), prefs.autostart(),
                checked -> prefs.setAutostart(checked)));

        statusContent.addView(section(getString(R.string.section_handoff)));
        renderHandoff();

        statusContent.addView(section(getString(R.string.section_tools)));
        statusContent.addView(body(getString(R.string.body_tools)));
        statusContent.addView(monoBlock(toolSummary()));
    }

    /** Read off the registry rather than kept as a second list that goes stale. */
    private String toolSummary() {
        McpService service = McpService.instance();
        List<McpTool> tools = service == null ? Collections.emptyList() : service.tools();
        if (tools.isEmpty()) {
            return getString(R.string.tools_service_not_running);
        }
        StringBuilder sb = new StringBuilder();
        for (McpTool tool : tools) {
            String kind = tool.readOnly ? getString(R.string.tools_read_only)
                    : getString("root_shell_exec".equals(tool.name)
                            ? R.string.tools_always_prompts : R.string.tools_prompts);
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
            statusContent.addView(body(getString(R.string.body_handoff_on)));

            LinearLayout actions = row();
            actions.addView(tonalButton(
                    getString(R.string.action_extend_minutes, Prefs.HANDOFF_EXTEND_MS / 60_000L),
                    v -> extendHandoff()));
            actions.addView(filledButton(getString(R.string.action_turn_off_now),
                    v -> turnOffHandoff()));
            statusContent.addView(actions);

            ui.removeCallbacks(handoffTick);
            ui.postDelayed(handoffTick, 500L);
            return;
        }

        handoffStatus = null;
        statusContent.addView(body(getString(R.string.body_handoff_intro)));
        statusContent.addView(body(getString(R.string.body_handoff_warning)));
        statusContent.addView(outlinedButton(getString(R.string.action_arm_handoff),
                v -> handoffWarningOne()));
    }

    private String handoffLine() {
        long remaining = Math.max(0L, prefs.handoffRemainingMs());
        long minutes = remaining / 60_000L;
        long seconds = (remaining % 60_000L) / 1000L;
        return getString(R.string.handoff_line, minutes, (seconds < 10 ? "0" : "") + seconds);
    }

    private void handoffWarningOne() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.handoff_warn1_title)
                .setMessage(R.string.handoff_warn1_body)
                .setPositiveButton(R.string.action_understand, (d, w) -> handoffWarningTwo())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void handoffWarningTwo() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.handoff_warn2_title)
                .setMessage(R.string.handoff_warn2_body)
                .setPositiveButton(R.string.action_understand, (d, w) -> handoffWarningThree())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void handoffWarningThree() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.handoff_warn3_title)
                .setMessage(R.string.handoff_warn3_body)
                .setPositiveButton(R.string.action_understand, (d, w) -> handoffTypedConfirmation())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void handoffTypedConfirmation() {
        LinearLayout box = column();
        box.setPadding(dp(24), 0, dp(24), 0);
        box.addView(body(getString(R.string.handoff_type_body, HANDOFF_WORD)));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(HANDOFF_WORD);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        int pad = dp(12);
        input.setPadding(pad, pad, pad, pad);
        box.addView(input);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.handoff_type_title, HANDOFF_WORD))
                .setView(box)
                .setPositiveButton(getString(R.string.action_arm_minutes,
                                Prefs.HANDOFF_DEFAULT_MS / 60_000L),
                        (d, w) -> armHandoff())
                .setNegativeButton(R.string.action_cancel, null)
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
            toast(getString(R.string.toast_start_service_first));
            return;
        }
        service.armHandoff(Prefs.HANDOFF_DEFAULT_MS);
        toast(getString(R.string.toast_handoff_armed, Prefs.HANDOFF_DEFAULT_MS / 60_000L));
        renderStatus();
    }

    private void extendHandoff() {
        McpService service = McpService.instance();
        if (service == null || !service.isRunning()) {
            toast(getString(R.string.toast_start_service_first));
            return;
        }
        long total = Math.max(0L, prefs.handoffRemainingMs()) + Prefs.HANDOFF_EXTEND_MS;
        service.armHandoff(total);
        toast(getString(R.string.toast_handoff_extended, total / 60_000L));
        renderStatus();
    }

    private void turnOffHandoff() {
        McpService service = McpService.instance();
        if (service != null && service.isRunning()) {
            service.disarmHandoff();
        } else {
            prefs.clearHandoff();
        }
        toast(getString(R.string.toast_handoff_off));
        renderStatus();
    }

    // ---- scripts tab -------------------------------------------------------

    private void renderScripts() {
        scriptsContent.removeAllViews();
        scriptsContent.addView(headline(getString(R.string.scripts_headline)));
        scriptsContent.addView(body(getString(R.string.scripts_intro)));

        List<SavedScript> scripts = ScriptStore.of(this).all();
        if (scripts.isEmpty()) {
            scriptsContent.addView(section(getString(R.string.section_nothing_saved)));
            scriptsContent.addView(body(getString(R.string.scripts_empty_body)));
            return;
        }

        scriptsContent.addView(section(getResources().getQuantityString(
                R.plurals.scripts_count, scripts.size(), scripts.size())));
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
        inner.addView(caption(getString(R.string.script_in, script.packageName)));
        if (script.effect != null && !script.effect.isEmpty()) {
            inner.addView(body(script.effect));
        }
        TextView last = caption(lastRunLine(script));
        last.setTypeface(Typeface.MONOSPACE);
        inner.addView(last);

        LinearLayout actions = row();
        actions.addView(filledButton(getString(R.string.action_run), v -> runScript(script)));
        actions.addView(tonalButton(getString(R.string.action_open), v -> showSource(script)));
        actions.addView(outlinedButton(getString(R.string.action_delete), v -> confirmDelete(script)));
        inner.addView(actions);

        card.addView(inner);
        return card;
    }

    private String lastRunLine(SavedScript script) {
        if (script.lastRunAt == 0) {
            return getString(R.string.script_never_run);
        }
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(script.lastRunAt));
        String outcome = script.lastOutcome == null || script.lastOutcome.isEmpty()
                ? "" : "\n" + script.lastOutcome;
        return getString(script.lastRunOk
                ? R.string.script_outcome_ok : R.string.script_outcome_failed, when, outcome);
    }

    private void runScript(SavedScript script) {
        McpService service = McpService.instance();
        if (service == null || !service.isRunning()) {
            toast(getString(R.string.toast_start_service_first));
            return;
        }
        // Running a script means bringing its app forward, which puts this one in
        // the background. Without the accessibility service the platform freezes
        // it there within seconds, mid-request, and the run never finishes - so
        // say why rather than let it fail in a way that looks like the script.
        if (!AccessibilityBridge.isConnected()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_a11y_off_title)
                    .setMessage(R.string.dialog_a11y_off_body)
                    .setPositiveButton(R.string.action_accessibility_settings, (dialog, which) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Throwable t) {
                            toast(getString(R.string.toast_a11y_settings_failed));
                        }
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }

        toast(getString(R.string.toast_running_script, script.name));
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
    private String summarize(JSONObject result) {
        String output = result.optString("output", "");
        if (!result.optBoolean("ok", false)) {
            String error = result.optString("error", "unknown error");
            return output.isEmpty() ? error : error + "\n" + output;
        }
        Object returned = result.opt("returned");
        String value = returned == null || returned == JSONObject.NULL
                ? "" : String.valueOf(returned);
        if (value.isEmpty()) {
            return output.isEmpty() ? getString(R.string.script_no_output) : output;
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
                .setPositiveButton(R.string.action_close, null)
                .setNeutralButton(R.string.action_run, (dialog, which) -> runScript(script))
                .show();
    }

    private void confirmDelete(SavedScript script) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.dialog_delete_script_title, script.name))
                .setMessage(R.string.dialog_delete_script_body)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    ScriptStore.of(this).delete(script.id);
                    renderScripts();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ---- hooks tab ---------------------------------------------------------

    private void renderHooks() {
        hooksContent.removeAllViews();
        hooksContent.addView(headline(getString(R.string.hooks_headline)));
        hooksContent.addView(body(getString(R.string.hooks_intro)));

        // Stated before the list, because the answer to "why is nothing
        // happening" may be that the rescue module switched these off.
        String suspension = HookGuard.suspension(this);
        if (!suspension.isEmpty()) {
            hooksContent.addView(hookSuspensionNotice(suspension));
        } else {
            hooksContent.addView(caption(getString(HookGuard.guardInstalled()
                    ? R.string.hooks_guard_installed : R.string.hooks_guard_missing)));
        }

        // The countermeasures this app ships are kept in the same library - that
        // is what arms them again after every restart - but this page is not
        // theirs to show. They are not hooks an agent made, they are not aimed
        // at an application, and there is no deleting them; Special settings is
        // the only place they appear, and it is the only place that can touch
        // them. A package left with nothing else to show disappears with them.
        HookStore store = HookStore.of(this);
        List<String> packages = new ArrayList<>();
        Map<String, List<SavedHook>> shown = new LinkedHashMap<>();
        for (String pkg : store.packages()) {
            List<SavedHook> ordinary = new ArrayList<>();
            for (SavedHook hook : store.forPackage(pkg)) {
                if (!hook.isSpecial()) {
                    ordinary.add(hook);
                }
            }
            if (!ordinary.isEmpty()) {
                packages.add(pkg);
                shown.put(pkg, ordinary);
            }
        }
        if (packages.isEmpty()) {
            hooksContent.addView(section(getString(R.string.section_nothing_registered)));
            hooksContent.addView(body(getString(R.string.hooks_empty_body)));
            return;
        }

        hooksContent.addView(section(getResources().getQuantityString(
                R.plurals.apps_count, packages.size(), packages.size())));
        for (String pkg : packages) {
            hooksContent.addView(hookCard(pkg, shown.get(pkg)));
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
        MaterialButton header = tonalButton((expanded ? "▾  " : "▸  ")
                + getString(R.string.hook_app_header, appLabel(pkg), on, hooks.size()), v -> {
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
            actions.addView(tonalButton(getString(R.string.action_source),
                    v -> showHookSource(hook)));
        }
        actions.addView(outlinedButton(getString(R.string.action_delete),
                v -> confirmDeleteHook(hook)));
        block.addView(actions);
        return block;
    }

    private String statusLine(SavedHook hook) {
        if (!hook.enabled) {
            return getString(R.string.hook_off);
        }
        if (hook.lastError != null && !hook.lastError.isEmpty()) {
            return getString(R.string.hook_failed, trim(hook.lastError, 200));
        }
        if (hook.lastAppliedAt == 0) {
            return getString(R.string.hook_not_armed);
        }
        return getString(R.string.hook_last_armed, DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(new Date(hook.lastAppliedAt)));
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
            toast(getString(enabled
                    ? R.string.toast_hook_saved_later
                    : R.string.toast_hook_saved_nothing_to_stop));
            renderHooks();
            return;
        }

        new Thread(() -> {
            String message;
            try {
                if (enabled) {
                    JSONObject result = service.armHook(hook);
                    int applied = result == null ? 0 : result.optInt("applied", 0);
                    message = applied > 0
                            ? getString(R.string.toast_hook_armed, applied)
                            : getString(R.string.toast_hook_not_taken);
                } else {
                    service.disarmHook(hook);
                    message = getString(R.string.toast_hook_stopped);
                }
            } catch (Throwable t) {
                message = getString(R.string.toast_hook_unreachable, t.getMessage());
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
                .setTitle(R.string.dialog_delete_hook_title)
                .setMessage(getString(R.string.dialog_delete_hook_body, hook.target()))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
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
                .setNegativeButton(R.string.action_cancel, null)
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

        TextView heading = title(getString(R.string.hooks_suspended_title));
        heading.setTextColor(color(androidx.appcompat.R.attr.colorError));
        inner.addView(heading);
        inner.addView(body(getString(R.string.hooks_suspended_body)));
        inner.addView(caption(reason));

        LinearLayout actions = row();
        actions.addView(outlinedButton(getString(R.string.action_lift_suspension),
                v -> confirmLiftSuspension()));
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
                .setTitle(R.string.dialog_lift_title)
                .setMessage(R.string.dialog_lift_body)
                .setPositiveButton(R.string.action_lift_it, (dialog, which) -> new Thread(() -> {
                    String problem = HookGuard.liftSuspension(this);
                    runOnUiThread(() -> {
                        toast(problem == null ? getString(R.string.toast_lifted) : problem);
                        renderHooks();
                    });
                }, "posedmcp-lift-suspension").start())
                .setNegativeButton(R.string.action_cancel, null)
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
                .setPositiveButton(R.string.action_close, null)
                .show();
    }

    private String appLabel(String pkg) {
        if (SavedHook.isSystem(pkg)) {
            // It has no application label because it is not an application; left
            // to the fallback below it would be shown as the bare word "android",
            // which tells the user nothing about what they are looking at.
            return getString(R.string.label_system_framework);
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

    /**
     * Two buttons side by side, each taking half the width.
     *
     * <p>These were one flat row of four until the accessibility button fell off
     * the end of it. Buttons are {@code WRAP_CONTENT} wide, the labels are not
     * short, and one of them changes length with what the service is doing -
     * nothing in that row had any reason to shrink, so the last one simply went
     * off screen. Two fixed columns cannot.
     *
     * <p>The gap is the left button's own right margin; the right one gives its
     * margin up so the pair stays flush with everything above it.
     */
    private LinearLayout buttonGridRow(View left, View right) {
        LinearLayout row = row();
        LinearLayout.LayoutParams leftParams =
                (LinearLayout.LayoutParams) left.getLayoutParams();
        leftParams.width = 0;
        leftParams.weight = 1f;
        LinearLayout.LayoutParams rightParams =
                (LinearLayout.LayoutParams) right.getLayoutParams();
        rightParams.width = 0;
        rightParams.weight = 1f;
        rightParams.rightMargin = 0;
        row.addView(left);
        row.addView(right);
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
            toast(getString(R.string.toast_copied));
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
            toast(getString(R.string.toast_battery_settings_failed));
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
            toast(getString(R.string.toast_a11y_read_failed));
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_repair_title)
                .setMessage(getString(R.string.dialog_repair_body,
                        plan.commandWithout(), plan.commandFull()))
                .setPositiveButton(R.string.action_repair,
                        (dialog, which) -> runAccessibilityRepair(plan))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void runAccessibilityRepair(AccessibilityRepair.Plan plan) {
        toast(getString(R.string.toast_repairing));
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
                toast(failure == null ? getString(R.string.toast_repaired) : failure);
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
