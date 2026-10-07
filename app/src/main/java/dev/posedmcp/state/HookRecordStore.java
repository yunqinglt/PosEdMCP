package dev.posedmcp.state;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.posedmcp.Logx;

/**
 * Hook records, kept by the app rather than by the process that produced them.
 *
 * <p>A hook records what flows through a method in the process it was installed
 * into, and that process is the shortest-lived thing involved: this ROM reclaims
 * an app a few seconds after it leaves the foreground. Reading records back from
 * it on demand therefore described only those last few seconds - and once they
 * had elapsed, it reported an empty list for a hook that had been recording the
 * whole time. That is indistinguishable from a hook that never matched, which is
 * the one answer a diagnostic tool must not give wrongly.
 *
 * <p>So every record is pushed here as it is made. Bounded twice over: a ring in
 * memory, and an append-only file trimmed back to that ring once it grows past
 * {@link #MAX_FILE_BYTES}. The file is what lets a record outlive not just the
 * hooked process but this app, which the ROM force-stops on a schedule of its
 * own - and records from a process that is already gone are exactly the ones
 * worth having.
 *
 * <p>This does mean argument values from third-party apps reach the disk. They
 * are the point of the feature, but they are someone else's data: the file is
 * bounded, lives in the app's private storage, and is never sent anywhere.
 */
public final class HookRecordStore {

    private static final String FILE = "hook-records.jsonl";
    private static final int CAPACITY = 1000;
    private static final long MAX_FILE_BYTES = 256L * 1024L;

    private final File file;
    private final Deque<JSONObject> records = new ArrayDeque<>();
    /** Guards {@link #records} only - never the disk, which has its own thread. */
    private final Object lock = new Object();

    /**
     * Writes on a thread of its own, which is allowed to die once it is idle.
     *
     * <p>{@link #add} is called from the bridge's read loop, and that loop also
     * carries the replies to every request the app sends a hooked process - so a
     * disk write in it would slow down, and eventually stall, the very calls it
     * is feeding. Nothing here blocks that thread.
     *
     * <p>The idle timeout is not decoration. This store is built afresh every
     * time the service starts and the ROM restarts it on a schedule of its own,
     * so a worker that outlived its store would leave one thread behind per
     * restart, for as long as the device is up.
     */
    private final Executor writer =
            new ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    public HookRecordStore(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.file = new File((app == null ? ctx : app).getFilesDir(), FILE);
        load();
    }

    /**
     * Files one record.
     *
     * @param source the peer key it came from, e.g. {@code app:com.x:1234}
     */
    public void add(String source, JSONObject record) {
        if (record == null) {
            return;
        }
        String line;
        try {
            record.put("source", source);
            line = record.toString();
        } catch (Throwable t) {
            return;
        }
        synchronized (lock) {
            records.addLast(record);
            while (records.size() > CAPACITY) {
                records.removeFirst();
            }
        }
        try {
            writer.execute(() -> append(line));
        } catch (Throwable ignored) {
            // A refused write loses one record; the ring still has it, and the
            // alternative is a hooked call that failed.
        }
    }

    /**
     * Records from the given sources, newest first.
     *
     * @param sourcePrefixes peer key prefixes to include, so that records from
     *                       processes that have already died are matched too
     * @param subject        only records whose target contains this text
     */
    public List<JSONObject> query(Collection<String> sourcePrefixes, String subject, int limit) {
        String filter = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        List<JSONObject> out = new ArrayList<>();
        synchronized (lock) {
            Iterator<JSONObject> it = records.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                JSONObject entry = it.next();
                if (!filter.isEmpty()
                        && !entry.optString("target", "").toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                if (matches(entry.optString("source", ""), sourcePrefixes)) {
                    out.add(entry);
                }
            }
        }
        return out;
    }

    private static boolean matches(String source, Collection<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return true;
        }
        for (String prefix : prefixes) {
            if (source.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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
            // Decoded as a whole, not line by line: a byte-at-a-time line read
            // turns every non-ASCII character into mojibake, and what these
            // records hold is other apps' own text.
            String text = new String(tail, StandardCharsets.UTF_8);
            if (from > 0L) {
                int firstBreak = text.indexOf('\n');
                // The tail began in the middle of a line, and possibly in the
                // middle of a character; neither fragment is worth keeping.
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
            // A log that cannot be read is worth less than an app that starts.
            Logx.w("could not read back the hook record log: " + t);
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

    /** One record, on the writer thread. Never holds {@link #lock} across a write. */
    private void append(String line) {
        try {
            if (file.length() > MAX_FILE_BYTES) {
                rewrite();
                return;
            }
            // Opened per record rather than held open: this app is killed
            // without warning, so anything buffered would simply be lost.
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            }
        } catch (Throwable ignored) {
        }
    }

    /** Replaces the file with what the ring still holds. */
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
            Logx.w("could not trim the hook record log: " + t);
        }
        temp.delete();
    }
}
