package dev.deploy.mcp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.deploy.core.DeployCore;

/**
 * The MCP frontend, in miniature.
 *
 * <p>This is a deliberately small JSON-RPC 2.0 / MCP server over loopback HTTP,
 * serving the same {@code DeployCore} that the AIDL service exposes. It is not
 * PosEdMCP's full {@code McpServer} - that one is bound up with its tool
 * registry and confirmation gate - and consolidating the two is a follow-up.
 *
 * <p>Supported: {@code POST /mcp} for JSON-RPC, {@code GET /health} for a liveness
 * probe. Bearer token auth on the MCP endpoint, constant-time compared. No SSE
 * stream yet: the agent's tool calls go over POST, and streaming server events
 * is a later concern for a read-only analysis server.
 */
public final class DeployMcpServer {

    private static final String SERVER_NAME = "deploy";
    private static final String SERVER_VERSION = DeployCore.VERSION;
    private static final int MAX_BODY_BYTES = 16 << 20;

    private final int port;
    private final String token;
    private final DeployCore core = new DeployCore();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "deploy-tool");
        t.setDaemon(true);
        return t;
    });

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public DeployMcpServer(int port, String token) {
        this.port = port;
        this.token = token;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket ss = new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"));
        ss.setReuseAddress(true);
        serverSocket = ss;
        running = true;
        Thread t = new Thread(this::acceptLoop, "deploy-http-accept");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket ss = serverSocket;
        serverSocket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (Throwable t) {
                continue;
            }
            Thread t = new Thread(() -> serve(socket), "deploy-http-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            Request request = readRequest(socket.getInputStream());
            if (request == null) {
                return;
            }
            byte[] body;
            String status;
            if ("/health".equals(request.path)) {
                body = health().getBytes(StandardCharsets.UTF_8);
                status = "200 OK";
            } else if (!"/mcp".equals(request.path)) {
                body = "{\"error\":\"no such endpoint\"}".getBytes(StandardCharsets.UTF_8);
                status = "404 Not Found";
            } else if (!"POST".equals(request.method)) {
                body = "{\"error\":\"POST only; no SSE stream in this stub\"}"
                        .getBytes(StandardCharsets.UTF_8);
                status = "405 Method Not Allowed";
            } else if (!authorized(request)) {
                body = "{\"error\":\"missing or invalid bearer token\"}"
                        .getBytes(StandardCharsets.UTF_8);
                status = "401 Unauthorized";
            } else {
                String response = handlePayload(request.body);
                body = response == null ? new byte[0]
                        : response.getBytes(StandardCharsets.UTF_8);
                status = response == null ? "202 Accepted" : "200 OK";
            }

            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);
            String head = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Server: deploy\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
            // A closed or malformed connection is not worth logging.
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private String health() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("status", "ok");
        o.put("server", SERVER_NAME);
        o.put("version", SERVER_VERSION);
        return o.toString();
    }

    private boolean authorized(Request request) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        String presented = null;
        for (String line : request.headerLines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if ("authorization".equals(name)) {
                String value = line.substring(colon + 1).trim();
                if (value.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                    presented = value.substring(7).trim();
                }
            }
        }
        return presented != null && constantTimeEquals(presented, token);
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

    private String handlePayload(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            return errorResponse(null, -32700, "empty request body");
        }
        try {
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
                default:
                    return notification ? null : errorResponse(id, -32601, "unknown method: " + method);
            }
        } catch (Throwable t) {
            return notification ? null : errorResponse(id, -32603, String.valueOf(t.getMessage()));
        }
    }

    private JSONObject initialize(JSONObject params) throws JSONException {
        JSONObject capabilities = new JSONObject();
        JSONObject toolsCap = new JSONObject();
        toolsCap.put("listChanged", false);
        capabilities.put("tools", toolsCap);

        JSONObject serverInfo = new JSONObject();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("title", "Deploy (ELF symbol analysis)");
        serverInfo.put("version", SERVER_VERSION);

        JSONObject result = new JSONObject();
        result.put("protocolVersion", "2025-06-18");
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        result.put("instructions",
                "Deploy parses ELF shared objects (Android native libraries) that are already"
                + " on disk. It is read-only and non-root: name a file with 'path' that the app"
                + " can read, typically something already copied to /sdcard. The 'op' argument"
                + " selects the layer - overview, symbols, relocations, strings, metadata, or"
                + " resolve. disasm returns Capstone instructions; decompile reports unavailable.");
        return result;
    }

    private JSONObject listTools() throws JSONException {
        JSONArray array = new JSONArray();
        array.put(tool("deploy_status", "Deploy backend status",
                "Version, backend, ABI and the implemented ops of the Deploy ELF analysis"
                + " backend. Read-only.", new JSONObject()));
        JSONObject props = dev.deploy.api.DeployContract.analysisProperties();
        array.put(tool("deploy_analyze", "Analyze an ELF shared object",
                "LIEF ELF analysis and Capstone disasm; decompile is reserved"
                + " for a future pseudocode engine. Read-only.",
                props));
        JSONObject result = new JSONObject();
        result.put("tools", array);
        return result;
    }

    private static JSONObject str(String description) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "string");
        o.put("description", description);
        return o;
    }

    private static JSONObject tool(String name, String title, String description, JSONObject props)
            throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("title", title);
        o.put("description", description);
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        schema.put("properties", props == null ? new JSONObject() : props);
        schema.put("additionalProperties", false);
        if ("deploy_analyze".equals(name)) schema.put("required", new JSONArray().put("path"));
        o.put("inputSchema", schema);
        return o;
    }

    private JSONObject callTool(JSONObject params) throws Exception {
        String name = params.optString("name", "");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) {
            args = new JSONObject();
        }
        final JSONObject finalArgs = args;
        JSONObject produced = workers.submit(() -> {
            switch (name) {
                case "deploy_status":
                    return core.status();
                case "deploy_analyze": {
                    String path = finalArgs.optString("path", "");
                    if (path.trim().isEmpty()) {
                        throw new IllegalArgumentException("'path' is required");
                    }
                    String op = finalArgs.optString("op", "overview");
                    return core.analyze(path, op, finalArgs);
                }
                default:
                    throw new IllegalArgumentException("unknown tool '" + name
                            + "'. Call tools/list.");
            }
        }).get();

        JSONObject result = new JSONObject();
        JSONArray content = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("type", "text");
        item.put("text", produced.toString(2));
        content.put(item);
        result.put("content", content);
        result.put("isError", produced.has("error"));
        return result;
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

    // ---- minimal HTTP request parsing ------------------------------------

    private static final class Request {
        final String method;
        final String path;
        final String body;
        final java.util.List<String> headerLines = new java.util.ArrayList<>();

        Request(String method, String path, String body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }
    }

    private static Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("malformed request line");
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        String path = target;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
        }

        java.util.List<String> headers = new java.util.ArrayList<>();
        int headerBytes = requestLine.length() + 2;
        String line;
        int contentLength = 0;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            headerBytes += line.length() + 2;
            if (headerBytes > 64 * 1024) {
                throw new IOException("headers too large");
            }
            headers.add(line);
            int colon = line.indexOf(':');
            if (colon > 0
                    && "content-length".equalsIgnoreCase(line.substring(0, colon).trim())) {
                try {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("bad Content-Length");
                }
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            throw new IOException("body too large");
        }
        String body = contentLength > 0
                ? new String(readExactly(in, contentLength), StandardCharsets.UTF_8)
                : "";
        Request request = new Request(method, path, body);
        request.headerLines.addAll(headers);
        return request;
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                throw new IOException("unexpected end of body");
            }
            read += n;
        }
        return buffer;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buffer.write(c);
            }
            if (buffer.size() > 64 * 1024) {
                throw new IOException("header line too long");
            }
        }
        if (!any && buffer.size() == 0) {
            return null;
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
