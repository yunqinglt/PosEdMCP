package dev.posedmcp.ipc;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.posedmcp.Logx;
import dev.posedmcp.state.EventStore;
import dev.posedmcp.state.HookRecordStore;
import dev.posedmcp.state.SavedHook;

/**
 * Server half of the in-device bridge, running in the app process.
 *
 * <p>It accepts two kinds of peer:
 * <ul>
 *   <li>{@code system} - the module inside system_server, the only peer that can
 *       capture the screen or inject input with platform privileges.</li>
 *   <li>{@code app:<pkg>} - the module inside a scoped third-party app, which is
 *       where injected plugins actually execute.</li>
 * </ul>
 *
 * <p>Traffic is line-delimited JSON (see {@link Wire}). Peers push events
 * unprompted and answer requests addressed to them by id.
 */
public final class BridgeServer {

    private final int port;
    private final String token;
    private final EventStore events;
    private final HookRecordStore hookRecords;
    private final TrustDecider trustDecider;
    private final PeerListener peerListener;

    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    /**
     * Request ids are unpredictable on purpose. Any local process can reach a
     * loopback port, so a peer that is not really the module could otherwise
     * guess an in-flight id and race the real peer with a forged reply.
     */
    private final java.security.SecureRandom random = new java.security.SecureRandom();

    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;
    private volatile boolean running;

    /**
     * Decides whether a package may use the bridge when it arrives without a
     * token. Implementations may block on the user.
     */
    public interface TrustDecider {
        boolean isTrusted(String pkg);
    }

    /**
     * Told when a module process has finished connecting, including system_server.
     *
     * <p>Called on a thread of its own, and that is the point: whatever handles
     * this usually sends a request straight back to the peer, and the thread
     * that accepted the connection is about to become that peer's read loop. A
     * request sent from there would wait for a reply nothing was reading.
     *
     * <p>The system peer arrives here too, as {@link SavedHook#SYSTEM_PACKAGE}. It
     * is the process whose hooks are the ones that have to be put back earliest,
     * so it is the last one that should be left out.
     */
    public interface PeerListener {
        void onPeerReady(String pkg, String peerKey);
    }

    public BridgeServer(int port, String token, EventStore events, HookRecordStore hookRecords,
            TrustDecider trustDecider, PeerListener peerListener) {
        this.port = port;
        this.token = token;
        this.events = events;
        this.hookRecords = hookRecords;
        this.trustDecider = trustDecider;
        this.peerListener = peerListener;
    }

