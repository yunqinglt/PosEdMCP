package dev.display.api;

/** v1. Explicit transaction IDs are permanent. Every reply is a JSON envelope.
 * Synchronous control-plane calls only; call off the main thread.
 * No sensor samples or framework method invocations travel through this API.
 */
interface IDisplay {
    String ping() = 0;
    String status() = 1;
    String capabilities() = 2;
    String validateProfile(String profileJson) = 3;
    String getProfile(String profileId) = 4;
    String putProfile(String profileJson, long expectedRevision, String requestId) = 5;
    String deleteProfile(String profileId, long expectedRevision, String requestId) = 6;
    String getTarget(String packageName) = 7;
    String applyProfile(String packageName, String profileId, long profileRevision,
            long expectedTargetRevision, String requestId) = 8;
    String clearTarget(String packageName, long expectedTargetRevision, String requestId) = 9;
    String getRuntimeState(String packageName) = 10;
}
