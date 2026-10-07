package dev.posedmcp.mcp;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import dev.posedmcp.Logx;
import dev.posedmcp.a11y.AccessibilityBridge;
import dev.posedmcp.dex.ApkInfo;
import dev.posedmcp.dex.DexClient;
import dev.posedmcp.ipc.BridgeServer;
import dev.posedmcp.root.AuditNotifier;
import dev.posedmcp.root.ConfirmationGate;
import dev.posedmcp.root.ProcessProbe;
import dev.posedmcp.root.RootShell;
import dev.posedmcp.state.DeviceStatus;
import dev.posedmcp.state.EventStore;
import dev.posedmcp.state.HookGuard;
import dev.posedmcp.state.HookRecordStore;
import dev.posedmcp.state.HookStore;
import dev.posedmcp.state.Prefs;
import dev.posedmcp.state.ProbeStore;
import dev.posedmcp.state.SavedHook;
import dev.posedmcp.state.SavedScript;
import dev.posedmcp.state.ScriptStore;
import dev.posedmcp.tools.Capabilities;
import dev.posedmcp.xposed.LuaRuntime;

/**
 * The tool surface exposed to agents.
 *
 * <p>Two conventions run through every mutating tool:
 * <ul>
 *   <li>a <b>reason</b> argument is mandatory - it is shown to the user in the
 *       confirmation prompt, and a prompt with no reason is a prompt the user
 *       cannot evaluate;</li>
 *   <li>a tool whose implementation runs a shell command requires the SHELL
 *       confirmation, which cannot be disabled. Tools that use the module's
 *       platform privileges instead are gated by a relaxation-capable kind.</li>
 * </ul>
 */
public final class ToolRegistry {

    private static final int MAX_PACKAGES = 400;

    /**
     * How the hook tools spell the one target that is not an application.
     *
     * <p>In the descriptions rather than only in the prompt, because an agent
     * cannot ask for what it does not know exists - and the earlier failure was
     * exactly that: system_server was reachable by nothing, and nothing said so.
     */
    private static final String SYSTEM_PACKAGE_HINT =
            " Pass \"" + SavedHook.SYSTEM_PACKAGE + "\" for the system framework (system_server),"
                    + " which is a process rather than an app.";

    /**
     * How long to let the display catch up with the approval window closing.
     * Generous on purpose: it is paid once per approved action, and being too
     * short means the agent reads its own prompt back as if it were the screen.
     */
    private static final long OVERLAY_SETTLE_MS = 400L;

    private static final int MAX_UI_NODES = 600;
    private static final long MAX_DEX_BYTES = 32L * 1024 * 1024;

    private final Context context;
    private final Prefs prefs;
    private final Capabilities capabilities;
    private final BridgeServer bridge;
    private final EventStore events;
    private final HookRecordStore hookRecords;
    private final ProbeStore probeStore;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    /** Set from the MCP handshake so prompts can name the agent that asked. */
    private static final AtomicReference<String> REQUESTER = new AtomicReference<>("an MCP client");

    /**
     * The tool running on this thread, so a notification can name it.
     *
     * <p>Carried here rather than threaded through every handler: the tool is
     * known where the call is dispatched, and the gate that needs it is several
     * frames down a lambda that already takes four arguments.
     */
    private static final ThreadLocal<String> CURRENT_TOOL = new ThreadLocal<>();

    /** Called by the dispatcher around each tool call, on the worker thread. */
    public static void enterTool(String name) {
        CURRENT_TOOL.set(name);
    }

    public static void exitTool() {
        CURRENT_TOOL.remove();
    }

    public ToolRegistry(Context context, Prefs prefs, Capabilities capabilities, BridgeServer bridge,
            EventStore events, HookRecordStore hookRecords, ProbeStore probeStore) {
        this.context = context;
        this.prefs = prefs;
        this.capabilities = capabilities;
        this.bridge = bridge;
        this.events = events;
        this.hookRecords = hookRecords;
        this.probeStore = probeStore;
        registerAll();
    }

    public static void setRequester(String name) {
        REQUESTER.set(name == null || name.isEmpty() ? "an MCP client" : name);
    }

    private static String requester() {
        return REQUESTER.get();
    }

    public McpTool get(String name) {
        return tools.get(name);
    }

    public List<McpTool> all() {
        return new ArrayList<>(tools.values());
    }

    public int size() {
        return tools.size();
    }

    private void add(McpTool tool) {
        tools.put(tool.name, tool);
    }

    // =====================================================================
    // Read-only tools
    // =====================================================================