    public int port() {
        return port;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket ss = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        ss.setReuseAddress(true);
        serverSocket = ss;
        running = true;
        Thread t = new Thread(this::acceptLoop, "posedmcp-bridge-accept");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
        Logx.i("bridge listening on 127.0.0.1:" + port);
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
        for (Peer p : new ArrayList<>(peers.values())) {
            p.close();
        }
        peers.clear();
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * A peer that is still connected, or {@code null}.
     *
     * <p>Liveness is the socket, not a timestamp. An earlier version expired
     * peers that had been quiet for 90 seconds, which quietly discarded every
     * healthy connection: a hooked process only speaks when it has an event, so
     * an idle peer looks identical to a dead one. The read loop removes a peer
     * from the map when its socket closes, which is the only signal that
     * actually means anything.
     */
    public Peer peer(String key) {
        Peer p = peers.get(key);
        if (p == null) {
            return null;
        }
        if (p.closed) {
            peers.remove(key, p);
            return null;
        }
        return p;
    }

    public Peer systemPeer() {
        return peer(Wire.ROLE_SYSTEM);
    }

    public Peer appPeer(String pkg) {
        return peer(Wire.ROLE_APP + ":" + pkg);
    }

    /** Every live process of a package that is serving as a peer. */
    public List<String> appPeerKeys(String pkg) {
        List<String> out = new ArrayList<>();
        String prefix = peerKeyPrefix(pkg);
        for (String key : new ArrayList<>(peers.keySet())) {
            if (key.startsWith(prefix) && peer(key) != null) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * The key every process of a package connects under.
     *
     * <p>Needed where the processes themselves are not: a record kept from a
     * process that has since been killed still says which package it came from,
     * and there is nothing left to ask.
     */
    public static String peerKeyPrefix(String pkg) {
        return isSystem(pkg) ? Wire.ROLE_SYSTEM : Wire.ROLE_APP + ":" + pkg + ":";
    }

    /**
     * Every peer that can serve an op for a package.
     *
     * <p>The one place the two kinds of peer are told apart, so that everything
     * which addresses a target - hooks, scripts, plugins - asks one question and
     * gets the right answer for system_server as well as for an app. Without it
     * the system peer's key, which is the bare word {@code system} and not an
     * {@code app:<pkg>:<pid>} key, matches nothing, and the one process worth
     * hooking most is the one no tool can address.
     */
    public List<String> peerKeys(String pkg) {
        if (isSystem(pkg)) {
            return systemPeer() == null ? new ArrayList<>() : singleton(Wire.ROLE_SYSTEM);
        }
        return appPeerKeys(pkg);
    }

    private static List<String> singleton(String key) {
        List<String> out = new ArrayList<>(1);
        out.add(key);
        return out;
    }

    /** Whether {@code pkg} names the system framework rather than an app. */
    public static boolean isSystem(String pkg) {
        return SavedHook.isSystem(pkg);
    }

    /** Whether anything is connected that could serve an op for this package. */
    public boolean hasPeer(String pkg) {
        return !peerKeys(pkg).isEmpty();
    }

    /**
     * Sends an op to every process of a package.
     *
     * <p>Needed for anything whose state is per-process. A hook lives in the
     * process it was installed into, and an application commonly has several -
     * asking only the first one that answers reports "nothing is hooked" while a
     * hook sits in a sibling process, which is exactly the kind of answer that
     * sends someone hunting for a bug that is not there.
     *
     * <p>The target may also be {@link SavedHook#SYSTEM_PACKAGE}, which has exactly
     * one process and answers through the {@code system} peer.
     */
    public JSONArray requestAllProcesses(String pkg, String op, JSONObject args, long timeoutMs) {
        JSONArray results = new JSONArray();
        for (String key : peerKeys(pkg)) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("process", key);
                entry.put("ok", true);
                entry.put("result", request(key, op, args, timeoutMs));
            } catch (Throwable t) {
                try {
                    entry.put("process", key);
                    entry.put("ok", false);
                    entry.put("error", String.valueOf(t.getMessage()));
                } catch (Throwable ignored) {
                }
            }
            results.put(entry);
        }
        return results;
    }

    /**
     * Sends an op to whichever of a package's processes can serve it.
     *
     * <p>Needed because a plugin is loaded into one specific process - the one
     * that happened to be running when it was injected - and the others have
     * nothing to say about it.
     */
    public JSONObject requestAnyProcess(String pkg, String op, JSONObject args, long timeoutMs)
            throws IOException {
        List<String> keys = peerKeys(pkg);
        if (keys.isEmpty()) {
            throw new IOException("no bridge peer connected for '" + pkg + "'");
        }
        IOException last = null;
        for (String key : keys) {
            try {
                return request(key, op, args, timeoutMs);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("no bridge peer answered for '" + pkg + "'") : last;
    }

    public List<String> connectedKeys() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Peer> e : peers.entrySet()) {
            if (!e.getValue().closed) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (Throwable t) {
                if (running) {
                    Logx.w("bridge accept failed: " + t);
                }
                continue;
            }
            Thread t = new Thread(() -> serve(socket), "posedmcp-bridge-peer");
            t.setDaemon(true);
            t.start();
        }
    }

    private void serve(Socket socket) {
        Peer peer = null;
        try {
            socket.setTcpNoDelay(true);
            BufferedReader in = Wire.reader(socket);
            Writer out = Wire.writer(socket);

            JSONObject hello = Wire.readJson(in);
            if (hello == null) {
                return;
            }
            JSONObject h = hello.optJSONObject("hello");
            if (h == null) {
                return;
            }
            String role = h.optString("role", "");
            String pkg = h.optString("pkg", "");
            String presented = h.optString("token", "");
            if (!Wire.ROLE_SYSTEM.equals(role) && !Wire.ROLE_APP.equals(role)) {
                Wire.writeJson(out, err("unknown role"));
                return;
            }
            if (Wire.ROLE_APP.equals(role) && pkg.isEmpty()) {
                Wire.writeJson(out, err("app peer must name its package"));
                return;
            }

            // A peer that already knows the token is in. One that does not is an
            // application that cannot obtain it - Android gives it no channel to
            // do so - so the question becomes whether the user trusts the
            // package it claims. That is asked once per package and remembered.
            boolean handedToken = false;
            if (!token.equals(presented)) {
                boolean mayConnect = Wire.ROLE_APP.equals(role)
                        && trustDecider != null
                        && trustDecider.isTrusted(pkg);
                if (!mayConnect) {
                    Logx.w("bridge peer rejected (role=" + role + ", pkg=" + pkg + ")");
                    Wire.writeJson(out, err("unauthorized"));
                    return;
                }
                handedToken = true;
                Logx.i("bridge peer approved by the user: " + pkg);
            }

            // An application commonly has several processes, each with the module
            // loaded, so the key includes the pid: they are all legitimate peers
            // and a call goes to whichever one can serve it.
            int pid = h.optInt("pid", 0);
            String key = Wire.ROLE_APP.equals(role)
                    ? Wire.ROLE_APP + ":" + pkg + ":" + pid
                    : Wire.ROLE_SYSTEM;

            Peer previous = peers.put(key, peer = new Peer(key, role, pkg, out));
            if (previous != null) {
                previous.close();
            }

            JSONObject ack = new JSONObject();
            JSONObject inner = new JSONObject();
            inner.put("ok", true);
            inner.put("server", "posedmcp");
            inner.put("protocol", 1);
            if (handedToken) {
                // So this process does not have to ask the user again.
                inner.put("token", token);
            }
            ack.put("hello", inner);
            Wire.writeJson(out, ack);

            events.add(key, "peer.connected", new JSONObject().put("role", role).put("pkg", pkg),
                    System.currentTimeMillis());
            Logx.i("bridge peer connected: " + key);

            if (peerListener != null) {
                // The system peer has no package of its own to name - it answers
                // for whichever system package happened to be loading - so it is
                // addressed by the one name the hook tools use for it.
                String readyPkg = Wire.ROLE_APP.equals(role) ? pkg : SavedHook.SYSTEM_PACKAGE;
                String readyKey = key;
                Thread t = new Thread(() -> {
                    try {
                        peerListener.onPeerReady(readyPkg, readyKey);
                    } catch (Throwable t2) {
                        Logx.w("peer-ready handler failed for " + readyKey + ": " + t2);
                    }
                }, "posedmcp-peer-ready");
                t.setDaemon(true);
                t.start();
            }

            readLoop(peer, in);
        } catch (Throwable t) {
            Logx.w("bridge peer ended: " + t);
        } finally {
            if (peer != null) {
                peers.remove(peer.key, peer);
                peer.close();
                try {
                    events.add(peer.key, "peer.disconnected",
                            new JSONObject().put("role", peer.role),
                            System.currentTimeMillis());
                } catch (Throwable ignored) {
                }
            }
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void readLoop(Peer peer, BufferedReader in) throws IOException {
        while (running && !peer.closed) {
            JSONObject msg = Wire.readJson(in);
            if (msg == null) {
                return;
            }

            JSONObject reply = msg.optJSONObject("reply");
            if (reply != null) {
                long id = reply.optLong("id", -1L);
                CompletableFuture<JSONObject> f = peer.pending.remove(id);
                if (f != null) {
                    f.complete(reply);
                }
                continue;
            }

            JSONObject event = msg.optJSONObject("event");
            if (event != null) {
                String type = event.optString("type", "unknown");
                long ts = event.optLong("ts", System.currentTimeMillis());
                JSONObject data = event.optJSONObject("data");
                if (Wire.HOOK_RECORD_EVENT.equals(type)) {
                    // Hook records go to their own store rather than the event
                    // feed. They arrive as fast as the hooked method is called,
                    // and the feed is a fixed-size window shared with foreground
                    // and screen transitions - a chatty hook would push those
                    // out and leave an agent watching events with no idea why.
                    HookRecordStore records = hookRecords;
                    if (records != null) {
                        records.add(peer.key, data);
                    }
                    continue;
                }
                events.add(peer.key, type, data, ts);
                continue;
            }
            // Anything else is ignored rather than treated as fatal, so a newer
            // peer can add message kinds without breaking an older app build.
        }
    }

    /**
     * Sends {@code op} to a connected peer and waits for its reply.
     *
     * @throws IOException if the peer is absent, disconnects, or is too slow
     */
    public JSONObject request(String peerKey, String op, JSONObject args, long timeoutMs)
            throws IOException {
        Peer p = peer(peerKey);
        if (p == null) {
            throw new IOException("no bridge peer connected for '" + peerKey + "'");
        }
        long id = random.nextLong() & Long.MAX_VALUE;
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        p.pending.put(id, future);

        JSONObject req = new JSONObject();
        try {
            req.put("id", id);
            req.put("op", op);
            req.put("args", args == null ? new JSONObject() : args);
        } catch (Throwable t) {
            p.pending.remove(id);
            throw new IOException(t);
        }
        try {
            p.send(req);
        } catch (IOException e) {
            p.pending.remove(id);
            throw e;
        }

        try {
            JSONObject reply = future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!reply.optBoolean("ok", false)) {
                throw new IOException("peer error: " + reply.optString("error", "unknown"));
            }
            JSONObject result = reply.optJSONObject("result");
            return result == null ? new JSONObject() : result;
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("peer '" + peerKey + "' timed out after " + timeoutMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for peer '" + peerKey + "'");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("peer '" + peerKey + "' failed: " + e.getCause());
        } finally {
            p.pending.remove(id);
        }
    }

    private static JSONObject err(String message) {
        JSONObject o = new JSONObject();
        try {
            JSONObject inner = new JSONObject();
            inner.put("ok", false);
            inner.put("error", message);
            o.put("hello", inner);
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** A connected module instance. */
    public static final class Peer {
        public final String key;
        public final String role;
        public final String pkg;

        private final Writer out;
        private final Map<Long, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
        private volatile boolean closed;

        Peer(String key, String role, String pkg, Writer out) {
            this.key = key;
            this.role = role;
            this.pkg = pkg;
            this.out = out;
        }

        synchronized void send(JSONObject obj) throws IOException {
            if (closed) {
                throw new IOException("peer closed");
            }
            Wire.writeJson(out, obj);
        }

        void close() {
            closed = true;
            for (CompletableFuture<JSONObject> f : pending.values()) {
                f.completeExceptionally(new IOException("peer closed"));
            }
            pending.clear();
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
