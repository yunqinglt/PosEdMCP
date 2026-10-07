package dev.posedmcp.ipc;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Line-delimited JSON framing for the in-device bridge.
 *
 * <p>The app process listens on 127.0.0.1; processes the module is loaded into
 * (system_server, scoped apps) dial in and identify themselves. One JSON object
 * per line, UTF-8, newline-terminated.
 */
public final class Wire {

    /** Identifies the module as the source of a connection. */
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_APP = "app";

    /** Guard against a malformed peer streaming an unbounded line. */
    public static final int MAX_LINE_CHARS = 64 * 1024 * 1024;

    private Wire() {
    }

    public static Socket connectLoopback(int port, int connectTimeoutMs) throws IOException {
        Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port);
        s.setTcpNoDelay(true);
        s.setSoTimeout(connectTimeoutMs);
        return s;
    }

    public static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8), 8192);
    }

    public static Writer writer(Socket socket) throws IOException {
        return new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), 8192);
    }

    /**
     * Reads one JSON object, or {@code null} at end of stream.
     *
     * @throws IOException if the peer sends a line that is not a JSON object
     */
    public static JSONObject readJson(BufferedReader in) throws IOException {
        String line = readLine(in);
        if (line == null) {
            return null;
        }
        if (line.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(line);
        } catch (Throwable t) {
            throw new IOException("bridge peer sent non-JSON line", t);
        }
    }


    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        int c;
        boolean any = false;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') {
                return stripTrailingCr(sb);
            }
            sb.append((char) c);
            if (sb.length() > MAX_LINE_CHARS) {
                throw new IOException("bridge line exceeds " + MAX_LINE_CHARS + " chars");
            }
        }
        return any ? stripTrailingCr(sb) : null;
    }

    private static String stripTrailingCr(StringBuilder sb) {
        int last = sb.length() - 1;
        if (last >= 0 && sb.charAt(last) == '\r') {
            sb.setLength(last);
        }
        return sb.toString();
    }

    public static void writeJson(Writer out, JSONObject obj) throws IOException {
        out.write(obj.toString());
        out.write('\n');
        out.flush();
    }
}
