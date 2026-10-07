package dev.posedmcp.xposed;

import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * Carries hook records out of the process that produced them.
 *
 * <p>A hook runs on the application's own thread, in the middle of its call, so
 * whatever it writes to must never block. The bridge writes to a socket, and a
 * peer that has stopped reading would otherwise stall the very method being
 * watched - a hook that breaks its app being worse than no hook at all. A
 * finished record therefore only lands in a bounded queue here, and a thread of
 * our own hands it to the bridge.
 *
 * <p>A full queue drops the record rather than the application, and says so, so
 * that {@link HookRegistry} can count what was lost. A feed that quietly stopped
 * would look exactly like a hook that stopped matching - the one answer this
 * module exists not to give.
 */
final class RecordFeed implements HookRegistry.RecordSink {

    /**
     * Deep enough that a burst survives a reconnecting bridge, and bounded
     * because a method called in a loop can produce records faster than any
     * socket will take them.
     */
    private static final int CAPACITY = 1024;

    private final ArrayBlockingQueue<JSONObject> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final Consumer<JSONObject> emitter;

    RecordFeed(String name, Consumer<JSONObject> emitter) {
        this.emitter = emitter;
        Thread t = new Thread(this::drain, name);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean accept(JSONObject record) {
        return queue.offer(record);
    }

    private void drain() {
        while (true) {
            JSONObject record;
            try {
                record = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                emitter.accept(record);
            } catch (Throwable ignored) {
                // The module is a guest in this process: nothing it does may
                // reach the application, including a transport that is down.
            }
        }
    }
}
