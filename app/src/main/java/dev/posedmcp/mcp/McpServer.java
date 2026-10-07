package dev.posedmcp.mcp;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import dev.posedmcp.Logx;
import dev.posedmcp.state.EventStore;
import dev.posedmcp.state.Prefs;

/**
 * MCP protocol handling: JSON-RPC 2.0 over the Streamable HTTP transport.
 *
 * <p>Stateless: every request carries its own bearer token and no session id is
 * issued, which keeps reconnects and process restarts from stranding a client.
 */
public final class McpServer {

    public static final String SERVER_NAME = "posedmcp";
    public static final String SERVER_VERSION = "1.0.3";

    /** Newest first. The negotiated version is the client's if we know it. */
    private static final String[] SUPPORTED_PROTOCOL_VERSIONS = {
            "2025-06-18", "2025-03-26", "2024-11-05",
    };

    private static final String INSTRUCTIONS = String.join("\n",
            "PosEdMCP controls this Android device at system level, on behalf of the device owner.",
            "",
            "How to work with this server:",
            "1. Prefer the narrowest tool that answers the question. Use device_info, list_packages,",
            "   foreground_app, events_poll and probe_state to understand state before you act.",
            "2. root_shell_exec runs as uid 0 and ALWAYS shows the user a dialog containing the exact",
            "   command and your stated reason. The user must tap approve. A refused or timed-out",
            "   request comes back as an error - report it plainly, do not retry the same command in a",
            "   loop, and never try to route around the confirmation with another tool.",
            "   Do not treat that dialog as your safety net. It exists for the user to judge your",
            "   command, not to catch your mistakes: assume every command you send may execute",
            "   exactly as written, and never send one you would not stand behind if it ran the",
            "   instant you sent it and nobody read it first.",
            "3. Always pass a specific, truthful reason. The user is deciding whether to run your",
            "   command; a vague reason is a reason to decline. Write it in the user's language.",
            "4. Do not batch unrelated commands into one shell string to reduce the number of prompts.",
            "   That hides what is being run and defeats the point of the confirmation.",
            "5. Anything that reads the screen (screen_capture, ui_dump) or synthesises input",
            "   (input_inject) also prompts by default, because it can reach private data on screen.",
            "6. Tools marked readOnly do not prompt and are safe to call freely.",
            "7. The probe: the user can freeze the app in front with the floating probe button,",
            "   which pins a transient screen (a splash ad) in place for inspection. probe_state",
            "   reads the frozen app - threads, stacks, loaded libraries with SDK hints, and the",
            "   view tree captured at freeze time; probe_resume releases it. You can never start",
            "   a freeze yourself. While an app is frozen, screen_capture sees it and ui_dump",
            "   cannot: the view tree inside probe_state is the only copy.",
            "",
            "Scope: this device is the user's own, already rooted and running LSPosed. The user has",
            "explicitly installed this server to let an agent operate the device. That authorisation",
            "covers this device only, and does not extend to destructive commands the user has not",
            "confirmed - the confirmation dialog is the authoritative check, not your own judgement.");

