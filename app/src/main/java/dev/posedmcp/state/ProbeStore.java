package dev.posedmcp.state;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.posedmcp.Logx;

/**
 * Probe snapshots, kept by the app so the evidence outlives the moment.
 *
 * <p>A snapshot - the threads and their Java stacks - is captured at freeze
 * time, because that is the one moment the scene still exists. The frozen app
 * itself cannot be asked later: it is frozen, and once it is released the
 * scene is gone. The snapshot therefore lands here, bounded twice over like
 * {@link HookRecordStore}: a small ring in memory and an append-only file
 * trimmed back to it. The newest snapshot for a package is what
 * {@code probe_state} reports.
 *
 * <p>Third-party stack traces reaching this disk is the price of the feature,
 * exactly like hook records: the file is bounded, private, and never sent
 * anywhere.
 */
public final class ProbeStore {

    private static final String FILE = "probe-snapshots.jsonl";
    private static final int CAPACITY = 8;
    private static final long MAX_FILE_BYTES = 256L * 1024L;

    private static volatile ProbeStore instance;

    private final File file;
    private final Deque<JSONObject> records = new ArrayDeque<>();
    /** Guards {@link #records} only - never the disk, which has its own thread. */
    private final Object lock = new Object();

    /** Same shape as {@link HookRecordStore}'s writer: own thread, dies idle. */
    private final Executor writer =
            new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    public ProbeStore(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.file = new File((app == null ? ctx : app).getFilesDir(), FILE);
        load();
    }

    /** Installed once per service start; the probe machinery reaches it statically. */
    public static void install(Context ctx) {
        instance = new ProbeStore(ctx);
    }

    public static ProbeStore instance() {
        return instance;
    }

    /** Files one snapshot, stamped with its package. */
    public void add(String pkg, JSONObject snapshot) {
        if (snapshot == null) {
            return;
        }
        String line;
        try {
            snapshot.put("pkg", pkg);
            line = snapshot.toString();
        } catch (Throwable t) {
            return;
        }
        synchronized (lock) {
            records.addLast(snapshot);
            while (records.size() > CAPACITY) {
                records.removeFirst();
            }
        }
        try {
            writer.execute(() -> append(line));
        } catch (Throwable ignored) {
        }
    }

    /** The newest snapshot for the package, or null. */
    public JSONObject latest(String pkg) {
        synchronized (lock) {
            Iterator<JSONObject> it = records.descendingIterator();
            while (it.hasNext()) {
                JSONObject entry = it.next();
                if (pkg.equals(entry.optString("pkg", ""))) {
                    return entry;
                }
            }
        }
        return null;
    }

    // ---- the file ---------------------------------------------------------

    private void load() {
        if (!file.isFile()) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long length = raf.length();
            long from = Math.max(0L, length - MAX_FILE_BYTES);
            raf.seek(from);
            byte[] tail = new byte[(int) (length - from)];
            raf.readFully(tail);
            // Decoded whole, for the same reason as HookRecordStore: a
            // byte-at-a-time line read corrupts non-ASCII text, and stacks
            // hold other apps' own strings.
            String text = new String(tail, StandardCharsets.UTF_8);
            if (from > 0L) {
                int firstBreak = text.indexOf('\n');
                if (firstBreak < 0) {
                    return;
                }
                text = text.substring(firstBreak + 1);
            }
            for (String line : text.split("\n")) {
                JSONObject entry = parse(line);
                if (entry == null) {
                    continue;
                }
                records.addLast(entry);
                while (records.size() > CAPACITY) {
                    records.removeFirst();
                }
            }
        } catch (Throwable t) {
            Logx.w("could not read back the probe snapshot log: " + t);
        }
    }

    private static JSONObject parse(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(line);
        } catch (Throwable t) {
            return null;
        }
    }

    private void append(String line) {
        try {
            if (file.length() > MAX_FILE_BYTES) {
                rewrite();
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            }
        } catch (Throwable ignored) {
        }
    }

    private void rewrite() {
        List<JSONObject> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(records);
        }
        File temp = new File(file.getParentFile(), FILE + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temp, false)) {
                for (JSONObject entry : snapshot) {
                    out.write(entry.toString().getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                }
            }
            if (temp.renameTo(file)) {
                return;
            }
        } catch (Throwable t) {
            Logx.w("could not trim the probe snapshot log: " + t);
        }
        temp.delete();
    }
}
