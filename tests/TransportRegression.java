package fixture;

import dev.posedmcp.mcp.EndpointAddresses;
import dev.posedmcp.mcp.HttpTransport;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real TCP listeners, streams, occupied ports and repeated mode changes. */
public final class TransportRegression {
    static int assertions;
    static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    static Socket connect(String host, int port) throws IOException {
        Socket socket = new Socket(); socket.connect(new InetSocketAddress(host, port), 1500);
        socket.setSoTimeout(1500); return socket;
    }
    static void send(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
    }
    static String get(String host, int port) throws IOException {
        try (Socket socket = connect(host, port)) {
            send(socket, "GET /health HTTP/1.1\r\nHost: fixture\r\n\r\n");
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    static boolean closed(Socket socket) throws IOException {
        try { return socket.getInputStream().read() == -1; }
        catch (SocketException reset) { return true; }
    }
    public static void main(String[] args) throws Exception {
        CountDownLatch streamReady = new CountDownLatch(1), streamClosed = new CountDownLatch(1);
        HttpTransport.Router router = request -> request.path.equals("/stream")
                ? HttpTransport.Response.sse(sink -> {
                    try {
                        sink.send(null, "ready"); streamReady.countDown();
                        while (sink.isOpen()) try { Thread.sleep(5); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    } finally { streamClosed.countDown(); }
                }) : HttpTransport.Response.json(200, "{\"ok\":true}");
        HttpTransport local = new HttpTransport(0, router);
        try {
            local.start(); int port = local.localPort();
            check(port > 0 && get("127.0.0.1", port).contains("200 OK"), "default loopback accepts requests");
            local.start(); check(local.localPort() == port, "start is idempotent");
            List<String> addresses = EndpointAddresses.urls(port, true);
            String lanHost = addresses.stream().map(URI::create).map(URI::getHost)
                    .filter(host -> !host.equals("127.0.0.1")).findFirst().orElse(null);
            if (lanHost != null) {
                boolean refused = false;
                try (Socket ignored = connect(lanHost, port)) {} catch (IOException expected) { refused = true; }
                check(refused, "default listener is inaccessible at LAN address");
            }
            try (Socket partial = connect("127.0.0.1", port); Socket stream = connect("127.0.0.1", port)) {
                send(partial, "POST /mcp HTTP/1.1\r\nHost: fixture\r\n");
                send(stream, "GET /stream HTTP/1.1\r\nHost: fixture\r\n\r\n");
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream.getInputStream(), StandardCharsets.UTF_8));
                String line; do { line = reader.readLine(); } while (line != null && !line.equals("data: ready"));
                reader.readLine();
                check(streamReady.await(1, TimeUnit.SECONDS), "SSE established");
                local.stop();
                check(closed(partial), "stop closes partially received remote requests");
                check(closed(stream) && streamClosed.await(1, TimeUnit.SECONDS), "stop revokes existing SSE connections");
            }
            for (int round = 0; round < 6; round++) {
                boolean lan = round % 2 == 0;
                HttpTransport next = new HttpTransport(port, EndpointAddresses.bindAddress(lan), router);
                try {
                    next.start();
                    check(get("127.0.0.1", port).contains("\"ok\":true"), "mode change retains HTTP responses");
                    if (lan && lanHost != null) check(get(lanHost, port).contains("200 OK"), "wildcard listener accepts LAN address");
                } finally { next.stop(); }
            }
            HttpTransport occupied = new HttpTransport(port, "0.0.0.0", router);
            try (ServerSocket blocker = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
                boolean refused = false;
                try { occupied.start(); } catch (IOException expected) { refused = true; }
                check(refused && !occupied.isRunning(), "failed bind leaves no running listener");
            }
            try { occupied.start(); check(get("127.0.0.1", port).contains("200 OK"), "failed bind releases resources for retry"); }
            finally { occupied.stop(); }
            check(EndpointAddresses.urls(port, false).equals(List.of("http://127.0.0.1:"+port+"/mcp")), "local URL is connectable");
            check(addresses.stream().noneMatch(url -> url.contains("0.0.0.0")), "never present wildcard as a client URL");
        } finally { local.stop(); }
        System.out.println("PASS HTTP network modes (" + assertions + " assertions)");
    }
}