    private final Context context;
    private final Prefs prefs;
    private final ToolRegistry tools;
    private final EventStore events;
    private final AtomicLong started = new AtomicLong(System.currentTimeMillis());
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "posedmcp-tool");
        t.setDaemon(true);
        return t;
    });

    private volatile HttpTransport transport;

    public McpServer(Context context, Prefs prefs, ToolRegistry tools, EventStore events) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.tools = tools;
        this.events = events;
    }

    public int port() {
        return prefs.mcpPort();
    }

    public boolean isRunning() {
        HttpTransport t = transport;
        return t != null && t.isRunning();
    }

    public synchronized void start() throws Exception {
        if (isRunning()) {
            return;
        }
        HttpTransport t = new HttpTransport(prefs.mcpPort(), this::route);
        t.start();
        transport = t;

        events.setListener(entry -> {
            HttpTransport current = transport;
            if (current == null || current.openStreamCount() == 0) {
                return;
            }
            JSONObject notification = new JSONObject();
            try {
                notification.put("jsonrpc", "2.0");
                notification.put("method", "notifications/message");
                JSONObject params = new JSONObject();
                params.put("level", "info");
                params.put("logger", "posedmcp.events");
                params.put("data", entry.toJson());
                notification.put("params", params);
            } catch (Throwable ignored) {
                return;
            }
            current.broadcast(null, notification.toString());
        });
    }

    public synchronized void stop() {
        HttpTransport t = transport;
        transport = null;
        if (t != null) {
            t.stop();
        }
        events.setListener(null);
    }

    private HttpTransport.Response route(HttpTransport.Request request) {
        String path = request.path;
        if ("/health".equals(path)) {
            JSONObject health = new JSONObject();
            try {
                health.put("status", "ok");
                health.put("server", SERVER_NAME);
                health.put("version", SERVER_VERSION);
                health.put("tools", tools.size());
                health.put("uptimeMs", System.currentTimeMillis() - started.get());
            } catch (Throwable ignored) {
            }
            return HttpTransport.Response.json(200, health.toString());
        }

        if ("OPTIONS".equals(request.method)) {
            // No CORS headers on purpose; see HttpTransport.
            return HttpTransport.Response.empty(204);
        }

        if (!"/mcp".equals(path) && !"/sse".equals(path) && !"/messages".equals(path)) {
            return HttpTransport.Response.json(404, "{\"error\":\"no such endpoint\"}");
        }

        if (!authorized(request)) {
            return HttpTransport.Response.json(401,
                    "{\"error\":\"missing or invalid bearer token\"}");
        }

        if ("GET".equals(request.method)) {
            return openEventStream();
        }
        if (!"POST".equals(request.method)) {
            return HttpTransport.Response.json(405, "{\"error\":\"method not allowed\"}");
        }

        String response;
        try {
            response = handlePayload(request.body);
        } catch (Throwable t) {
            Logx.e("json-rpc handling failed", t);
            response = errorResponse(null, -32603, "internal error: " + t.getMessage());
        }
        if (response == null) {
            return HttpTransport.Response.empty(202);
        }
        return HttpTransport.Response.json(200, response);
    }

    private HttpTransport.Response openEventStream() {
        return HttpTransport.Response.sse(sink -> {
            // An SSE stream is only useful for server-initiated notifications.
            // Announce readiness, then idle: HttpTransport keeps the socket.
            JSONObject ready = new JSONObject();
            try {
                ready.put("jsonrpc", "2.0");
                ready.put("method", "notifications/message");
                JSONObject params = new JSONObject();
                params.put("level", "info");
                params.put("logger", "posedmcp");
                params.put("data", "event stream open");
                ready.put("params", params);
            } catch (Throwable ignored) {
            }
            sink.send(null, ready.toString());
            try {
                while (sink.isOpen()) {
                    Thread.sleep(15_000L);
                    sink.send(null, "");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private boolean authorized(HttpTransport.Request request) {
        String expected = prefs.mcpToken();
        if (expected == null || expected.isEmpty()) {
            // Tokens are created at service start; reaching here means they are
            // missing, and accepting anything would expose an unauthenticated
            // root bridge.
            Logx.e("refusing MCP request: no token has been generated");
            return false;
        }
        String header = request.header("authorization");
        String presented = null;
        if (header != null && header.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
            presented = header.substring(7).trim();
        }
        if (presented == null) {
            // EventSource cannot set headers, so the query string is accepted
            // for the read-only event stream.
            presented = request.query.get("token");
        }
        return presented != null && constantTimeEquals(presented, expected);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** Handles one request or a batch; returns {@code null} when there is nothing to send. */
    private String handlePayload(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return errorResponse(null, -32700, "empty request body");
        }
        try {
            if (trimmed.startsWith("[")) {
                JSONArray batch = new JSONArray(trimmed);
                JSONArray responses = new JSONArray();
                for (int i = 0; i < batch.length(); i++) {
                    JSONObject request = batch.optJSONObject(i);
                    if (request == null) {
                        continue;
                    }
                    String response = handleOne(request);
                    if (response != null) {
                        responses.put(new JSONObject(response));
                    }
                }
                return responses.length() == 0 ? null : responses.toString();
            }
            JSONObject request = new JSONObject(trimmed);
            return handleOne(request);
        } catch (Throwable t) {
            return errorResponse(null, -32700, "parse error: " + t.getMessage());
        }
    }

    private String handleOne(JSONObject request) {
        Object id = request.opt("id");
        String method = request.optString("method", "");
        if (method.isEmpty()) {
            return errorResponse(id, -32600, "missing method");
        }
        boolean notification = id == null;
        JSONObject params = request.optJSONObject("params");
        if (params == null) {
            params = new JSONObject();
        }

        try {
            switch (method) {
                case "initialize":
                    return notification ? null : resultResponse(id, initialize(params));
                case "notifications/initialized":
                case "notifications/cancelled":
                    return null;
                case "ping":
                    return notification ? null : resultResponse(id, new JSONObject());
                case "tools/list":
                    return notification ? null : resultResponse(id, listTools());
                case "tools/call":
                    return notification ? null : resultResponse(id, callTool(params));
                case "resources/list":
                    return notification ? null
                            : resultResponse(id, new JSONObject().put("resources", new JSONArray()));
                case "prompts/list":
                    return notification ? null
                            : resultResponse(id, new JSONObject().put("prompts", new JSONArray()));
                case "logging/setLevel":
                    return notification ? null : resultResponse(id, new JSONObject());
                default:
                    return notification ? null
                            : errorResponse(id, -32601, "unknown method: " + method);
            }
        } catch (Throwable t) {
            Logx.e("method " + method + " failed", t);
            return notification ? null
                    : errorResponse(id, -32603, String.valueOf(t.getMessage()));
        }
    }

    private JSONObject initialize(JSONObject params) throws Exception {
        String requested = params.optString("protocolVersion", "");
        String negotiated = negotiate(requested);

        // Remember who is talking, so confirmation prompts can name the caller
        // instead of showing an anonymous "an MCP client".
        JSONObject clientInfo = params.optJSONObject("clientInfo");
        if (clientInfo != null) {
            String name = clientInfo.optString("name", "");
            String version = clientInfo.optString("version", "");
            if (!name.isEmpty()) {
                ToolRegistry.setRequester(version.isEmpty() ? name : name + " " + version);
            }
        }

        JSONObject capabilities = new JSONObject();
        JSONObject toolsCap = new JSONObject();
        toolsCap.put("listChanged", false);
        capabilities.put("tools", toolsCap);
        capabilities.put("logging", new JSONObject());

        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("title", "PosEdMCP (Android root agent bridge)");
        serverInfo.put("version", SERVER_VERSION);

        JSONObject result = new JSONObject();
        result.put("protocolVersion", negotiated);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        result.put("instructions", INSTRUCTIONS);
        return result;
    }

    private static String negotiate(String requested) {
        for (String supported : SUPPORTED_PROTOCOL_VERSIONS) {
            if (supported.equals(requested)) {
                return supported;
            }
        }
        return SUPPORTED_PROTOCOL_VERSIONS[0];
    }

    private JSONObject listTools() throws Exception {
        JSONArray array = new JSONArray();
        for (McpTool tool : tools.all()) {
            array.put(tool.describe());
        }
        JSONObject result = new JSONObject();
        result.put("tools", array);
        return result;
    }

    /**
     * Tool calls run on their own thread: several of them block on a human
     * tapping a dialog, and that must not stall the connection they arrived on.
     */
    private JSONObject callTool(JSONObject params) throws Exception {
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) {
            args = new JSONObject();
        }
        McpTool tool = tools.get(name);
        if (tool == null) {
            return McpTool.error("unknown tool '" + name + "'. Call tools/list to see what exists.");
        }

        final JSONObject finalArgs = args;
        try {
            return workers.submit(() -> {
                ToolRegistry.enterTool(tool.name);
                try {
                    JSONObject produced = tool.handler.call(finalArgs);
                    return produced == null ? McpTool.text("ok") : produced;
                } catch (McpTool.ToolError e) {
                    return McpTool.error(e.getMessage());
                } catch (Throwable t) {
                    Logx.e("tool " + tool.name + " failed", t);
                    return McpTool.error(tool.name + " failed: "
                            + (t.getMessage() == null ? t.toString() : t.getMessage()));
                } finally {
                    ToolRegistry.exitTool();
                }
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return McpTool.error("interrupted");
        }
    }

    private static String resultResponse(Object id, JSONObject result) {
        JSONObject o = new JSONObject();
        try {
            o.put("jsonrpc", "2.0");
            o.put("id", id == null ? JSONObject.NULL : id);
            o.put("result", result);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    private static String errorResponse(Object id, int code, String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("jsonrpc", "2.0");
            o.put("id", id == null ? JSONObject.NULL : id);
            JSONObject error = new JSONObject();
            error.put("code", code);
            error.put("message", message == null ? "error" : message);
            o.put("error", error);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /** Endpoint details for the app UI. */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("url", "http://127.0.0.1:" + prefs.mcpPort() + "/mcp");
        out.put("token", prefs.mcpToken());
        out.put("running", String.valueOf(isRunning()));
        return out;
    }

    @SuppressWarnings("unused")
    private List<String> toolNames() {
        List<String> names = new ArrayList<>();
        for (McpTool tool : tools.all()) {
            names.add(tool.name);
        }
        return names;
    }
}
