package dev.display.api;

/** Read/ack channel. No profile or target mutations. Caller UID must be in Display's actual scope. */
interface IDisplayRuntime {
    String exchange(String packageName, String processName, String sessionId, String reportJson) = 0;
}