    private void registerAll() {
        add(McpTool.of("device_info")
                .title("Device information")
                .description("Model, Android version, ABI, root availability and whether the"
                        + " LSPosed module is loaded. Call this first to learn what the device"
                        + " supports. Read-only, never prompts.")
                .readOnly()
                .input(new JSONObject())
                .handler(args -> {
                    JSONObject out = DeviceStatus.toJson();
                    out.put("root", DeviceStatus.rootJson());
                    out.put("module", moduleStatusJson());
                    out.put("displayProbe", dev.posedmcp.tools.DisplayProbe.describe());
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("module_status")
                .title("Module and bridge status")
                .description("Which processes the LSPosed module is currently loaded into, and"
                        + " whether the system_server bridge is connected. Use this to tell"
                        + " whether system-privileged tools will work, or whether a target app"
                        + " still needs its scope enabled and the app restarted. Read-only.")
                .readOnly()
                .input(new JSONObject())
                .handler(args -> McpTool.json(moduleStatusJson()))
                .build());

        add(McpTool.of("list_packages")
                .title("List installed apps")
                .description("Installed packages with their labels. Filter with a substring."
                        + " Useful before targeting an app. Read-only.")
                .readOnly()
                .input(props(
                        "filter", McpTool.string("Case-insensitive substring of package name or label"),
                        "include_system", McpTool.type("boolean", "Include system apps (default false)"),
                        "limit", McpTool.integer("Maximum results, default 100")))
                .handler(args -> {
                    String filter = args.optString("filter", "").toLowerCase(Locale.ROOT);
                    boolean includeSystem = args.optBoolean("include_system", false);
                    int limit = args.optInt("limit", 100);
                    JSONArray packages = listPackages(filter, includeSystem, limit);
                    JSONObject out = new JSONObject();
                    out.put("packages", packages);
                    out.put("count", packages.length());
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("foreground_app")
                .title("Current foreground app")
                .description("The activity that currently has focus, reported by the module inside"
                        + " system_server. Requires the module's system scope. Read-only.")
                .readOnly()
                .input(new JSONObject())
                .handler(args -> {
                    requireSystemBridge("foreground_app");
                    return McpTool.json(capabilities.systemForeground());
                })
                .build());

        add(McpTool.of("launch_app")
                .title("Bring an app to the foreground")
                .description("Starts an app's main activity, the same as tapping its icon."
                        + " Needs no root: this app holds the overlay permission, which is what"
                        + " lets a background process start an activity. Pair it with ui_dump and"
                        + " input_inject to drive an app's interface."
                        + " \n\nUse list_packages to find the package name.")
                .mutating()
                .input(props("package", McpTool.string("Package name, e.g. com.github.android")),
                        "package")
                .handler(args -> {
                    String pkg = require(args, "package");
                    android.content.Intent intent =
                            context.getPackageManager().getLaunchIntentForPackage(pkg);
                    if (intent == null) {
                        throw new McpTool.ToolError(pkg + " has no launchable activity"
                                + " (it may be a service-only package)");
                    }
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try {
                        context.startActivity(intent);
                    } catch (Throwable t) {
                        throw new McpTool.ToolError("could not start " + pkg + ": " + t);
                    }
                    JSONObject out = new JSONObject();
                    out.put("launched", pkg);
                    out.put("note", "Give it a moment, then ui_dump to see where it landed.");
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("events_poll")
                .title("Poll device events")
                .description("Reads the event feed pushed by the module: foreground activity"
                        + " changes, screen on/off, user present, bridge connects and disconnects."
                        + " Pass the last seq you saw as 'since' to get only new events. Read-only.")
                .readOnly()
                .input(props(
                        "since", McpTool.integer("Return events with seq greater than this."
                                + " Omit to get the most recent events."),
                        "limit", McpTool.integer("Maximum events to return, default 100"),
                        "type", McpTool.string("Only return events whose type contains this substring")))
                .handler(args -> {
                    long since = args.has("since") ? args.optLong("since", 0L) : events.oldestSeq() - 1;
                    int limit = Math.max(1, Math.min(args.optInt("limit", 100), 500));
                    String typeFilter = args.optString("type", "").toLowerCase(Locale.ROOT);

                    JSONArray array = new JSONArray();
                    for (EventStore.Entry entry : events.since(since, limit)) {
                        if (!typeFilter.isEmpty()
                                && !entry.type.toLowerCase(Locale.ROOT).contains(typeFilter)) {
                            continue;
                        }
                        array.put(entry.toJson());
                    }
                    JSONObject out = new JSONObject();
                    out.put("events", array);
                    out.put("lastSeq", events.lastSeq());
                    out.put("oldestSeq", events.oldestSeq());
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("plugin_list")
                .title("List loaded plugins")
                .description("Plugins currently loaded into scoped app processes, as reported by"
                        + " those processes. Read-only.")
                .readOnly()
                .input(props("package", McpTool.string("Only list plugins in this package")))
                .handler(args -> {
                    String pkg = args.optString("package", "");
                    JSONArray array = new JSONArray();
                    for (String candidate : connectedPackages()) {
                        if (!pkg.isEmpty() && !pkg.equals(candidate)) {
                            continue;
                        }
                        JSONObject entry = new JSONObject();
                        entry.put("package", candidate);
                        try {
                            JSONObject listed = capabilities.appCall(candidate, "list_plugins",
                                    new JSONObject(), 5_000L);
                            entry.put("plugins", listed.optJSONArray("plugins"));
                        } catch (Throwable t) {
                            entry.put("error", String.valueOf(t.getMessage()));
                        }
                        array.put(entry);
                    }
                    JSONObject out = new JSONObject();
                    out.put("packages", array);
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Static analysis
        // =================================================================

        add(McpTool.of("dex_classes")
                .title("List classes in a DEX or APK")
                .description("Lists the classes in a DEX file or in every classes*.dex of an APK,"
                        + " optionally with their method and field signatures."
                        + " This reads the index rather than disassembling, so it is cheap and is"
                        + " the right first step before picking a target to disassemble or hook."
                        + " Name the target with 'package' for an installed app, or 'path' for an"
                        + " APK/DEX file on disk. Read-only.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Installed package name, e.g. com.example.app"),
                        "path", McpTool.string("Absolute path to an .apk/.dex/.jar instead of a package"),
                        "filter", McpTool.string("Only classes whose name contains this substring"),
                        "with_members", McpTool.type("boolean", "Include method and field signatures"),
                        "limit", McpTool.integer("Maximum classes to return, default 200")))
                .handler(args -> {
                    JSONObject out = DexClient.get(context).call("classes", dexArgs(args),
                            60_000L);
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("dex_search")
                .title("Search inside a DEX or APK")
                .description("Searches the string table, class names, method names or field names."
                        + " Use kind=string to find hard-coded text such as URLs, API keys, error"
                        + " messages or log tags; kind=method to jump straight to a method by name."
                        + " Name the target with 'package' or 'path'. Read-only.")
                .readOnly()
                .input(props(
                        "pattern", McpTool.string("Substring to look for, case-insensitive"),
                        "kind", enumOf("What to search. Default string",
                                "string", "class", "method", "field"),
                        "package", McpTool.string("Installed package name"),
                        "path", McpTool.string("Absolute path to an .apk/.dex/.jar instead of a package"),
                        "limit", McpTool.integer("Maximum hits, default 100")),
                        "pattern")
                .handler(args -> {
                    JSONObject out = DexClient.get(context).call("search", dexArgs(args),
                            60_000L);
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("apk_info")
                .title("Read an APK's manifest")
                .description("Package name, version, SDK levels, permissions, the four component"
                        + " lists, and the signing certificate digest. Parsed by the platform's own"
                        + " package parser, so it is exact. Use it to find entry points and to tell"
                        + " whether a build is debuggable. Name the target with 'package' or 'path'."
                        + " Read-only.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Installed package name"),
                        "path", McpTool.string("Absolute path to an .apk instead of a package")))
                .handler(args -> {
                    String path = args.optString("path", "");
                    String pkg = args.optString("package", "");
                    if (path.isEmpty()) {
                        path = resolveSourcePath(pkg);
                    }
                    return McpTool.json(ApkInfo.describe(context, path, pkg));
                })
                .build());

        add(McpTool.of("apk_list")
                .title("List files inside an APK")
                .description("The archive's entries with their sizes, so you can see which"
                        + " classes*.dex and resource files exist before pulling one apart."
                        + " Read-only.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Installed package name"),
                        "path", McpTool.string("Absolute path to an .apk instead of a package"),
                        "filter", McpTool.string("Only entries whose path contains this substring"),
                        "limit", McpTool.integer("Maximum entries, default 200")))
                .handler(args -> {
                    String path = args.optString("path", "");
                    if (path.isEmpty()) {
                        path = resolveSourcePath(args.optString("package", ""));
                    }
                    return McpTool.json(ApkInfo.listEntries(path, args.optString("filter", ""),
                            args.optInt("limit", 200)));
                })
                .build());

        add(McpTool.of("smali_disassemble")
                .title("Disassemble to smali")
                .description("Runs baksmali over a DEX or APK and writes a .smali tree to disk,"
                        + " returning the path plus file and byte counts. Only a single small class"
                        + " is returned inline, because a full tree is megabytes and would swamp"
                        + " the conversation - read individual files with the shell tools if you"
                        + " need detail. Filter with a class name fragment to keep the output"
                        + " manageable. Read-only (it writes into the app's own cache).")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Installed package name"),
                        "path", McpTool.string("Absolute path to an .apk/.dex instead of a package"),
                        "filter", McpTool.string("Only classes whose name contains this substring")))
                .handler(args -> {
                    JSONObject call = new JSONObject();
                    call.put("path", dexArgs(args).optString("path"));
                    call.put("filter", args.optString("filter", ""));
                    return McpTool.json(DexClient.get(context).call("disassemble", call, 600_000L));
                })
                .build());

        add(McpTool.of("smali_assemble")
                .title("Assemble smali into a DEX")
                .description("Runs smali over smali sources and writes a DEX, returning its path."
                        + " Pass 'dir' for a directory already on disk, or 'sources' as"
                        + " [{path, content}] to hand over smali text directly."
                        + " \n\nThis is how you produce code to inject without a computer: a phone"
                        + " has no javac and no d8, so write the plugin as smali, assemble it here,"
                        + " then pass the resulting path to plugin_load. Assembling on its own"
                        + " executes nothing and does not prompt; the approval happens when the DEX"
                        + " is actually loaded into another app.")
                .readOnly()
                .input(props(
                        "dir", McpTool.string("Directory containing .smali files"),
                        "sources", McpTool.array("object",
                                "Inline smali: [{path: \"com/example/Foo.smali\", content: \"...\"}]"),
                        "output", McpTool.string("Output file name, default classes.dex")))
                .handler(args -> {
                    JSONObject call = new JSONObject();
                    call.put("dir", args.optString("dir", ""));
                    call.put("output", args.optString("output", "classes.dex"));
                    if (args.has("sources")) {
                        call.put("sources", args.optJSONArray("sources"));
                    }
                    return McpTool.json(DexClient.get(context).call("assemble", call, 600_000L));
                })
                .build());

        // =================================================================
        // Root shell
        // =================================================================

        add(McpTool.of("root_shell_exec")
                .title("Run a root shell command")
                .description("Runs one command as uid 0 via su. The user is shown a blocking dialog"
                        + " containing the exact command string and your stated reason, and must tap"
                        + " approve before anything executes. There is no way to skip this and no"
                        + " allow-list; every call prompts. A refusal or a timeout returns an error"
                        + " - treat that as a decision, not a transient failure, and do not retry"
                        + " the same command repeatedly."
                        + " \n\nWrite the command the way you would type it in a shell. Do not chain"
                        + " unrelated work with ';' or '&&' just to reduce the number of prompts;"
                        + " that hides what is being run from the person approving it.")
                .mutating()
                .input(props(
                        "command", McpTool.string("The exact shell command to run as root"),
                        "reason", McpTool.string("Why this command is needed. Shown to the user."
                                + " Be specific about what it changes and why."),
                        "timeout_ms", McpTool.integer("Kill the command after this many ms, default 60000")),
                        "command", "reason")
                .handler(args -> {
                    String command = require(args, "command");
                    String reason = require(args, "reason");
                    long timeout = args.optLong("timeout_ms", prefs.execTimeoutMs());

                    requireConfirmation(ConfirmationGate.Kind.SHELL, "Root shell command", command,
                            reason);

                    RootShell.Result result = capabilities.confirmedShell(command, timeout);
                    JSONObject out = new JSONObject();
                    out.put("exitCode", result.exitCode);
                    out.put("stdout", result.stdout);
                    out.put("stderr", result.stderr);
                    out.put("timedOut", result.timedOut);
                    out.put("durationMs", result.durationMs);
                    if (result.timedOut) {
                        out.put("note", "The command was killed after " + timeout + "ms.");
                    }
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Screen
        // =================================================================

        add(McpTool.of("screen_capture")
                .title("Capture the screen")
                .description("Takes a screenshot. Route preference in mode=auto is accessibility,"
                        + " then system_server, then the root shell - accessibility first because"
                        + " it needs no root and is the only one of the three that still exists on"
                        + " Android 16."
                        + " \n\nmode=a11y captures through the accessibility service; mode=system"
                        + " uses the module inside system_server; mode=root runs 'screencap' as root,"
                        + " which always prompts as a root shell command."
                        + " \n\nThe screenshot comes back as an image you can look at, followed by"
                        + " one line saying its size and which route produced it. The image is"
                        + " scaled down; ui_dump and input_inject use the screen's own pixels.")
                .mutating()
                .input(props(
                        "mode", enumOf("Which capture path to use. Default auto",
                                "auto", "a11y", "system", "root"),
                        "format", enumOf("Image format. Default png", "png", "jpeg"),
                        "max_dimension", McpTool.integer("Longest edge in pixels after scaling, default 1024. 0 keeps full size"),
                        "quality", McpTool.integer("JPEG quality 1-100, default 80"),
                        "reason", McpTool.string("Why you need to see the screen. Shown to the user.")),
                        "reason")
                .handler(args -> {
                    String mode = normalizeMode(args.optString("mode", Capabilities.MODE_AUTO));
                    String reason = require(args, "reason");
                    String format = args.optString("format", "png");
                    int maxDim = args.optInt("max_dimension", 1024);
                    int quality = Math.max(1, Math.min(args.optInt("quality", 80), 100));

                    // Route preference: accessibility, then system_server, then the
                    // shell. Accessibility is first because it needs no root and is
                    // the only one of the three Android 16 still offers.
                    boolean allowFallback = !Capabilities.MODE_A11Y.equals(mode)
                            && !Capabilities.MODE_SYSTEM.equals(mode)
                            && !Capabilities.MODE_ROOT.equals(mode);
                    boolean tryA11y = Capabilities.MODE_A11Y.equals(mode)
                            || (allowFallback && Capabilities.accessibilityOnline());
                    boolean trySystem = !tryA11y && (Capabilities.MODE_SYSTEM.equals(mode)
                            || (allowFallback && capabilities.systemScreenshotUsable()));

                    android.graphics.Bitmap shot = null;
                    byte[] raw = null;
                    String route = null;

                    if (tryA11y) {
                        requireConfirmation(ConfirmationGate.Kind.SCREEN,
                                "Capture the screen contents",
                                "Take a screenshot through the accessibility service and hand it to "
                                        + requester() + ".", reason);
                        try {
                            shot = Capabilities.accessibilityScreenshot();
                            route = Capabilities.MODE_A11Y;
                        } catch (IOException e) {
                            if (!allowFallback) {
                                throw new McpTool.ToolError("the accessibility route failed: "
                                        + e.getMessage());
                            }
                            Logx.w("accessibility screenshot failed, falling back: " + e.getMessage());
                        }
                    }

                    if (shot == null && raw == null && trySystem) {
                        requireSystemBridge("screen_capture");
                        requireConfirmation(ConfirmationGate.Kind.SCREEN,
                                "Capture the screen contents",
                                "Take a screenshot of the current screen and hand it to "
                                        + requester() + ".", reason);
                        try {
                            raw = capabilities.systemScreenshot();
                            route = Capabilities.MODE_SYSTEM;
                        } catch (Exception e) {
                            capabilities.markSystemScreenshotBroken(String.valueOf(e.getMessage()));
                            if (!allowFallback) {
                                throw new McpTool.ToolError("the system screenshot route failed: "
                                        + e.getMessage() + ". Use mode=root, or mode=auto to"
                                        + " fall back automatically.");
                            }
                        }
                    }

                    if (shot == null && raw == null) {
                        route = Capabilities.MODE_ROOT;
                        requireConfirmation(ConfirmationGate.Kind.SHELL, "Root shell command",
                                "screencap -p", reason);
                        try {
                            raw = RootShell.execBinary("screencap -p", 30_000L);
                        } catch (IOException e) {
                            throw new McpTool.ToolError("screencap failed: " + e.getMessage());
                        }
                    }

                    Capabilities.Encoded image = shot != null
                            ? Capabilities.encodeImage(shot, format, maxDim, quality)
                            : Capabilities.encodeImage(raw, format, maxDim, quality);

                    JSONObject out = new JSONObject();
                    out.put("route", route);
                    out.put("mimeType", image.mimeType);
                    out.put("width", image.width);
                    out.put("height", image.height);
                    out.put("bytes", image.bytes);
                    // The image travels as an image content block. The caption is
                    // what a client that cannot show images still has to work
                    // with, and it carries the one thing a model that can see the
                    // picture would otherwise get wrong: its scale.
                    return McpTool.image(image.base64, image.mimeType,
                            "Screenshot of the current screen, " + image.width + "x" + image.height
                                    + " pixels, via the " + route + " route. ui_dump and"
                                    + " input_inject use the screen's own pixel coordinates, which"
                                    + " are not necessarily these.",
                            out);
                })
                .build());

        add(McpTool.of("ui_dump")
                .title("Dump the UI hierarchy")
                .description("Dumps the control tree of the current screen as JSON: text, content"
                        + " descriptions, resource ids, bounds and clickability."
                        + " Far cheaper than a screenshot for locating a control to tap."
                        + " \n\nmode=a11y reads it through the accessibility service, which needs no"
                        + " root; mode=root runs 'uiautomator' as root, which prompts as a root"
                        + " shell command every time. mode=auto prefers accessibility.")
                .mutating()
                .input(props(
                        "reason", McpTool.string("Why you need the UI tree. Shown to the user."),
                        "mode", enumOf("Which route to use. Default auto",
                                "auto", "a11y", "root"),
                        "max_nodes", McpTool.integer("Stop after this many nodes, default 300"),
                        "simplify", McpTool.type("boolean", "Drop uninteresting nodes. Default true")),
                        "reason")
                .handler(args -> {
                    String reason = require(args, "reason");
                    boolean simplify = args.optBoolean("simplify", true);
                    int maxNodes = args.optInt("max_nodes", 300);
                    String mode = normalizeMode(args.optString("mode", Capabilities.MODE_AUTO));

                    boolean allowFallback = !Capabilities.MODE_A11Y.equals(mode)
                            && !Capabilities.MODE_ROOT.equals(mode);
                    boolean tryA11y = Capabilities.MODE_A11Y.equals(mode)
                            || (allowFallback && Capabilities.accessibilityOnline());

                    if (tryA11y) {
                        requireConfirmation(ConfirmationGate.Kind.SCREEN, "Read the current screen",
                                "Read the controls on screen and hand the tree to " + requester()
                                        + ".", reason);
                        try {
                            return McpTool.json(
                                    AccessibilityBridge.activeWindowTree(maxNodes, simplify));
                        } catch (IOException e) {
                            if (!allowFallback) {
                                throw new McpTool.ToolError("the accessibility route failed: "
                                        + e.getMessage());
                            }
                            Logx.w("accessibility ui dump failed, falling back: " + e.getMessage());
                        }
                    }

                    // The shell route needs a root command for every dump, which is
                    // why it is the fallback rather than the default.
                    // The file is removed first, and that is the whole point of this
                    // line. `uiautomator dump` leaves the previous file untouched when
                    // it cannot reach an idle state, and its own output is discarded,
                    // so without the removal a failed dump cats back the *last* screen
                    // and every check below passes: the agent would then measure
                    // coordinates against a screen that is no longer there and send
                    // input to whatever is. Measured with a sentinel file in place of
                    // that earlier screen: the shape without `rm -f` returned it, the
                    // shape with it returns nothing and the check fires.
                    String path = "/data/local/tmp/posedmcp_ui.xml";
                    String command = "rm -f " + shellQuote(path)
                            + "; uiautomator dump --compressed " + shellQuote(path)
                            + " >/dev/null 2>&1; cat " + shellQuote(path);

                    requireConfirmation(ConfirmationGate.Kind.SHELL, "Root shell command", command,
                            reason);

                    RootShell.Result result = capabilities.confirmedShell(command, 30_000L);
                    if (!result.ok() || result.stdout.trim().isEmpty()) {
                        throw new McpTool.ToolError("uiautomator dump produced nothing"
                                + " (exit " + result.exitCode + "). It refuses while the device"
                                + " is not idle, which is the usual cause and is worth retrying;"
                                + " nothing stale is ever returned in its place."
                                + (result.stderr.isEmpty() ? "" : ": " + result.stderr.trim()));
                    }
                    JSONObject out = parseUiDump(result.stdout, simplify);
                    out.put("source", "uiautomator");
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Input
        // =================================================================

        add(McpTool.of("input_inject")
                .title("Inject input events")
                .description("Sends synthetic touch, swipe, text or key events to the device."
                        + " Route preference in mode=auto is accessibility, then system_server,"
                        + " then root."
                        + " \n\nmode=a11y uses the accessibility service: real gestures, and text"
                        + " through ACTION_SET_TEXT on the focused field. It needs no root and"
                        + " keeps working while another app is in front. Keys it cannot express"
                        + " (volume, media) automatically fall through to another route."
                        + " mode=system injects through the module inside system_server;"
                        + " mode=root runs the 'input' command as root, which always prompts as a"
                        + " root shell command."
                        + " \n\nGet coordinates from ui_dump or screen_capture first.")
                .mutating()
                .input(props(
                        "action", enumOf("The gesture or event to send",
                                "tap", "swipe", "long_press", "text", "key"),
                        "x", McpTool.integer("X coordinate for tap/long_press/swipe start"),
                        "y", McpTool.integer("Y coordinate for tap/long_press/swipe start"),
                        "x2", McpTool.integer("End X for swipe"),
                        "y2", McpTool.integer("End Y for swipe"),
                        "duration_ms", McpTool.integer("Swipe/long-press duration, default 300"),
                        "text", McpTool.string("Text to type for action=text"),
                        "keycode", McpTool.string("Key name or code for action=key, e.g. HOME, BACK, 3"),
                        "mode", enumOf("Which injection path to use. Default auto",
                                "auto", "a11y", "system", "root"),
                        "reason", McpTool.string("Why this input is needed. Shown to the user.")),
                        "action", "reason")
                .handler(args -> {
                    String action = require(args, "action");
                    String reason = require(args, "reason");
                    String mode = normalizeMode(args.optString("mode", Capabilities.MODE_AUTO));

                    boolean allowFallback = !Capabilities.MODE_A11Y.equals(mode)
                            && !Capabilities.MODE_SYSTEM.equals(mode)
                            && !Capabilities.MODE_ROOT.equals(mode);
                    boolean tryA11y = Capabilities.MODE_A11Y.equals(mode)
                            || (allowFallback && Capabilities.accessibilityOnline());

                    if (tryA11y) {
                        try {
                            return McpTool.json(accessibilityInput(action, args, reason));
                        } catch (McpTool.ToolError e) {
                            throw e;
                        } catch (IOException e) {
                            if (!allowFallback) {
                                throw new McpTool.ToolError(e.getMessage());
                            }
                            // Some keys have no accessibility equivalent, so this
                            // is a normal outcome rather than a failure.
                            Logx.w("accessibility input unavailable, falling back: " + e.getMessage());
                        }
                    }

                    JSONObject payload = new JSONObject(args.toString());
                    payload.remove("reason");
                    payload.remove("mode");

                    boolean trySystem = !Capabilities.MODE_ROOT.equals(mode)
                            && (Capabilities.MODE_SYSTEM.equals(mode) || capabilities.systemOnline());
                    if (trySystem) {
                        requireSystemBridge("input_inject");
                        requireConfirmation(ConfirmationGate.Kind.INPUT, "Inject input into the device",
                                "Send " + action + " " + describeInputTarget(action, args) + " to "
                                        + "the foreground app as " + requester() + ".", reason);
                        JSONObject out = capabilities.systemInput(payload);
                        out.put("route", Capabilities.MODE_SYSTEM);
                        return McpTool.json(out);
                    }

                    String command = buildInputCommand(action, args);
                    requireConfirmation(ConfirmationGate.Kind.SHELL, "Root shell command",
                            command, reason);
                    RootShell.Result result = capabilities.confirmedShell(command, 20_000L);
                    JSONObject out = new JSONObject();
                    out.put("route", Capabilities.MODE_ROOT);
                    out.put("command", command);
                    out.put("exitCode", result.exitCode);
                    if (!result.stderr.isEmpty()) {
                        out.put("stderr", result.stderr);
                    }
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Dynamic code injection
        // =================================================================

        add(McpTool.of("plugin_load")
                .title("Load code into a scoped app")
                .description("Loads a DEX into a target app's process via reflection"
                        + " (InMemoryDexClassLoader) and calls its entry point. This is how you"
                        + " extend a third-party app: write a class implementing"
                        + " dev.posedmcp.plugin.PluginEntry, compile it to DEX, and pass the bytes."
                        + " \n\nSupply the DEX either as dex_base64, or as dex_path pointing at a"
                        + " file this app can read - typically the output of smali_assemble, which"
                        + " lets you build the payload entirely on the phone."
                        + " \n\nThe target package must be ticked in LSPosed Manager and its process"
                        + " must have started after that (module_status lists which processes are"
                        + " reachable). The plugin runs inside the target app with that app's"
                        + " privileges. Always prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package name, e.g. com.example.app"),
                        "class_name", McpTool.string("Fully qualified DEX class implementing PluginEntry"),
                        "dex_base64", McpTool.string("Base64-encoded DEX containing the class"),
                        "dex_path", McpTool.string("Path to a .dex file instead of dex_base64"),
                        "reason", McpTool.string("What the injected code does and why. Shown to the user.")),
                        "package", "class_name", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String className = require(args, "class_name");
                    String reason = require(args, "reason");

                    byte[] dex = readDex(args);
                    if (dex.length < 8 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x') {
                        throw new McpTool.ToolError("the payload is not a DEX file");
                    }

                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Load code into " + pkg,
                            "Class: " + className + "\nDEX size: " + dex.length + " bytes",
                            reason);

                    requirePeer(pkg);
                    JSONObject callArgs = new JSONObject();
                    callArgs.put("class_name", className);
                    callArgs.put("entry", "");
                    callArgs.put("dex_base64",
                            android.util.Base64.encodeToString(dex, android.util.Base64.NO_WRAP));
                    // Into every process: an application commonly has several,
                    // and the code you are extending may run in any of them.
                    JSONObject out = summarizeAcrossProcesses(
                            capabilities.appCallAll(pkg, "load_plugin", callArgs, 30_000L),
                            "loadedIn",
                            "The payload is loaded into every process of the package.");
                    out.put("package", pkg);
                    out.put("class", className);
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("plugin_invoke")
                .title("Call into a loaded plugin")
                .description("Invokes a plugin's entry method inside the target app's process."
                        + " Use plugin_list first to see what is loaded. Always prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package whose process runs the plugin"),
                        "class_name", McpTool.string("Plugin class name"),
                        "method", McpTool.string("Method on the plugin to call"),
                        "args_json", McpTool.string("JSON array of arguments, default []"),
                        "reason", McpTool.string("Why this call is needed. Shown to the user.")),
                        "package", "class_name", "method", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String className = require(args, "class_name");
                    String method = require(args, "method");
                    String reason = require(args, "reason");
                    String argsJson = args.optString("args_json", "[]");

                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Call plugin code in " + pkg,
                            className + "." + method + "(" + argsJson + ")", reason);

                    requirePeer(pkg);
                    return McpTool.json(capabilities.systemInvokePlugin(pkg, className, method,
                            argsJson));
                })
                .build());

        // =================================================================
        // Scripted injection
        // =================================================================

        add(McpTool.of("lua_exec")
                .title("Run a Lua script inside a scoped app")
                .description("Runs Lua inside the target app's own process, with that app's"
                        + " privileges. This is for logic - loops, conditionals, string building,"
                        + " several calls in sequence. For a single call prefer invoke_method; to"
                        + " change one method's behaviour use hook_method. A script is worth it"
                        + " when the work cannot be expressed as one call."
                        + " \n\nThe interpreter ships with this module, so it is already inside"
                        + " the app: nothing is compiled and nothing is loaded first, unlike"
                        + " plugin_load, which needs a DEX."
                        + " \n\nThe script gets a global table `app`:"
                        + " \n  app.name(), app.uid(), app.context(), app.loader()"
                        + " \n  app.class(\"com.example.Foo\") - the class, or nil when nothing has"
                        + " that name (obfuscated apps are full of names that survive only as"
                        + " strings)"
                        + " \n  app.new(target, ...) - construct. Pass a class, or a name carrying"
                        + " the signature to use, e.g. \"java.util.Date(long)\", when several"
                        + " constructors would fit equally well."
                        + " \n  app.call(target, \"method\", ...) - call it. Pass the class for a"
                        + " static call, an instance otherwise; private and unexported methods are"
                        + " reachable. Overloads are picked by how well the values fit, so"
                        + " put(\"key\", \"value\") still finds put(String, String) among the nine"
                        + " two-argument forms on ContentValues. A genuine tie is refused with the"
                        + " candidates listed; name the one you want exactly as app.methods prints"
                        + " it, e.g. app.call(values, \"put(String,Integer)\", \"key\", 5)."
                        + " \n  app.get(target, \"field\") and app.set(target, \"field\", value)"
                        + " \n  app.methods(target, filter) - the declared methods as"
                        + " \"name(types)\" strings, plus \"<init>(types)\" constructors when no"
                        + " filter is given"
                        + " \n  app.files(path) - {name=, dir=, size=} per entry"
                        + " \n  app.exists(path), app.read(path[, maxChars])"
                        + " \n  app.db(path) - a SQLite database inside the app, opened"
                        + " read-only: db.tables(), db.schema(table), db.query(sql, ...args),"
                        + " db.one(...). Rows come back as tables keyed by column name. It"
                        + " refuses anything that is not a database when you open it, so the"
                        + " -wal and -shm files beside a real one are rejected there rather than"
                        + " at your first query. A query stops at " + LuaRuntime.DB_ROW_LIMIT
                        + " rows and marks the result truncated when there were more; use LIMIT"
                        + " and OFFSET to page. This is usually the fastest way to understand an"
                        + " obfuscated app: what it stores says more than its renamed classes do."
                        + " \n  app.native - native code in the target process: open(path) to"
                        + " dlopen, symbol(id, name) to dlsym, call(address, ...) for up to six"
                        + " word arguments, read/write/string for memory, and status()/error()."
                        + " Addresses are \"0x...\" strings, not numbers: Lua numbers are doubles"
                        + " here and a pointer does not survive them. Arguments and results are"
                        + " machine words, so float or struct arguments cannot be expressed. A"
                        + " bad address takes the target process down."
                        + " \n  app.log(text) - into the module log"
                        + " \nThere is no io and no os library; file access goes through"
                        + " app.files and app.read, and databases through app.db, all of which"
                        + " only read."
                        + " \n\nstdout and the returned value both come back in the result, and"
                        + " errors carry a line number. app.files raises rather than returning an"
                        + " empty list when it cannot read a directory, so an empty list means the"
                        + " directory really is empty - it is not evidence that data is missing."
                        + " \n\nThe script is stopped if it runs past its instruction budget, so"
                        + " it cannot hang the target app. Always prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package whose process runs the script"),
                        "source", McpTool.string("Lua source. Return a value or print; both are"
                                + " reported back."),
                        "max_instructions", McpTool.integer("Instruction budget, default "
                                + LuaRuntime.DEFAULT_MAX_INSTRUCTIONS),
                        "reason", McpTool.string("What the script does and why. Shown to the"
                                + " user.")),
                        "package", "source", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String source = require(args, "source");
                    String reason = require(args, "reason");

                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Run a script inside " + pkg,
                            forPrompt(source),
                            reason);

                    return McpTool.json(runScript(pkg, source,
                            args.optLong("max_instructions", LuaRuntime.DEFAULT_MAX_INSTRUCTIONS)));
                })
                .build());

        // =================================================================
        // Saved scripts
        // =================================================================

        add(McpTool.of("script_save")
                .title("Save a script to the automation tab")
                .description("Files a Lua script into the user's library of kept scripts, where"
                        + " they can open, run or delete it from the app's automation tab."
                        + " \n\nSaving is not running. It writes to this app's own storage and"
                        + " changes nothing on the device, so it does not prompt - the boundary"
                        + " is execution, the same as smali_assemble. The source is compiled"
                        + " first and the save is refused if it does not parse, so the user"
                        + " cannot end up with a script that cannot run."
                        + " \n\nUse it when the user asks for something repeatable, or when a"
                        + " script has just done something useful: run it with lua_exec first,"
                        + " then save the version that worked. Saving under the same name"
                        + " replaces that script instead of adding a second copy."
                        + " \n\nThe name and the effect line are what the user sees in the list,"
                        + " so write the effect in their language and say what running it does.")
                .mutating()
                .input(props(
                        "name", McpTool.string("Short name for the script, unique in the library"),
                        "package", McpTool.string("Target package the script runs inside"),
                        "source", McpTool.string("The Lua source"),
                        "effect", McpTool.string("One line, in the user's language: what running"
                                + " this does")),
                        "name", "package", "source", "effect")
                .handler(args -> {
                    String name = require(args, "name");
                    String pkg = require(args, "package");
                    String source = require(args, "source");
                    String effect = require(args, "effect");

                    String problem = LuaRuntime.checkSyntax(source);
                    if (problem != null) {
                        throw new McpTool.ToolError("the script does not compile: " + problem);
                    }

                    SavedScript saved = ScriptStore.of(context).save(name, pkg, source, effect);
                    JSONObject out = saved.describe();
                    out.put("saved", true);
                    out.put("note", "The user runs or deletes it from the automation tab."
                            + " Saving under this name again replaces it.");
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("script_list")
                .title("List the user's saved scripts")
                .description("The scripts the user has kept, newest first, with what each one is"
                        + " for and what happened the last time it was run. Pass a name to get"
                        + " that one script's source as well. Read-only, never prompts.")
                .readOnly()
                .input(props(
                        "name", McpTool.string("Return this one script in full, including its"
                                + " source")))
                .handler(args -> {
                    ScriptStore store = ScriptStore.of(context);
                    String name = args.optString("name", "");
                    JSONArray scripts = new JSONArray();
                    if (!name.isEmpty()) {
                        SavedScript script = store.byName(name);
                        if (script == null) {
                            throw new McpTool.ToolError("no saved script called '" + name + "'."
                                    + " Call script_list without a name to see what is there.");
                        }
                        JSONObject one = script.describe();
                        one.put("source", script.source);
                        scripts.put(one);
                    } else {
                        for (SavedScript script : store.all()) {
                            scripts.put(script.describe());
                        }
                    }
                    JSONObject out = new JSONObject();
                    out.put("scripts", scripts);
                    out.put("count", scripts.length());
                    return McpTool.json(out);
                })
                .build());

        // =================================================================
        // Runtime observation
        // =================================================================

        add(McpTool.of("hook_method")
                .title("Watch or change a method at runtime")
                .description("Installs a hook on a method inside a running application, and"
                        + " keeps it. By default the hook only records: every call's arguments,"
                        + " return value, exception and thread. Supply any of the modification"
                        + " options and it also changes what the application does."
                        + " \n\nPersistent by design. A runtime hook normally dies with the"
                        + " process it was installed into, and on this device processes are"
                        + " killed and frozen constantly - so a hook that vanished on the next"
                        + " app start was useless for anything but one sitting. This one is"
                        + " stored and re-armed automatically inside every process of that"
                        + " application as it starts. Pass persist=false for a hook you only"
                        + " want for the next few minutes."
                        + " \n\nBecause a kept hook keeps working without asking again, it shows"
                        + " up on the app's Hooks page, where the user can switch it off or"
                        + " delete it. hook_list shows them; hook_clear removes one for good."
                        + " \n\nThis is the dynamic half of analysis, and it needs no plugin"
                        + " DEX - the module installs the hook directly. The usual loop is: find"
                        + " a method with dex_search or smali_disassemble, hook it here, use the"
                        + " app, then read hook_records to see what flowed through it."
                        + " \n\nModification options, all optional and combinable:"
                        + " return_value makes the method produce that value and skips the original"
                        + " entirely; set_args replaces arguments before the call; set_fields"
                        + " assigns fields on the instance after the call. Values are parsed as"
                        + " JSON when possible, so \"false\" is a boolean and \"42\" a number, and"
                        + " are converted to whatever type the method actually declares."
                        + " \n\nFor a body that is logic rather than a value - branch, loop, call"
                        + " something else - use hook_lua. Always prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package whose process should be hooked."
                                + SYSTEM_PACKAGE_HINT),
                        "class", McpTool.string("Fully qualified class name, e.g. com.example.Foo"),
                        "method", McpTool.string("Method name"),
                        "params", McpTool.string("Comma-separated parameter types to pick one"
                                + " overload, e.g. java.lang.String,int. Omit to hook every overload."),
                        "return_value", McpTool.string("Make the method return this and skip the"
                                + " original. Parsed as JSON when possible."),
                        "set_args", McpTool.freeformObject("Map of argument index to new value,"
                                + " applied before the call, e.g. {\"0\":\"hello\",\"2\":false}"),
                        "set_fields", McpTool.freeformObject("Map of field name to new value,"
                                + " assigned on the instance after the call, e.g. {\"mEnabled\":true}"),
                        "observe", McpTool.type("boolean", "Keep recording calls. Default true"),
                        "max_records", McpTool.integer("Keep at most this many calls, default 200"),
                        "persist", McpTool.type("boolean", "Keep the hook and re-arm it whenever"
                                + " that app starts. Default true; false is for a hook you will"
                                + " clear within the same sitting."),
                        "reason", McpTool.string("Why this is needed. Shown to the user.")),
                        "package", "class", "method", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String className = require(args, "class");
                    String method = require(args, "method");
                    String reason = require(args, "reason");
                    boolean persist = args.optBoolean("persist", true);

                    String effect = describeHookEffect(args);
                    refuseIfSuspended(pkg, persist);
                    String kept = persist
                            ? "\n\nThis hook is kept. " + (SavedHook.isSystem(pkg)
                                    ? "It is put back every time system_server starts, until you"
                                            + " take it off the Hooks page."
                                    : "It will be re-armed automatically every time that"
                                            + " application starts, until you take it off the Hooks"
                                            + " page.")
                            : "";
                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            (effect == null ? "Watch a method in " : "Change behaviour in ")
                                    + targetName(pkg),
                            className + "." + method
                                    + "(" + args.optString("params", "") + ")"
                                    + (effect == null ? "" : "\n" + effect)
                                    + kept
                                    + systemTargetWarning(pkg, persist),
                            reason);

                    requirePeer(pkg);
                    JSONObject call = new JSONObject();
                    call.put("class", className);
                    call.put("method", method);
                    call.put("params", args.optString("params", ""));
                    call.put("max_records", args.optInt("max_records", 200));
                    call.put("observe", args.optBoolean("observe", true));
                    for (String key : new String[]{"return_value", "set_args", "set_fields"}) {
                        if (args.has(key)) {
                            call.put(key, args.get(key));
                        }
                    }
                    JSONObject out = summarizeAcrossProcesses(
                            capabilities.appCallAll(pkg, "hook_method", call, 30_000L),
                            "hookedIn", "The hook is installed wherever the method runs.");

                    if (persist) {
                        SavedHook hook = new SavedHook();
                        hook.packageName = pkg;
                        hook.className = className;
                        hook.methodName = method;
                        hook.params = args.optString("params", "");
                        hook.body = SavedHook.BODY_RULE;
                        // The request itself is the definition, so re-arming is a
                        // copy rather than a translation that could drift.
                        hook.spec = call.toString();
                        hook.effect = reason;
                        hook.enabled = true;
                        HookStore.of(context).save(hook);
                        out.put("saved", true);
                        out.put("target", hook.target());
                    } else {
                        out.put("saved", false);
                        out.put("note", "persist=false: this hook lives only in the processes"
                                + " running now, and is gone when they restart.");
                    }
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("hook_records")
                .title("Read recorded calls")
                .description("Returns what the hooks installed by hook_method have captured,"
                        + " newest first. Read-only."
                        + "\n\nRecords are kept by this app from the moment they are made, so"
                        + " they cover calls made by processes that have since been killed -"
                        + " which on many devices is a matter of seconds. Asking the process"
                        + " itself would report nothing in exactly the case worth investigating."
                        + " A record's `source` is the process it came from.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Target package to read from."
                                + SYSTEM_PACKAGE_HINT),
                        "subject", McpTool.string("Only hooks whose class or method contains this"),
                        "limit", McpTool.integer("Maximum records, default 100")),
                        "package")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String subject = args.optString("subject", "");
                    int limit = args.has("limit") ? args.optInt("limit", 100) : 100;
                    JSONObject call = new JSONObject();
                    call.put("subject", subject);
                    call.put("limit", limit);

                    // The armed hooks are live state, so every process still has
                    // to be asked - a hook sits in the process it was installed
                    // into, and asking one of them would report "nothing is
                    // hooked" while a hook sits in a sibling.
                    //
                    // The records are read from the app instead. They were pushed
                    // there as they were made, so they describe processes that
                    // have since been killed - which on this device is all of
                    // them, within seconds. Reading them back from a live process
                    // was why a hook that was recording the whole time could
                    // still report nothing.
                    JSONArray across = capabilities.appCallAll(pkg, "hook_records", call, 20_000L);
                    JSONArray hooks = new JSONArray();
                    JSONArray liveRecords = new JSONArray();
                    long dropped = 0L;
                    for (int i = 0; i < across.length(); i++) {
                        JSONObject entry = across.optJSONObject(i);
                        if (entry == null || !entry.optBoolean("ok", false)) {
                            continue;
                        }
                        JSONObject result = entry.optJSONObject("result");
                        if (result == null) {
                            continue;
                        }
                        String process = entry.optString("process", "");
                        tag("process", process, result.optJSONArray("hooks"), hooks);
                        tag("source", process, result.optJSONArray("records"), liveRecords);
                        dropped += result.optLong("droppedRecords", 0L);
                    }

                    Set<String> seen = new HashSet<>();
                    List<JSONObject> merged = new ArrayList<>();
                    for (JSONObject record : hookRecords.query(
                            Collections.singletonList(BridgeServer.peerKeyPrefix(pkg)), subject,
                            limit)) {
                        if (remember(seen, record)) {
                            merged.add(record);
                        }
                    }
                    for (int i = 0; i < liveRecords.length(); i++) {
                        JSONObject record = liveRecords.optJSONObject(i);
                        if (record != null && remember(seen, record)) {
                            merged.add(record);
                        }
                    }
                    merged.sort((a, b) -> Long.compare(b.optLong("ts", 0L), a.optLong("ts", 0L)));

                    JSONArray records = new JSONArray();
                    for (int i = 0; i < merged.size() && records.length() < limit; i++) {
                        records.put(merged.get(i));
                    }

                    JSONObject out = new JSONObject();
                    out.put("hooks", hooks);
                    out.put("records", records);
                    if (dropped > 0) {
                        out.put("droppedRecords", dropped);
                        out.put("note", dropped + " record(s) could not be handed to this app and"
                                + " were lost - which is a fault in the bridge, not in the hooks.");
                    } else if (records.length() == 0) {
                        out.put("note", hooks.length() == 0
                                ? "nothing is hooked in any process of this package"
                                : "hooks are armed but nothing has called them yet");
                    }
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("probe_state")
                .title("Read the frozen probe target")
                .description("The app the user has frozen with the floating probe button, and the"
                        + " evidence captured at the moment it froze: every thread with its Java"
                        + " stack, which packages outside the app own the most frames (and which"
                        + " thread each one runs on), the shared libraries the app has mapped"
                        + " with any known ad/analytics SDKs among them, and the view tree."
                        + " Read-only."
                        + "\n\nA freeze is the only way a transient screen can be inspected"
                        + " without racing it: the user presses the button and the scene stays"
                        + " exactly as it was. You cannot freeze anything yourself;"
                        + " probe_resume releases it."
                        + "\n\ninScope says whether the module is loaded into that app. true"
                        + " means hook_method / hook_lua / lua_exec can reach into the frozen"
                        + " process right now; false means the stacks and screenshots are all"
                        + " there is, and no probe can be inserted until the user adds the app"
                        + " to the LSPosed scope.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Package name of the frozen app,"
                                + " e.g. com.miui.gallery")),
                        "package")
                .handler(args -> {
                    String pkg = require(args, "package");
                    JSONObject out = new JSONObject();
                    out.put("package", pkg);

                    ProcessProbe.State state = ProcessProbe.current(context);
                    if (state == null || !state.pkg.equals(pkg)) {
                        out.put("frozen", false);
                        JSONArray notes = new JSONArray();
                        if (state == null) {
                            notes.put("Nothing is frozen. The user freezes the app in front with"
                                    + " the floating probe button; the snapshot exists only from"
                                    + " that moment, because a transient screen is gone by the"
                                    + " time any tool could read it.");
                        } else {
                            notes.put("The probe freeze currently holds " + state.pkg
                                    + ", not " + pkg + ".");
                        }
                        out.put("notes", notes);
                        return McpTool.json(out);
                    }

                    out.put("frozen", true);
                    out.put("frozenAt", state.frozenAt);
                    out.put("expiresAt", state.expiresAt);
                    out.put("remainingMs", state.remainingMs());
                    JSONArray pids = new JSONArray();
                    for (int pid : state.pids) {
                        pids.put(pid);
                    }
                    out.put("pids", pids);

                    boolean inScope = false;
                    try {
                        inScope = !bridge.peerKeys(pkg).isEmpty();
                    } catch (Throwable ignored) {
                    }
                    out.put("inScope", inScope);

                    // The frozen process cannot be asked: it is frozen, and the
                    // snapshot was taken at freeze time because that is the one
                    // moment the scene existed. This store is what survives it.
                    JSONObject snapshot = probeStore == null ? null : probeStore.latest(pkg);
                    if (snapshot != null) {
                        out.put("capturedAt", snapshot.optLong("capturedAt", 0L));
                        if (snapshot.has("captureError")) {
                            out.put("captureError", snapshot.optString("captureError"));
                        } else {
                            out.put("threads", snapshot.optJSONArray("threads"));
                            out.put("foreignPackages", snapshot.optJSONArray("foreignPackages"));
                        }
                        if (snapshot.has("activity")) {
                            out.put("activity", snapshot.optString("activity"));
                        }
                        if (snapshot.has("ui")) {
                            out.put("ui", snapshot.optJSONObject("ui"));
                        }
                        if (snapshot.has("libraries")) {
                            out.put("libraries", snapshot.optJSONArray("libraries"));
                            out.put("sdkHints", snapshot.optJSONArray("sdkHints"));
                        }
                    }

                    JSONArray notes = new JSONArray();
                    notes.put("The screen is frozen exactly as the user left it: screen_capture"
                            + " sees the same frame for as long as this freeze lasts.");
                    notes.put("It releases itself in " + (state.remainingMs() / 1000L)
                            + "s (probe_resume releases it earlier).");
                    if (snapshot != null && snapshot.has("ui")) {
                        notes.put("ui is the view tree captured just before the freeze - a frozen"
                                + " app answers no tool, so this is the only copy of it.");
                    }
                    if (snapshot != null && snapshot.optJSONArray("sdkHints") != null
                            && snapshot.optJSONArray("sdkHints").length() > 0) {
                        notes.put("sdkHints names the ad/analytics SDKs found among the loaded"
                                + " shared objects - the .so files survive the obfuscation their"
                                + " Java does not, so these stay reliable even where the frame"
                                + " names are junk. Threads marked topForeign run through that"
                                + " package's code.");
                    }
                    if (snapshot != null && snapshot.has("captureError")) {
                        notes.put("The Java stacks could not be captured: "
                                + snapshot.optString("captureError")
                                + ". Some system apps swallow the dump signal; for those,"
                                + " the stacks arrive only from inside the process, which"
                                + " needs the app in the module's scope.");
                    }
                    if (inScope) {
                        notes.put("This app is in the module's scope: hook_method, hook_lua and"
                                + " lua_exec can reach into the frozen process right now."
                                + " Threads whose stacks run through a foreign package"
                                + " (see foreignPackages) are where third-party SDKs live -"
                                + " hooking one of those frames' methods is how its arguments"
                                + " are read.");
                    } else {
                        notes.put("This app is NOT in the module's scope, so nothing can be"
                                + " inserted into it: the Java stacks and screenshots are all"
                                + " there is. For probe access the user must add the app to the"
                                + " LSPosed scope (and the app restarts).");
                    }
                    out.put("notes", notes);
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("probe_resume")
                .title("Release the frozen probe target")
                .description("Releases the app frozen by the probe button. Takes a package name"
                        + " and nothing else: the release is a fixed root command built from the"
                        + " record this app wrote when it froze that package, so the argument"
                        + " can only choose among freezes that already exist - it is validated"
                        + " against that record and never reaches the shell."
                        + " Deliberately does NOT prompt: a freeze is a state the user asked"
                        + " for, releasing it can do nothing but end it, and putting a"
                        + " confirmation on the release path would only lengthen the freeze in"
                        + " exactly the case where the user is not there to answer.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Package name of the frozen app,"
                                + " e.g. com.miui.gallery")),
                        "package")
                .handler(args -> {
                    String pkg = require(args, "package");
                    boolean released;
                    try {
                        released = ProcessProbe.resumeIf(context, pkg);
                    } catch (Throwable t) {
                        throw new McpTool.ToolError("could not release the freeze: " + t);
                    }
                    if (!released) {
                        ProcessProbe.State state = ProcessProbe.current(context);
                        throw new McpTool.ToolError(state == null
                                ? "nothing is frozen"
                                : "the probe freeze holds " + state.pkg + ", not " + pkg);
                    }
                    JSONObject out = new JSONObject();
                    out.put("released", pkg);
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("hook_clear")
                .title("Remove hooks, for good")
                .description("Removes hooks: exactly one by `id`, a subset by `subject`, or every"
                        + " one of them for the package. In each case it also removes the saved"
                        + " definitions, so nothing is re-armed when that app next starts. Worth"
                        + " doing once you are finished: a watched app"
                        + " keeps paying for hooks you no longer read, and a kept hook keeps"
                        + " working without asking again."
                        + " \n\nWorks even when that app cannot stay running, which is the case"
                        + " it exists for. A hook that crashes its app keeps crashing it - the"
                        + " app arms the hook again on every start - so the saved definition is"
                        + " what has to go, and removing it needs no live process at all. It is"
                        + " also the only way to clean up a library entry for an app that is not"
                        + " running. The answer says which processes were reached and what was"
                        + " forgotten. Prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package." + SYSTEM_PACKAGE_HINT),
                        "id", McpTool.string("Remove exactly one hook, by the id hook_list shows."
                                + " This is the precise option, and the one to reach for when only"
                                + " one of several hooks on the same class should go."),
                        "subject", McpTool.string("Or remove a subset: only hooks whose class or"
                                + " method contains this."),
                        "reason", McpTool.string("Why the hooks are being removed. Shown to the user.")),
                        "package", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String reason = require(args, "reason");
                    String subject = args.optString("subject", "");
                    String id = args.optString("id", "");
                    HookStore store = HookStore.of(context);

                    // One hook, by the library's own identity, when the caller named one.
                    // Resolved before anything else and refused loudly if it does not
                    // match, because the alternative - falling through to the subject
                    // filter - would turn a stale or mistyped id into "everything in this
                    // package", which is the opposite of what was asked for.
                    SavedHook one = null;
                    if (!id.isEmpty()) {
                        one = store.byId(id);
                        if (one == null) {
                            throw new McpTool.ToolError("no kept hook has the id '" + id + "'."
                                    + " Ids come from hook_list and change when a hook is"
                                    + " registered again, so read the list rather than reusing an"
                                    + " old one. Nothing was removed.");
                        }
                        if (!one.packageName.equals(pkg)) {
                            throw new McpTool.ToolError("the hook with id '" + id + "' is kept for "
                                    + one.packageName + ", not " + pkg + ". Nothing was removed.");
                        }
                        if (one.isSpecial()) {
                            // Refused rather than quietly skipped: an agent that asked
                            // for this hook by id is owed an answer, and the answer is
                            // that this one belongs to the app rather than to it.
                            throw new McpTool.ToolError("that hook is one of this app's own"
                                    + " countermeasures, not a hook you registered. The user can"
                                    + " pause it in Special settings, and nothing can delete it."
                                    + " Nothing was removed.");
                        }
                    }

                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Remove " + (one == null ? "hooks from " : "a hook from ") + pkg,
                            one != null
                                    ? one.target() + (one.effect.isEmpty()
                                            ? "" : "\n" + one.effect)
                                    : (subject.isEmpty() ? "all hooks in this package"
                                            : "hooks matching \"" + subject + "\""),
                            reason);

                    // Deliberately no requirePeer here, unlike every other hook tool.
                    // This is the one operation that has to work when the target cannot
                    // stay alive, and that is exactly the case it is most needed for: a
                    // hook that crashes its app keeps crashing it, because the app
                    // re-arms the hook on every start. Refusing when nothing is
                    // reachable would leave no way out through the tools at all - which
                    // is what someone hit, and had to work around by hand in the app.
                    JSONObject call = new JSONObject();
                    // The module unhooks by the runtime identity, class#method; the id is
                    // the library's own. One hook therefore means sending its key, rather
                    // than whatever substring a caller would have had to invent.
                    call.put("subject", one == null
                            ? subject : one.className + "#" + one.methodName);
                    JSONObject out = summarizeAcrossProcesses(
                            capabilities.appCallAll(pkg, "hook_clear", call, 20_000L),
                            "clearedIn", "Every process of the package was asked.");

                    // Forget the definitions as well - and here that is the whole point.
                    // A saved hook re-arms itself, so clearing only the process would
                    // bring it back the next time the app starts: the exact opposite of
                    // what was asked, and for a crashing hook a loop with no end.
                    JSONArray forgotten = new JSONArray();
                    if (one != null) {
                        store.delete(one.id);
                        forgotten.put(one.target());
                    } else {
                        forgotten = forgetHooks(pkg, subject);
                    }
                    out.put("forgottenSavedHooks", forgotten);
                    if (out.optInt("clearedIn", 0) == 0) {
                        out.put("note", "No process of that package was reachable, so nothing live"
                                + " was touched. For a hook that is crashing its app that is not a"
                                + " problem - there is nothing left running to unhook - and the"
                                + " part that mattered is done: "
                                + (forgotten.length() == 0
                                        ? "no saved definition matched, so nothing changed."
                                        : forgotten.length() + " saved definition(s) are gone, so"
                                                + " that hook will not be armed when the app next"
                                                + " starts."));
                    } else if (forgotten.length() > 0) {
                        out.put("note", "Also removed from the saved hooks, so it will not come"
                                + " back when that application restarts.");
                    }
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("hook_lua")
                .title("Register a hook whose body is a Lua script")
                .description("The same idea as hook_method, but the body is logic rather than a"
                        + " value: it can read the arguments, decide, call other things, and"
                        + " change or replace the result. The script must call app.hook{...}"
                        + " exactly once, which is what actually installs it."
                        + " \n\nExample: app.hook{class = \"com.x.Y\", method = \"isPro\","
                        + " effect = \"pretend everything is unlocked\", after = function(ctx)"
                        + " ctx.set_result(true) end}"
                        + " \n\nThe context a body receives is ctx: args (a table of what the"
                        + " call got), this, phase, and set_arg(i,v) / set_result(v) / result() /"
                        + " throwable() / field(name) / set_field(name,v). A call to set_result"
                        + " skips the original method."
                        + " \n\nAn error inside the body never reaches the application - but it"
                        + " is recorded, and hook_records reports it, because a hook that fails"
                        + " on every call otherwise looks exactly like one that matches nothing."
                        + " \n\nLike hook_method this is kept: the script is re-run in each"
                        + " process as the app starts, so it should do nothing but register the"
                        + " hook. The user can see and remove it on the Hooks page. Prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package to register the hook in."
                                + SYSTEM_PACKAGE_HINT),
                        "source", McpTool.string("Lua that calls app.hook{...} exactly once"),
                        "reason", McpTool.string("Why this is needed. Shown to the user.")),
                        "package", "source", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String source = require(args, "source");
                    String reason = require(args, "reason");

                    refuseIfSuspended(pkg, true);
                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Register a Lua hook in " + targetName(pkg),
                            source + "\n\nThis hook is kept. " + (SavedHook.isSystem(pkg)
                                    ? "It is re-run every time system_server starts, so it stays"
                                            + " there until you take it off the Hooks page."
                                    : "It is re-run automatically every time that application"
                                            + " starts, so it stays in the app until you take it"
                                            + " off the Hooks page.")
                                    + systemTargetWarning(pkg, true),
                            reason);

                    requirePeer(pkg);
                    JSONObject call = new JSONObject();
                    call.put("source", source);
                    call.put("max_instructions", LuaRuntime.DEFAULT_MAX_INSTRUCTIONS);
                    call.put("module_apk", context.getApplicationInfo().sourceDir);

                    JSONArray across = capabilities.appCallAll(pkg, "lua_exec", call, 45_000L);
                    JSONObject out = summarizeAcrossProcesses(across, "ranIn",
                            "The script was run in every process of the package.");

                    SavedHook hook = registeredHook(across);
                    if (hook == null) {
                        out.put("saved", false);
                        out.put("warning", "Nothing was saved: the script has to register exactly"
                                + " one hook. A kept Lua hook arms itself by being run again, so a"
                                + " script registering none, or several, cannot be kept.");
                        return McpTool.json(out);
                    }
                    hook.packageName = pkg;
                    hook.body = SavedHook.BODY_LUA;
                    hook.source = source;
                    if (hook.effect == null || hook.effect.isEmpty()) {
                        hook.effect = reason;
                    }
                    hook.enabled = true;
                    HookStore.of(context).save(hook);
                    out.put("saved", true);
                    out.put("target", hook.target());
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("hook_list")
                .title("List kept hooks")
                .description("The hooks that are kept for this device: which app each one is in,"
                        + " what it is attached to, whether it is switched on, and whether a"
                        + " process has actually taken it. These re-arm themselves every time"
                        + " their app starts, so this is the list that says what is quietly"
                        + " happening without asking. Read-only, never prompts.")
                .readOnly()
                .input(props(
                        "package", McpTool.string("Only hooks for this package."
                                + SYSTEM_PACKAGE_HINT)))
                .handler(args -> {
                    String pkg = args.optString("package", "");
                    HookStore store = HookStore.of(context);
                    List<String> packages = pkg.isEmpty()
                            ? store.packages() : Collections.singletonList(pkg);

                    JSONArray hooks = new JSONArray();
                    JSONArray running = new JSONArray();
                    for (String name : packages) {
                        if (bridge.hasPeer(name)) {
                            running.put(name);
                        }
                        for (SavedHook hook : store.forPackage(name)) {
                            hooks.put(hook.describe());
                        }
                    }

                    JSONObject out = new JSONObject();
                    out.put("hooks", hooks);
                    out.put("appsRunningNow", running);
                    if (hooks.length() == 0) {
                        out.put("note", "nothing is kept for " + (pkg.isEmpty()
                                ? "any application" : pkg));
                    }
                    return McpTool.json(out);
                })
                .build());

        add(McpTool.of("invoke_method")
                .title("Call a method inside another app")
                .description("Calls a method inside a target app's process, as that app: with its"
                        + " class loader and its privileges. Private and unexported methods are"
                        + " reachable, which is the point - this drives an app through its own API"
                        + " rather than tapping its interface."
                        + " \n\nStatic method: name class + method. Instance method: also say how to"
                        + " get the receiver - instance_class plus either instance_field (a static"
                        + " field, typically a singleton) or instance_method (a static no-argument"
                        + " accessor)."
                        + " \n\nArguments are coerced to the declared parameter types, so \"false\""
                        + " arrives as a boolean and \"42\" as a number. Nothing is compiled or"
                        + " loaded: the call happens directly, so it needs no DEX."
                        + " \n\nFind the class and method with dex_search and smali_disassemble"
                        + " first. Always prompts.")
                .mutating()
                .input(props(
                        "package", McpTool.string("Target package whose process should make the call"),
                        "class", McpTool.string("Fully qualified class name"),
                        "method", McpTool.string("Method name"),
                        "params", McpTool.string("Comma-separated parameter types, needed only to"
                                + " choose between overloads"),
                        "args", McpTool.array("object", "Arguments as a JSON array,"
                                + " e.g. [\"rikkahub\",\"rikkahub\"]"),
                        "instance_class", McpTool.string("For instance methods: the class holding the"
                                + " receiver, often a singleton holder"),
                        "instance_field", McpTool.string("...its static field holding the receiver,"
                                + " e.g. INSTANCE"),
                        "instance_method", McpTool.string("...or a static no-argument method"
                                + " returning it, e.g. getInstance"),
                        "reason", McpTool.string("Why this call is needed. Shown to the user.")),
                        "package", "class", "method", "reason")
                .handler(args -> {
                    String pkg = require(args, "package");
                    String className = require(args, "class");
                    String method = require(args, "method");
                    String reason = require(args, "reason");
                    String params = args.optString("params", "");
                    String instanceClass = args.optString("instance_class", "");
                    String instanceField = args.optString("instance_field", "");
                    String instanceMethod = args.optString("instance_method", "");

                    StringBuilder detail = new StringBuilder();
                    detail.append(className).append('.').append(method)
                            .append('(').append(params).append(')');
                    if (args.has("args")) {
                        detail.append("\narguments: ").append(args.get("args"));
                    }
                    if (!instanceClass.isEmpty()) {
                        detail.append("\nreceiver: ").append(instanceClass);
                        if (!instanceField.isEmpty()) {
                            detail.append('.').append(instanceField);
                        } else if (!instanceMethod.isEmpty()) {
                            detail.append('.').append(instanceMethod).append("()");
                        }
                    }

                    requireConfirmation(ConfirmationGate.Kind.PLUGIN,
                            "Call into " + pkg + " through its own API", detail.toString(), reason);

                    requirePeer(pkg);
                    JSONObject call = new JSONObject();
                    call.put("class", className);
                    call.put("method", method);
                    call.put("params", params);
                    if (args.has("args")) {
                        call.put("args", args.opt("args"));
                    }
                    call.put("instance_class", instanceClass);
                    call.put("instance_field", instanceField);
                    call.put("instance_method", instanceMethod);
                    return McpTool.json(capabilities.appCall(pkg, "invoke_method", call, 60_000L));
                })
                .build());
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * The call behind {@code lua_exec}, without the prompt.
     *
     * <p>Shared with the automation tab: tapping Run there is the user making the
     * decision themselves, so prompting again would be asking twice.
     */
    public JSONObject runScript(String pkg, String source, long maxInstructions) throws Exception {
        requirePeer(pkg);
        JSONObject callArgs = new JSONObject();
        callArgs.put("source", source);
        callArgs.put("max_instructions", maxInstructions);
        // Where the module can find its own APK, for loading the native library
        // inside the target process.
        callArgs.put("module_apk", context.getApplicationInfo().sourceDir);
        JSONObject out = capabilities.appCall(pkg, "lua_exec", callArgs, 30_000L);
        out.put("package", pkg);
        return out;
    }

    /**
     * The confirmation prompt shows the script itself - it is the thing being
     * approved. Capped so a runaway paste cannot push the buttons off screen.
     */
    private static String forPrompt(String source) {
        int limit = 4000;
        return source.length() <= limit ? source
                : source.substring(0, limit) + "\n... (" + (source.length() - limit)
                        + " more characters)";
    }

    private void requireConfirmation(ConfirmationGate.Kind kind, String title, String detail,
            String reason) throws McpTool.ToolError {
        boolean willPrompt = ConfirmationGate.willPrompt(context, kind);
        ConfirmationGate.Decision decision = ConfirmationGate.request(context,
                new ConfirmationGate.Request(kind, title, detail, reason, requester(),
                        prefs.confirmTimeoutMs()));
        if (!decision.approved) {
            throw new McpTool.ToolError("Refused: " + decision.note
                    + ". The action was not performed. Ask the user what they would prefer"
                    + " instead of retrying.");
        }
        if (decision.viaHandoff) {
            // Hand-off mode answered this, so the user saw nothing. The banner is
            // the whole report: without it, a 15-minute window of unattended root
            // access leaves no trace they would ever run into.
            String tool = CURRENT_TOOL.get();
            AuditNotifier.action(context, tool == null ? kind.name().toLowerCase(Locale.ROOT) : tool,
                    detail);
        }
        if (willPrompt) {
            // The approval window is gone as far as the window manager is
            // concerned the instant the user taps the button, but the display is
            // a frame or two behind that. A capture taken immediately after
            // contains the approval prompt itself - which is exactly the thing
            // the agent was about to read. Only waited for when a prompt was
            // really shown, so a relaxed setting costs nothing.
            sleepQuietly(OVERLAY_SETTLE_MS);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void requireSystemBridge(String tool) throws McpTool.ToolError {
        if (!capabilities.systemOnline()) {
            throw new McpTool.ToolError(tool + " needs the system bridge, which is not connected."
                    + " Enable the PosEdMCP module in LSPosed Manager with scope including"
                    + " \"System Framework\", reboot or restart system_server, then check"
                    + " module_status again.");
        }
    }

    /** The name a prompt gives the target: the framework is not a package to a user. */
    private static String targetName(String pkg) {
        return SavedHook.isSystem(pkg) ? "system_server" : pkg;
    }

    /**
     * The paragraph a prompt gets when the target is the system framework.
     *
     * <p>Only ever shown to the person tapping approve, never in a tool
     * description: an agent that has not chosen this target should not be nudged
     * towards it, and whoever has to judge the request should not need to know
     * that "android" means the system.
     */
    private String systemTargetWarning(String pkg, boolean persist) {
        if (!SavedHook.isSystem(pkg)) {
            return "";
        }
        String what = "This is system_server, not an app - the process the whole system runs in."
                + " A hook that throws here can take it down, and with it everything on screen.";
        if (!persist) {
            return "\n\n" + what + " This one is not kept, so restarting the system clears it.";
        }
        return "\n\n" + what + " This one IS kept: it is put back before you can open the app that"
                + " would remove it, which is how a bad hook becomes a phone that cannot finish"
                + " booting."
                + (HookGuard.guardInstalled()
                        ? " The posedmcp-guard module is installed and suspends system hooks by"
                                + " itself after repeated failed boots."
                        : " The posedmcp-guard rescue module is NOT installed, so if this goes"
                                + " wrong there is nothing to fall back on but recovery.");
    }

    /**
     * Refuses to <i>keep</i> a hook in system_server while the guard has it suspended.
     *
     * <p>Only kept ones. The suspension is about a hook that is put back before
     * the user can reach the app, which is the shape that can leave a device
     * unable to boot; a hook that dies with the process cannot do that, and
     * refusing it too would take away the one way left to look at the thing that
     * went wrong.
     */
    private void refuseIfSuspended(String pkg, boolean persist) throws McpTool.ToolError {
        if (!persist || !SavedHook.isSystem(pkg) || !HookGuard.suspended(context)) {
            return;
        }
        throw new McpTool.ToolError("kept system hooks are suspended on this device. The"
                + " posedmcp-guard rescue module switched them off after the device failed to"
                + " finish booting, and only the user can lift that, on the Hooks page in the"
                + " app. Its reason: " + HookGuard.suspension(context)
                + " A persist=false hook is still allowed - it cannot outlive the process.");
    }

    /**
     * Fails unless something is connected that can serve an op for this package.
     *
     * <p>The two ways to fail have nothing to do with each other and get told
     * apart, because the old single message actively misled: for
     * {@link SavedHook#SYSTEM_PACKAGE} it said "add the package to the module's
     * scope", which is advice to do something that was already done -
     * system_server is in scope and the module is loaded into it, it simply had
     * no peer of its own to be addressed by.
     */
    private void requirePeer(String pkg) throws McpTool.ToolError {
        if (bridge.hasPeer(pkg)) {
            return;
        }
        if (SavedHook.isSystem(pkg)) {
            throw new McpTool.ToolError("the module is not running inside system_server,"
                    + " so nothing can be hooked there. Give PosEdMCP \"System Framework\" in its"
                    + " scope in LSPosed Manager, then reboot - restarting system_server alone is"
                    + " not always enough - and check module_status for a 'system' peer.");
        }
        throw new McpTool.ToolError("no PosEdMCP module inside '" + pkg + "'."
                + " Add the package to the module's scope in LSPosed Manager, then start (or"
                + " restart) that app so the module can load into its process.");
    }

    private JSONObject moduleStatusJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("systemBridgeConnected", capabilities.systemOnline());
            JSONArray peers = new JSONArray();
            for (String key : bridge.connectedKeys()) {
                peers.put(key);
            }
            out.put("bridgePeers", peers);
            out.put("bridgePort", bridge.port());
            out.put("mcpPort", prefs.mcpPort());
            out.put("accessibility", AccessibilityBridge.describe(context));
            if (!AccessibilityBridge.isConnected()) {
                out.put("accessibilityHint",
                        "Without the accessibility service the platform freezes this app once it"
                                + " leaves the screen, so the MCP endpoint stops answering exactly"
                                + " when an agent in another app needs it.");
                if (AccessibilityBridge.STATE_FAULTED.equals(AccessibilityBridge.state(context))) {
                    // Worth saying precisely, because the obvious advice is wrong:
                    // the setting already reads as on, so "turn it on" does
                    // nothing and the user concludes the app is broken.
                    out.put("accessibilityRepair",
                            "The service is still switched on, but the system marked it"
                                    + " malfunctioning - it does that whenever this app's process"
                                    + " is killed - and will not bind it again. Turning it on in"
                                    + " Settings will not help; it has to be switched off and on."
                                    + " The user can do that in the app: Status tab,"
                                    + " \"Repair accessibility…\".");
                }
            }
            out.put("confirmations", confirmationsJson());
            if (capabilities.systemOnline()) {
                try {
                    out.put("system", capabilities.systemStatus());
                } catch (Throwable t) {
                    out.put("systemError", String.valueOf(t.getMessage()));
                }
                try {
                    out.put("displayProbe", capabilities.systemDisplayProbe());
                } catch (Throwable t) {
                    out.put("displayProbeError", String.valueOf(t.getMessage()));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private JSONObject confirmationsJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("root_shell_exec", "always prompts");
            o.put("screen_capture", prefs.confirmScreen() ? "prompts" : "not prompted");
            o.put("ui_dump", "always prompts (runs a shell command)");
            o.put("input_inject", prefs.confirmInput() ? "prompts" : "not prompted (system mode)");
            o.put("plugin_load/plugin_invoke", prefs.confirmPlugin() ? "prompts" : "not prompted");
            o.put("lua_exec", prefs.confirmPlugin() ? "prompts" : "not prompted");
            o.put("confirmTimeoutMs", prefs.confirmTimeoutMs());
            // Hand-off mode is deliberately not reported here. Telling the agent
            // "nobody is checking right now" is exactly the context that invites
            // it to take liberties, and the caveat is covered from the other
            // side instead: the prompt tells it to treat its own judgement as the
            // last line of defence, never the dialog. See the README.
        } catch (Throwable ignored) {
        }
        return o;
    }

    private JSONArray listPackages(String filter, boolean includeSystem, int limit) {
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.GET_META_DATA;
        List<PackageInfo> installed = pm.getInstalledPackages(flags);
        JSONArray array = new JSONArray();
        for (PackageInfo info : installed) {
            if (array.length() >= Math.max(1, Math.min(limit, MAX_PACKAGES))) {
                break;
            }
            ApplicationInfo app = info.applicationInfo;
            if (app == null) {
                continue;
            }
            boolean system = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (system && !includeSystem) {
                continue;
            }
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(app));
            } catch (Throwable t) {
                label = info.packageName;
            }
            if (!filter.isEmpty()
                    && !info.packageName.toLowerCase(Locale.ROOT).contains(filter)
                    && !label.toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            JSONObject entry = new JSONObject();
            try {
                entry.put("package", info.packageName);
                entry.put("label", label);
                entry.put("system", system);
                entry.put("uid", app.uid);
                entry.put("enabled", app.enabled);
                entry.put("moduleLoaded", bridge.hasPeer(info.packageName));
            } catch (Throwable ignored) {
            }
            array.put(entry);
        }
        return array;
    }

    /**
     * Normalises analysis arguments so a target can be named either way.
     *
     * <p>A package is friendlier for an agent that just listed the installed
     * apps; a path is what it needs for a file it produced itself.
     */
    private JSONObject dexArgs(JSONObject args) throws Exception {
        JSONObject out = new JSONObject(args.toString());
        String path = args.optString("path", "");
        if (path.isEmpty()) {
            out.put("path", resolveSourcePath(args.optString("package", "")));
        }
        out.remove("package");
        return out;
    }

    private String resolveSourcePath(String pkg) throws McpTool.ToolError {
        if (pkg.isEmpty()) {
            throw new McpTool.ToolError("name the target with either 'package' or 'path'");
        }
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            if (info.sourceDir == null || info.sourceDir.isEmpty()) {
                throw new McpTool.ToolError("no APK path reported for " + pkg);
            }
            return info.sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            throw new McpTool.ToolError("no such package: " + pkg);
        }
    }

    /**
     * Takes the payload either as bytes or as a path.
     *
     * <p>The path form is what makes the on-device loop work: smali_assemble
     * writes a DEX and plugin_load picks it up, with no base64 round trip through
     * the conversation.
     */
    private byte[] readDex(JSONObject args) throws McpTool.ToolError {
        String path = args.optString("dex_path", "");
        if (!path.isEmpty()) {
            File file = new File(path);
            if (!file.canRead()) {
                throw new McpTool.ToolError("cannot read dex_path: " + path);
            }
            if (file.length() > MAX_DEX_BYTES) {
                throw new McpTool.ToolError("dex_path is larger than "
                        + (MAX_DEX_BYTES / (1024 * 1024)) + " MB");
            }
            try {
                return java.nio.file.Files.readAllBytes(file.toPath());
            } catch (Throwable t) {
                throw new McpTool.ToolError("could not read dex_path: " + t.getMessage());
            }
        }
        String base64 = args.optString("dex_base64", "");
        if (base64.isEmpty()) {
            throw new McpTool.ToolError("provide either dex_base64 or dex_path");
        }
        try {
            return android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        } catch (Throwable t) {
            throw new McpTool.ToolError("dex_base64 is not valid base64");
        }
    }

    /** Distinct application packages that currently have a bridge peer. */    private java.util.Set<String> connectedPackages() {
        java.util.Set<String> packages = new java.util.LinkedHashSet<>();
        for (String key : bridge.connectedKeys()) {
            // Keys look like "app:<pkg>:<pid>".
            if (!key.startsWith("app:")) {
                continue;
            }
            String rest = key.substring(4);
            int sep = rest.lastIndexOf(':');
            packages.add(sep > 0 ? rest.substring(0, sep) : rest);
        }
        packages.remove("android");
        return packages;
    }

    private static String normalizeMode(String mode) {
        String m = mode == null ? "" : mode.toLowerCase(Locale.ROOT);
        if (Capabilities.MODE_SYSTEM.equals(m) || Capabilities.MODE_ROOT.equals(m)
                || Capabilities.MODE_A11Y.equals(m)) {
            return m;
        }
        return Capabilities.MODE_AUTO;
    }

    /** Builds the equivalent {@code input} command for the root route. */
    private static String buildInputCommand(String action, JSONObject args) throws McpTool.ToolError {
        switch (action) {
            case "tap":
                return "input tap " + requireInt(args, "x") + " " + requireInt(args, "y");
            case "long_press": {
                int duration = args.optInt("duration_ms", 800);
                return "input swipe " + requireInt(args, "x") + " " + requireInt(args, "y")
                        + " " + requireInt(args, "x") + " " + requireInt(args, "y") + " " + duration;
            }
            case "swipe": {
                int duration = args.optInt("duration_ms", 300);
                return "input swipe " + requireInt(args, "x") + " " + requireInt(args, "y")
                        + " " + requireInt(args, "x2") + " " + requireInt(args, "y2") + " " + duration;
            }
            case "text": {
                String text = require(args, "text");
                return "input text " + shellQuote(text.replace(" ", "%s"));
            }
            case "key": {
                String keycode = require(args, "keycode");
                return "input keyevent " + shellQuote(keycode);
            }
            default:
                throw new McpTool.ToolError("unsupported action '" + action
                        + "'. Use tap, swipe, long_press, text or key.");
        }
    }

    /**
     * Condenses a per-process fan-out into one answer.
     *
     * <p>Reporting a raw list of processes would leave the caller to work out
     * whether the operation actually took, and on how many of them.
     */
    private static JSONObject summarizeAcrossProcesses(JSONArray results, String countKey,
            String note) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray detail = new JSONArray();
        int succeeded = 0;
        for (int i = 0; i < results.length(); i++) {
            JSONObject entry = results.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            boolean ok = entry.optBoolean("ok", false);
            if (ok) {
                succeeded++;
            }
            JSONObject row = new JSONObject();
            row.put("process", entry.optString("process", ""));
            row.put("ok", ok);
            if (ok) {
                JSONObject result = entry.optJSONObject("result");
                if (result != null) {
                    row.put("result", result);
                }
            } else {
                row.put("error", entry.optString("error", ""));
            }
            detail.put(row);
        }
        out.put("processes", results.length());
        out.put(countKey, succeeded);
        if (note != null) {
            out.put("note", note);
        }
        if (succeeded < results.length()) {
            out.put("partial", true);
        }
        out.put("detail", detail);
        return out;
    }

