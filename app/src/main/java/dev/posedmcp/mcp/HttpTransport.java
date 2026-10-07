package dev.posedmcp.mcp;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import dev.posedmcp.Logx;

/**
 * A small HTTP/1.1 server for the MCP endpoint.
 *
 * <p>Deliberately minimal and dependency-free: {@code com.sun.net.httpserver} is
 * absent on Android, and pulling in a web framework for five routes would cost
 * more than it saves.
 *
 * <p>Handled shape:
 * <ul>
 *   <li>{@code POST /mcp} - JSON-RPC, answered with {@code application/json}</li>
 *   <li>{@code GET /mcp} - server-sent events for server-initiated messages</li>
 *   <li>{@code GET /health} - unauthenticated liveness probe</li>
 * </ul>
 *
 * <p>No CORS headers are emitted on purpose. Loopback plus DNS rebinding is a
 * live attack path, and a browser page has no business reaching a root bridge;
 * browser-hosted MCP clients are not supported.
 */
public final class HttpTransport {

    public static final int MAX_BODY_BYTES = 16 << 20;

    /**
     * How much room the request line and headers get together.
     *
     * <p>Each line is already capped at 64 KiB by {@link #readLine}, but nothing
     * capped how many there were - and every one of them was kept. A limit on each
     * item is not a limit on the collection: the count is the peer's, and a peer
     * that only ever sends headers can keep sending them until the socket times
     * out. Real requests from MCP clients are a few hundred bytes.
     */
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    public interface Router {
        Response route(Request request);
    }

    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        public final Map<String, String> headers;
        public final String body;
        public final String remoteAddress;

        Request(String method, String path, Map<String, String> query,
                Map<String, String> headers, String body, String remoteAddress) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
            this.remoteAddress = remoteAddress;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** A response; exactly one of {@code body} or {@code sse} is used. */
    public static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final SseHandler sse;

