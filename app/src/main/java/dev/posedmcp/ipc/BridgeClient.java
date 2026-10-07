package dev.posedmcp.ipc;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.posedmcp.Logx;

/**
 * Module half of the in-device bridge: dials the app process, reconnects
 * forever, and serves the ops the app asks of this process.
 *
 * <p>Instances live inside system_server and inside scoped third-party apps, so
 * every failure here is swallowed and retried rather than propagated. A broken
 * bridge must never take down the hosting process.
 */
public final class BridgeClient {

    /** Handles one op from the app. Returning {@code null} yields an empty result. */
    public interface OpHandler {
        JSONObject handle(JSONObject args) throws Exception;
    }

    private static final int MAX_QUEUED_EVENTS = 256;

    /**
     * How long to wait between attempts to reach the app.
     *
     * <p>Bounded low on purpose. This loop is the only thing that notices the app
     * has gone and brings it back - binding its credential service is what starts
     * it - so the wait is a delay on the bridge coming back, and on every hook
     * that has to be re-armed through it. It used to double up to a minute, which
     * meant that after the app had been away long enough to fail a few attempts,
     * a process starting afterwards could sit unhooked for most of that minute.
     */
    private static final long RECONNECT_MIN_MS = 1000L;
    private static final long RECONNECT_MAX_MS = 5000L;

    /** Token handed to this process by the app, shared by every client in it. */
    private static volatile String grantedToken;

    private final String role;
    private final String pkg;
    private final int port;

    private final Map<String, OpHandler> handlers = new ConcurrentHashMap<>();
    private final ArrayDeque<JSONObject> pendingEvents = new ArrayDeque<>();

    private volatile Socket socket;
    private volatile Writer out;
    private volatile boolean running;
    private volatile boolean stopped;
    private volatile Thread worker;

    public BridgeClient(String role, String pkg, int port) {
        this.role = role;
        this.pkg = pkg == null ? "" : pkg;
        this.port = port;
    }

    public String endpointKey() {
        return Wire.ROLE_APP.equals(role) ? Wire.ROLE_APP + ":" + pkg : Wire.ROLE_SYSTEM;
    }

    public void registerHandler(String op, OpHandler handler) {
        handlers.put(op, handler);
    }

    public boolean isConnected() {
        return running && socket != null && !socket.isClosed();
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        stopped = false;
        Thread t = new Thread(this::runLoop, "posedmcp-bridge-" + endpointKey());
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    public synchronized void stop() {
        stopped = true;
        closeSocket();
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Queues an event for the app; delivered on the next successful connection. */
    public void emit(String type, JSONObject data) {
        JSONObject envelope = new JSONObject();
        try {
            envelope.put("type", type);
            envelope.put("ts", System.currentTimeMillis());
            envelope.put("data", data == null ? new JSONObject() : data);
        } catch (Throwable t) {
            return;
        }
        JSONObject msg = new JSONObject();
        try {
            msg.put("event", envelope);
        } catch (Throwable t) {
            return;
        }

        Writer w = out;
        if (w == null) {
            synchronized (pendingEvents) {
                if (pendingEvents.size() >= MAX_QUEUED_EVENTS) {
                    pendingEvents.removeFirst();
                }
                pendingEvents.addLast(msg);
            }
            return;
        }
        try {
            synchronized (this) {
                Wire.writeJson(w, msg);
            }
        } catch (Throwable t) {
            // The read loop will notice and reconnect; drop this event.
            synchronized (pendingEvents) {
                if (pendingEvents.size() < MAX_QUEUED_EVENTS) {
                    pendingEvents.addLast(msg);
                }
            }
        }
    }

    private void runLoop() {
        long backoff = RECONNECT_MIN_MS;
        boolean reportedDown = false;
        while (!stopped) {
            try {
                connectAndServe();
                backoff = RECONNECT_MIN_MS;
                reportedDown = false;
            } catch (Throwable t) {
                // Once per outage rather than once per attempt: at a second
                // between attempts, saying it every time buried everything else
                // this process had to say.
                if (!stopped && !reportedDown) {
                    reportedDown = true;
                    Logx.w("bridge[" + endpointKey() + "] disconnected: " + t);
                }
            } finally {
                closeSocket();
            }
            if (stopped) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
        }
    }

    private void connectAndServe() throws Exception {
        // An application that cannot obtain the token still connects: it asks
        // over the connection itself, and the app either hands one back in the
        // handshake or refuses. Refusing is a decision, not a network error, so
        // there is nothing to retry in a tight loop.
        String token = grantedToken;
        if (token == null || token.isEmpty()) {
            token = BridgeAuth.token();
        }
        if (token == null) {
            token = "";
        }

        Socket s = Wire.connectLoopback(port, Wire.LOOPBACK_CONNECT_TIMEOUT_MS);
        s.setSoTimeout(0);
        BufferedReader in = Wire.reader(s);
        Writer w = Wire.writer(s);

        JSONObject hello = new JSONObject();
        JSONObject inner = new JSONObject();
        inner.put("role", role);
        inner.put("pkg", pkg);
        inner.put("pid", android.os.Process.myPid());
        inner.put("token", token);
        hello.put("hello", inner);
        Wire.writeJson(w, hello);

        JSONObject ack = Wire.readJson(in);
        JSONObject reply = ack == null ? null : ack.optJSONObject("hello");
        if (reply == null || !reply.optBoolean("ok", false)) {
            throw new IOException("bridge refused this process"
                    + (reply == null ? "" : ": " + reply.optString("error", "rejected")));
        }
        String granted = reply.optString("token", "");
        if (!granted.isEmpty()) {
            grantedToken = granted;
        }

        socket = s;
        out = w;
        running = true;
        flushPending(w);
        Logx.i("bridge[" + endpointKey() + "] connected");

        serveRequests(in);
    }

    private void flushPending(Writer w) {
        synchronized (pendingEvents) {
            while (!pendingEvents.isEmpty()) {
                try {
                    Wire.writeJson(w, pendingEvents.pollFirst());
                } catch (Throwable t) {
                    return;
                }
            }
        }
    }

    private void serveRequests(BufferedReader in) throws IOException {
        while (!stopped) {
            JSONObject msg = Wire.readJson(in);
            if (msg == null) {
                return;
            }
            long id = msg.optLong("id", -1L);
            String op = msg.optString("op", "");
            if (id < 0 || op.isEmpty()) {
                continue;
            }

            JSONObject reply = new JSONObject();
            try {
                reply.put("id", id);
                OpHandler handler = handlers.get(op);
                if (handler == null) {
                    reply.put("ok", false);
                    reply.put("error", "unsupported op '" + op + "' in " + endpointKey());
                } else {
                    JSONObject result = handler.handle(msg.optJSONObject("args"));
                    reply.put("ok", true);
                    reply.put("result", result == null ? new JSONObject() : result);
                }
            } catch (Throwable t) {
                try {
                    reply.put("ok", false);
                    reply.put("error", String.valueOf(t.getMessage() == null ? t : t.getMessage()));
                } catch (Throwable ignored) {
                }
            }

            JSONObject envelope = new JSONObject();
            try {
                envelope.put("reply", reply);
            } catch (Throwable t) {
                // Nothing useful can be sent if the reply will not serialise.
                continue;
            }
            synchronized (this) {
                Wire.writeJson(out, envelope);
            }
        }
    }

    private void closeSocket() {
        running = false;
        out = null;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