    /** Copies items into a combined list, recording which process each came from. */
    private static void tag(String field, String value, JSONArray from, JSONArray into)
            throws Exception {
        if (from == null) {
            return;
        }
        for (int i = 0; i < from.length(); i++) {
            JSONObject item = from.optJSONObject(i);
            if (item == null) {
                continue;
            }
            item.put(field, value);
            into.put(item);
        }
    }

    /**
     * True the first time a record is seen.
     *
     * <p>A record reaches the app as it is made and also stays in the process
     * that made it, so while that process lives the same call arrives down both
     * paths. Its seq identifies it within that process, which is why the source
     * is part of the key: two apps, or two runs of one app, count independently.
     * A record without a seq comes from a build that did not write one, and is
     * taken at face value rather than silently folded into its neighbour.
     */
    private static boolean remember(Set<String> seen, JSONObject record) {
        long seq = record.optLong("seq", 0L);
        if (seq <= 0L) {
            return true;
        }
        return seen.add(record.optString("source", "") + "#" + seq);
    }

    /**
     * Renders the modification part of a hook request for the confirmation dialog.
     *
     * <p>Observing needs no explanation, but changing what a method does is the
     * kind of thing the user should see spelled out before agreeing to it.
     *
     * @return {@code null} when the hook only observes
     */
    private static String describeHookEffect(JSONObject args) {
        List<String> parts = new ArrayList<>();
        String returnValue = args.optString("return_value", "");
        if (!returnValue.isEmpty()) {
            parts.add("Always return " + returnValue + " (the original will not run)");
        }
        String setArgs = args.optString("set_args", "");
        if (!setArgs.isEmpty()) {
            parts.add("Replace arguments: " + setArgs);
        }
        String setFields = args.optString("set_fields", "");
        if (!setFields.isEmpty()) {
            parts.add("Assign fields after the call: " + setFields);
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    /**
     * Drops the saved definitions matching a clear request.
     *
     * <p>A kept hook re-arms itself, so clearing only the live process would
     * bring it back the next time that app starts. "Clear" has to mean the hook
     * is gone, not that it is gone until the app is restarted.
     */
    private JSONArray forgetHooks(String pkg, String subject) throws Exception {
        HookStore store = HookStore.of(context);
        String needle = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        JSONArray gone = new JSONArray();
        for (SavedHook hook : store.forPackage(pkg)) {
            if (hook.isSpecial()) {
                // This app's own countermeasures are not an agent's to remove. They
                // are kept in the same library so that they come back after a
                // restart, and the switch in Special settings can pause one - but
                // deleting is not offered there either, on purpose: a
                // countermeasure that can be removed by accident is one somebody
                // then has to work out how to restore.
                continue;
            }
            if (needle.isEmpty() || hook.target().toLowerCase(Locale.ROOT).contains(needle)) {
                store.delete(hook.id);
                gone.put(hook.target());
            }
        }
        return gone;
    }

    /**
     * The single hook a Lua script registered, or {@code null} if it registered
     * any other number.
     *
     * <p>Exactly one, on purpose: a kept Lua hook arms itself by being run again,
     * so a script holding two would be run twice on every start and would have
     * no single target to show the user.
     */
    private static SavedHook registeredHook(JSONArray across) throws Exception {
        Map<String, SavedHook> distinct = new LinkedHashMap<>();
        for (int i = 0; i < across.length(); i++) {
            JSONObject entry = across.optJSONObject(i);
            if (entry == null || !entry.optBoolean("ok", false)) {
                continue;
            }
            JSONObject result = entry.optJSONObject("result");
            JSONArray installed = result == null ? null : result.optJSONArray("hooksInstalled");
            if (installed == null) {
                continue;
            }
            for (int j = 0; j < installed.length(); j++) {
                JSONObject item = installed.optJSONObject(j);
                if (item == null) {
                    continue;
                }
                SavedHook hook = new SavedHook();
                hook.className = item.optString("class", "");
                hook.methodName = item.optString("method", "");
                hook.params = item.optString("params", "");
                hook.effect = item.optString("effect", "");
                distinct.put(hook.className + "#" + hook.methodName, hook);
            }
        }
        return distinct.size() == 1 ? distinct.values().iterator().next() : null;
    }

    private static String describeInputTarget(String action, JSONObject args) {        switch (action) {
            case "tap":
                return "at (" + args.optInt("x") + "," + args.optInt("y") + ")";
            case "long_press":
                return "at (" + args.optInt("x") + "," + args.optInt("y") + ")";
            case "swipe":
                return "from (" + args.optInt("x") + "," + args.optInt("y") + ") to ("
                        + args.optInt("x2") + "," + args.optInt("y2") + ")";
            case "text":
                return "typing \"" + args.optString("text") + "\"";
            case "key":
                return "key " + args.optString("keycode");
            default:
                return action;
        }
    }

    /** Single-quotes a value for safe inclusion in a shell command. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * Input through the accessibility service.
     *
     * <p>Gestures and global keys both go through APIs only this service may use,
     * which is why they need no root. Text goes through {@code ACTION_SET_TEXT}
     * on the focused field, which is both cleaner and more reliable than passing
     * a string to a shell command.
     */
    private JSONObject accessibilityInput(String action, JSONObject args, String reason)
            throws Exception {
        requireConfirmation(ConfirmationGate.Kind.INPUT, "Inject input into the device",
                "Send " + action + " " + describeInputTarget(action, args) + " to the foreground app"
                        + " as " + requester() + " (through the accessibility service).", reason);

        switch (action) {
            case "tap":
                AccessibilityBridge.gesture(requireInt(args, "x"), requireInt(args, "y"),
                        requireInt(args, "x"), requireInt(args, "y"), 60);
                break;
            case "long_press": {
                int x = requireInt(args, "x");
                int y = requireInt(args, "y");
                AccessibilityBridge.gesture(x, y, x, y, args.optInt("duration_ms", 800));
                break;
            }
            case "swipe":
                AccessibilityBridge.gesture(requireInt(args, "x"), requireInt(args, "y"),
                        requireInt(args, "x2"), requireInt(args, "y2"),
                        Math.max(1, args.optInt("duration_ms", 300)));
                break;
            case "text":
                AccessibilityBridge.setText(require(args, "text"));
                break;
            case "key": {
                int global = globalActionFor(args.optString("keycode", ""));
                if (global == 0) {
                    // Volume, media and other keys have no accessibility action;
                    // say so plainly so the caller can pick another route.
                    throw new IOException("'" + args.optString("keycode")
                            + "' has no accessibility equivalent");
                }
                AccessibilityBridge.globalAction(global);
                break;
            }
            default:
                throw new McpTool.ToolError("unsupported action '" + action
                        + "'. Use tap, swipe, long_press, text or key.");
        }

        JSONObject out = new JSONObject();
        out.put("route", Capabilities.MODE_A11Y);
        out.put("injected", true);
        out.put("action", action);
        return out;
    }

    /** Maps a key name onto the accessibility global action that performs it. */
    private static int globalActionFor(String keycode) {
        String key = keycode == null ? "" : keycode.trim().toUpperCase(Locale.ROOT);
        if (key.startsWith("KEYCODE_")) {
            key = key.substring("KEYCODE_".length());
        }
        switch (key) {
            case "HOME": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;
            case "BACK": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK;
            case "RECENTS":
            case "APP_SWITCH": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
            case "NOTIFICATIONS": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
            case "QUICK_SETTINGS": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
            case "POWER_DIALOG": return android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
            default: return 0;
        }
    }

    private static int requireInt(JSONObject args, String name) throws McpTool.ToolError {
        if (!args.has(name)) {
            throw new McpTool.ToolError("missing required argument '" + name + "'");
        }
        return args.optInt(name, 0);
    }

    private static String require(JSONObject args, String name) throws McpTool.ToolError {
        String value = args.optString(name, "");
        if (value.trim().isEmpty()) {
            throw new McpTool.ToolError("missing required argument '" + name + "'");
        }
        return value;
    }

    private static JSONObject props(Object... pairs) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                o.put((String) pairs[i], pairs[i + 1]);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** A string property restricted to a fixed set of values. */
    private static JSONObject enumOf(String description, String... values) {
        JSONObject o = McpTool.string(description);
        try {
            JSONArray array = new JSONArray();
            for (String value : values) {
                array.put(value);
            }
            o.put("enum", array);
        } catch (Throwable ignored) {
        }
        return o;
    }

    // =====================================================================
    // uiautomator dump parsing
    // =====================================================================

    /**
     * Turns the {@code uiautomator} hierarchy XML into a compact tree.
     *
     * <p>With {@code simplify}, nodes carrying no text, id or interaction are
     * dropped and their children are re-parented. A raw dump is mostly layout
     * scaffolding, and an agent pays for every token of it.
     */
    static JSONObject parseUiDump(String xml, boolean simplify) throws McpTool.ToolError {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "UTF-8");

            JSONObject virtualRoot = new JSONObject();
            Deque<JSONObject> parents = new ArrayDeque<>();
            parents.push(virtualRoot);
            int nodes = 0;
            boolean truncated = false;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "node".equals(parser.getName())) {
                    if (nodes++ >= MAX_UI_NODES) {
                        truncated = true;
                        break;
                    }
                    JSONObject node = nodeFromAttributes(parser);
                    JSONObject parent = parents.peek();
                    if (!simplify || isInteresting(node)) {
                        childrenOf(parent).put(node);
                        parents.push(node);
                    } else {
                        // Transparent: this node disappears, its children do not.
                        parents.push(parent);
                    }
                } else if (event == XmlPullParser.END_TAG && "node".equals(parser.getName())) {
                    if (parents.size() > 1) {
                        parents.pop();
                    }
                }
                event = parser.next();
            }