        private Response(int status, String contentType, byte[] body, SseHandler sse) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.sse = sse;
        }

        public static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8",
                    json.getBytes(StandardCharsets.UTF_8), null);
        }

        public static Response text(int status, String text) {
            return new Response(status, "text/plain; charset=utf-8",
                    text.getBytes(StandardCharsets.UTF_8), null);
        }

        public static Response empty(int status) {
            return new Response(status, null, new byte[0], null);
        }

        public static Response sse(SseHandler handler) {
            return new Response(200, "text/event-stream; charset=utf-8", null, handler);
        }
    }

    /** Writes server-initiated events for the lifetime of one open stream. */
    public interface SseHandler {
        void serve(Sink sink) throws IOException;
    }

    public interface Sink {
        void send(String event, String data) throws IOException;

        boolean isOpen();
    }

    private final int port;
    private final Router router;
    private final Set<Sink> liveSinks = new CopyOnWriteArraySet<>();

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private volatile Thread acceptThread;

    public HttpTransport(int port, Router router) {
        this.port = port;
        this.router = router;
    }

    public boolean isRunning() {
        return running;
    }

    public int openStreamCount() {
        return liveSinks.size();
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket ss = new ServerSocket(port, 32, InetAddress.getByName("127.0.0.1"));
        ss.setReuseAddress(true);
        serverSocket = ss;
        running = true;
        Thread t = new Thread(this::acceptLoop, "posedmcp-http-accept");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
        Logx.i("MCP endpoint listening on http://127.0.0.1:" + port + "/mcp");
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
        for (Sink sink : liveSinks) {
            try {
                sink.send("closed", "{\"reason\":\"server stopping\"}");
            } catch (Throwable ignored) {
            }
        }
    }

    /** Broadcasts a server-initiated notification to every open event stream. */
    public void broadcast(String event, String data) {
        for (Sink sink : liveSinks) {
            try {
                sink.send(event, data);
            } catch (Throwable t) {
                liveSinks.remove(sink);
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (Throwable t) {
                if (running) {
                    Logx.w("http accept failed: " + t);
                }
                continue;
            }
            Thread t = new Thread(() -> serve(socket), "posedmcp-http-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            InputStream in = socket.getInputStream();
            OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024);

            Request request = readRequest(in, socket);
            if (request == null) {
                return;
            }

            Response response;
            try {
                response = router.route(request);
            } catch (Throwable t) {
                Logx.e("route failed", t);
                response = Response.json(500, "{\"error\":\"internal error\"}");
            }

            if (response.sse != null) {
                serveSse(socket, rawOut, response);
            } else {
                writeResponse(rawOut, response.status, response.contentType, response.body);
            }
        } catch (Throwable t) {
            Logx.w("http connection ended: " + t);
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void serveSse(Socket socket, OutputStream out, Response response) throws IOException {
        socket.setSoTimeout(0);
        writeHead(out, response.status, response.contentType, -1,
                "Cache-Control: no-cache\r\nConnection: keep-alive\r\n");
        out.flush();

        SseSink sink = new SseSink(out);
        liveSinks.add(sink);
        try {
            response.sse.serve(sink);
        } finally {
            liveSinks.remove(sink);
            sink.close();
        }
    }

    private Request readRequest(InputStream in, Socket socket) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) {
            return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            throw new IOException("malformed request line: " + requestLine);
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];

        String path = target;
        Map<String, String> query = new LinkedHashMap<>();
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            parseQuery(target.substring(q + 1), query);
        }

        Map<String, String> headers = new HashMap<>();
        String line;
        int headerBytes = requestLine.length() + 2;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            headerBytes += line.length() + 2;
            if (headerBytes > MAX_HEADER_BYTES) {
                throw new IOException("request headers exceed " + MAX_HEADER_BYTES + " bytes");
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        String body = "";
        java.nio.charset.Charset charset = charsetOf(headers.get("content-type"));
        String contentLength = headers.get("content-length");
        if (contentLength != null) {
            int length;
            try {
                length = Integer.parseInt(contentLength.trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad Content-Length");
            }
            if (length < 0 || length > MAX_BODY_BYTES) {
                throw new IOException("body too large: " + length);
            }
            body = decodeBody(readExactly(in, length), charset);
        } else if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
            body = decodeBody(readChunked(in), charset);
        }

        String remote = socket.getInetAddress() == null ? "?" : socket.getInetAddress().getHostAddress();
        return new Request(method, path, query, headers, body, remote);
    }

    /**
     * Decodes a request body, second-guessing a client that mislabels its encoding.
     *
     * <p>MCP mandates UTF-8, but an agent on a Simplified-Chinese desktop can
     * still produce GBK bytes and label them UTF-8 - which arrives as garbage
     * exactly where it matters most, in the reason text a person is about to read
     * before approving a root command. If the declared decoding is invalid and
     * GBK makes it valid, the bytes were GBK.
     */
    private static String decodeBody(byte[] raw, java.nio.charset.Charset declared) {
        String decoded = new String(raw, declared);
        if (decoded.indexOf('�') < 0) {
            return decoded;
        }
        try {
            String asGbk = new String(raw, java.nio.charset.Charset.forName("GBK"));
            if (asGbk.indexOf('�') < 0) {
                Logx.w("body was not valid " + declared.name() + "; decoded as GBK instead");
                return asGbk;
            }
        } catch (Throwable ignored) {
        }
        return decoded;
    }

    /**
     * Picks the body charset.
     *
     * <p>MCP mandates UTF-8, but a client that declares another charset and then
     * encodes to it would otherwise arrive as mojibake - which is exactly what a
     * user sees when a Chinese confirmation reason comes out garbled. Honouring
     * the declaration is both correct HTTP and the difference between readable
     * and unreadable.
     */
    private static java.nio.charset.Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                    String name = trimmed.substring(8).trim().replace("\"", "");
                    try {
                        return java.nio.charset.Charset.forName(name);
                    } catch (Throwable t) {
                        Logx.w("unknown charset '" + name + "', falling back to UTF-8");
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void parseQuery(String raw, Map<String, String> out) {
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(urlDecode(pair), "");
            } else {
                out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
            }
        }
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Throwable t) {
            return s;
        }
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size: " + sizeLine);
            }
            if (size < 0) {
                throw new IOException("bad chunk size: " + sizeLine);
            }
            if (size == 0) {
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.isEmpty()) {
                        break;
                    }
                }
                break;
            }
            // The size is the peer's, so it is judged before anything is allocated
            // for it. It used to be read first and compared afterwards, which made
            // this guard unable to prevent the thing it names: a header reading
            // "7fffffff" asked for a two-gigabyte array, and read into it, before
            // any check looked at the total. The request body is parsed to build
            // the request, which happens before the bearer token is checked, so a
            // peer need not be authenticated to try this - and the endpoint is on
            // loopback, which every other app on the device can reach.
            if (size > MAX_BODY_BYTES - buffer.size()) {
                throw new IOException("chunked body too large");
            }
            buffer.write(readExactly(in, size));
            readLine(in);
        }
        return buffer.toByteArray();
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

    /** Reads a CRLF-terminated line, tolerating a bare LF. */
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

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        writeHead(out, status, contentType, body.length, null);
        out.write(body);
        out.flush();
    }

    private static void writeHead(OutputStream out, int status, String contentType, int contentLength,
            String extraHeaders) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        if (contentType != null) {
            sb.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (contentLength >= 0) {
            sb.append("Content-Length: ").append(contentLength).append("\r\n");
        }
        sb.append("Server: posedmcp\r\n");
        if (extraHeaders != null) {
            sb.append(extraHeaders);
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 202:
                return "Accepted";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 401:
                return "Unauthorized";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 413:
                return "Payload Too Large";
            case 500:
                return "Internal Server Error";
            default:
                return "Status";
        }
    }

    private final class SseSink implements Sink {
        private final OutputStream out;
        private volatile boolean open = true;

        SseSink(OutputStream out) {
            this.out = out;
        }

        @Override
        public synchronized void send(String event, String data) throws IOException {
            if (!open) {
                throw new IOException("stream closed");
            }
            StringBuilder sb = new StringBuilder();
            if (event != null) {
                sb.append("event: ").append(event).append('\n');
            }
            for (String line : data.split("\n", -1)) {
                sb.append("data: ").append(line).append('\n');
            }
            sb.append('\n');
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        void close() {
            open = false;
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Keepalive comments so intermediaries do not drop an idle stream. */
    public void keepalive() {
        broadcast(null, "");
    }

    static boolean isDisconnect(Throwable t) {
        return t instanceof SocketException;
    }
}