            JSONObject out = new JSONObject();
            JSONArray roots = virtualRoot.optJSONArray("children");
            out.put("node", roots == null || roots.length() == 0 ? new JSONObject() : roots.get(0));
            if (truncated) {
                out.put("truncated", true);
                out.put("note", "Stopped after " + MAX_UI_NODES + " nodes.");
            }
            return out;
        } catch (McpTool.ToolError e) {
            throw e;
        } catch (Throwable t) {
            throw new McpTool.ToolError("could not parse the uiautomator dump: " + t);
        }
    }

    private static JSONArray childrenOf(JSONObject parent) throws Exception {
        JSONArray children = parent.optJSONArray("children");
        if (children == null) {
            children = new JSONArray();
            parent.put("children", children);
        }
        return children;
    }

    private static JSONObject nodeFromAttributes(XmlPullParser parser) throws Exception {
        JSONObject node = new JSONObject();
        String text = attr(parser, "text");
        String desc = attr(parser, "content-desc");
        String resId = attr(parser, "resource-id");
        String cls = attr(parser, "class");
        String pkg = attr(parser, "package");
        String bounds = attr(parser, "bounds");
        boolean clickable = "true".equals(attr(parser, "clickable"));
        boolean focusable = "true".equals(attr(parser, "focusable"));
        boolean scrollable = "true".equals(attr(parser, "scrollable"));
        boolean enabled = "true".equals(attr(parser, "enabled"));

        if (!text.isEmpty()) {
            node.put("text", text);
        }
        if (!desc.isEmpty()) {
            node.put("desc", desc);
        }
        if (!resId.isEmpty()) {
            node.put("id", resId);
        }
        if (!cls.isEmpty()) {
            node.put("class", cls);
        }
        if (!pkg.isEmpty()) {
            node.put("package", pkg);
        }
        if (!bounds.isEmpty()) {
            node.put("bounds", bounds);
            int[] centre = centreOf(bounds);
            if (centre != null) {
                JSONObject c = new JSONObject();
                c.put("x", centre[0]);
                c.put("y", centre[1]);
                node.put("center", c);
            }
        }
        if (clickable) {
            node.put("clickable", true);
        }
        if (focusable) {
            node.put("focusable", true);
        }
        if (scrollable) {
            node.put("scrollable", true);
        }
        if (!enabled) {
            node.put("enabled", false);
        }
        return node;
    }

    /** Nodes worth showing: they carry text, or the user can interact with them. */
    private static boolean isInteresting(JSONObject node) {
        return node.has("text") || node.has("desc") || node.has("id")
                || node.optBoolean("clickable", false) || node.optBoolean("scrollable", false);
    }

    /** Parses {@code [x1,y1][x2,y2]} into its centre point. */
    private static int[] centreOf(String bounds) {
        try {
            String cleaned = bounds.replace("[", "").replace("]", ",");
            String[] parts = cleaned.split(",");
            List<Integer> nums = new ArrayList<>();
            for (String p : parts) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) {
                    nums.add(Integer.parseInt(trimmed));
                }
            }
            if (nums.size() < 4) {
                return null;
            }
            return new int[]{(nums.get(0) + nums.get(2)) / 2, (nums.get(1) + nums.get(3)) / 2};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String attr(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        return value == null ? "" : value;
    }

    /** Exposed for the UI: a stable snapshot of what is registered. */
    public Map<String, String> summary() {
        Map<String, String> out = new LinkedHashMap<>();
        for (McpTool tool : tools.values()) {
            out.put(tool.name, tool.readOnly ? "read-only" : "prompts");
        }
        return Collections.unmodifiableMap(out);
    }
}
