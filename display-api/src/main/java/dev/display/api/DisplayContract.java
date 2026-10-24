package dev.display.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.time.ZoneId;
import java.util.Locale;

/** Shared v1 wire policy. Pure Java so the same validation runs on both sides. */
public final class DisplayContract {
    public static final int API_VERSION = 1;
    public static final int MAX_JSON_CHARS = 32_768;
    public static final int MAX_REPLY_CHARS = 65_536;
    public static final int MAX_RULES = 64;
    public static final String PACKAGE = "dev.display";
    public static final String SERVICE = "dev.display.DisplayAidlService";
    public static final String CLIENT_PACKAGE = "dev.posedmcp";
    private DisplayContract() {}

    public static JSONObject ok(JSONObject data) throws JSONException {
        if (data == null) throw new IllegalArgumentException("Core returned no data");
        return new JSONObject().put("ok", true).put("apiVersion", API_VERSION).put("data", data);
    }
    public static JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("ok", false).put("apiVersion", API_VERSION)
                    .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    public static JSONObject decodeReply(String raw) {
        try {
            if (raw == null || raw.length() > MAX_REPLY_CHARS)
                return error("INVALID_REPLY", "Display returned an empty or oversized reply");
            org.json.JSONTokener tokener = new org.json.JSONTokener(raw);
            Object parsed = tokener.nextValue();
            if (!(parsed instanceof JSONObject) || tokener.nextClean() != 0)
                return error("INVALID_REPLY", "Expected a Display JSON envelope");
            JSONObject reply = (JSONObject)parsed;
            long version = integer(reply.get("apiVersion"), 0, 9_007_199_254_740_991L);
            if (version != API_VERSION) return error("VERSION_MISMATCH", "Display AIDL version differs");
            Object success = reply.get("ok");
            if (!(success instanceof Boolean) || ((Boolean)success ? reply.optJSONObject("data") == null : reply.optJSONObject("error") == null))
                return error("INVALID_REPLY", "Malformed Display envelope");
            if (!(Boolean)success) {
                JSONObject failure = reply.getJSONObject("error");
                strictString(failure.get("code")); strictString(failure.get("message"));
            }
            return reply;
        } catch (IllegalArgumentException | JSONException e) {
            return error("INVALID_REPLY", "Malformed Display envelope");
        }
    }
    public static String id(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("id/requestId must be 1..64 ASCII letters, digits, _, . or -");
        return id;
    }
    public static String target(String pkg) {
        if (pkg == null || pkg.length() > 255 || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                || pkg.equals(PACKAGE) || pkg.equals(CLIENT_PACKAGE) || pkg.equals("dev.deploy"))
            throw new IllegalArgumentException("target must be an ordinary application package; system and companion targets are excluded");
        return pkg;
    }
    public static long revision(long n) {
        if (n < 0 || n > 9_007_199_254_740_991L)
            throw new IllegalArgumentException("revision must be a nonnegative JSON-safe integer");
        return n;
    }
    public static long integer(Object value, long min, long max) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long))
            throw new IllegalArgumentException("expected an integer, without coercion");
        long n = ((Number)value).longValue();
        if (n < min || n > max) throw new IllegalArgumentException("integer outside " + min + ".." + max);
        return n;
    }
    public static JSONObject parse(String json) throws JSONException {
        if (json == null || json.length() > MAX_JSON_CHARS || !json.trim().startsWith("{"))
            throw new IllegalArgumentException("profile must be a JSON object <= " + MAX_JSON_CHARS + " UTF-16 code units");
        org.json.JSONTokener tokener = new org.json.JSONTokener(json);
        Object value = tokener.nextValue();
        if (!(value instanceof JSONObject) || tokener.nextClean() != 0)
            throw new IllegalArgumentException("expected one JSON object with no trailing data");
        return (JSONObject)value;
    }
    private static void fields(JSONObject o, Set<String> allowed) {
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw new IllegalArgumentException("unknown field: " + key);
        }
    }
    public static JSONObject validateProfile(String json) throws JSONException {
        JSONObject p = parse(json);
        fields(p, Set.of("schemaVersion", "id", "rules"));
        integer(p.get("schemaVersion"), 1, 1);
        id(strictString(p.get("id")));
        JSONArray rules = p.getJSONArray("rules");
        if (rules.length() < 1 || rules.length() > MAX_RULES)
            throw new IllegalArgumentException("profile requires 1..64 rules; use clearTarget to restore originals");
        Set<String> seen = new HashSet<>();
        JSONObject values = new JSONObject();
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.getJSONObject(i);
            fields(rule, Set.of("key", "value"));
            String key = strictString(rule.get("key"));
            if (!seen.add(key)) throw new IllegalArgumentException("duplicate rule: " + key);
            Object value = rule.get("value");
            validateValue(key, value);
            values.put(key, value);
        }
        if (values.has("time.wallOffsetMillis") && values.has("time.wallFixedMillis"))
            throw new IllegalArgumentException("wall offset and fixed time are mutually exclusive");
        return p;
    }
    public static String strictString(Object o) {
        if (!(o instanceof String)) throw new IllegalArgumentException("expected string without coercion");
        return (String)o;
    }
    private static void bool(Object v) {
        if (!(v instanceof Boolean)) throw new IllegalArgumentException("expected boolean without coercion");
    }
    private static void vector(Object v, int length, double max) throws JSONException {
        if (!(v instanceof JSONArray) || ((JSONArray)v).length() != length)
            throw new IllegalArgumentException("expected numeric vector length " + length);
        for (int i = 0; i < length; i++) {
            Object n = ((JSONArray)v).get(i);
            if (!(n instanceof Number) || !Double.isFinite(((Number)n).doubleValue()) || Math.abs(((Number)n).doubleValue()) > max)
                throw new IllegalArgumentException("non-finite or out-of-range sensor value");
        }
    }
    private static void validateValue(String key, Object v) throws JSONException {
        switch (key) {
            case "location.position":
                if (!(v instanceof JSONObject)) throw new IllegalArgumentException("position requires latitude, longitude, accuracyMeters");
                JSONObject position = (JSONObject)v;
                fields(position, Set.of("latitude", "longitude", "accuracyMeters"));
                number(position.get("latitude"), -90, 90);
                number(position.get("longitude"), -180, 180);
                number(position.get("accuracyMeters"), 0.1, 100_000);
                break;
            case "time.wallOffsetMillis": integer(v, -315_576_000_000L, 315_576_000_000L); break;
            case "time.wallFixedMillis": integer(v, 0, 4_102_444_800_000L); break;
            case "locale.timeZone":
                try { ZoneId.of(strictString(v)); } catch (Exception e) { throw new IllegalArgumentException("invalid IANA time zone"); } break;
            case "locale.languageTags":
                if (!(v instanceof JSONArray) || ((JSONArray)v).length() < 1 || ((JSONArray)v).length() > 8)
                    throw new IllegalArgumentException("languageTags requires 1..8 BCP-47 tags");
                Set<String> tags = new HashSet<>();
                for (int i = 0; i < ((JSONArray)v).length(); i++) {
                    String tag = strictString(((JSONArray)v).get(i));
                    try {
                        if (tag.length() > 64 || !tags.add(new Locale.Builder().setLanguageTag(tag).build().toLanguageTag().toLowerCase(Locale.ROOT)))
                            throw new IllegalArgumentException("duplicate/overlong tag");
                    } catch (Exception e) { throw new IllegalArgumentException("invalid/duplicate BCP-47 tag"); }
                } break;
            case "network.connected": case "network.validated": case "network.metered":
            case "state.powerSave": case "state.interactive": case "state.deviceIdle":
            case "capture.screenshotEvent": case "capture.recordingVisible":
            case "security.keyguardLocked": case "security.deviceLocked":
            case "security.keyguardSecure": case "security.deviceSecure":
            case "audio.musicActive": case "audio.microphoneMute": case "audio.speakerphoneOn":
            case "state.lightDeviceIdle": case "network.wifiEnabled":
            case "settings.airplaneMode": case "settings.autoRotate": case "settings.time24Hour":
                bool(v); break;
            case "audio.ringerMode": integer(v, 0, 2); break;
            case "audio.mode": integer(v, 0, 3); break;
            case "audio.musicVolumePercent": case "audio.alarmVolumePercent": integer(v, 0, 100); break;
            case "display.rotation": integer(v, 0, 3); break;
            case "display.refreshRate": number(v, 1, 1000); break;
            case "display.state": case "state.thermalStatus": integer(v, 0, 6); break;
            case "settings.brightness": integer(v, 0, 255); break;
            case "network.wifiRssi": integer(v, -127, 0); break;
            case "network.wifiLinkSpeed": integer(v, -1, 100_000); break;
            case "network.transport":
                if (!Set.of("none", "wifi", "cellular", "ethernet", "vpn").contains(strictString(v)))
                    throw new IllegalArgumentException("unknown network transport"); break;
            case "sensors.accelerometer": vector(v, 3, 200); break;
            case "sensors.gyroscope": vector(v, 3, 100); break;
            case "sensors.magneticField": vector(v, 3, 10_000); break;
            case "sensors.light": vector(v, 1, 200_000); break;
            case "sensors.pressure": vector(v, 1, 2_000); break;
            case "sensors.proximity": vector(v, 1, 1_000); break;
            case "sensors.linearAcceleration": case "sensors.gravity": vector(v, 3, 200); break;
            case "sensors.ambientTemperature": vector(v, 1, 1000); number(((JSONArray)v).get(0), -273.15, 1000); break;
            case "sensors.relativeHumidity": vector(v, 1, 100); number(((JSONArray)v).get(0), 0, 100); break;
            case "sensors.stepCounter": vector(v, 1, 1_000_000_000_000d); number(((JSONArray)v).get(0), 0, 1_000_000_000_000d); break;
            case "state.batteryLevel": integer(v, 0, 100); break;
            case "state.batteryStatus": integer(v, 1, 5); break;
            case "state.batteryPlugged":
                long n = integer(v, 0, 8);
                if (n != 0 && n != 1 && n != 2 && n != 4 && n != 8) throw new IllegalArgumentException("invalid plugged constant"); break;
            default: throw new IllegalArgumentException("unknown rule key: " + key);
        }
        if (Set.of("sensors.light", "sensors.pressure", "sensors.proximity").contains(key)
                && ((Number)((JSONArray)v).get(0)).doubleValue() < 0) throw new IllegalArgumentException("sensor scalar cannot be negative");
    }
    private static void number(Object value, double min, double max) {
        if (!(value instanceof Number) || !Double.isFinite(((Number)value).doubleValue())
                || ((Number)value).doubleValue() < min || ((Number)value).doubleValue() > max)
            throw new IllegalArgumentException("expected finite number in " + min + ".." + max);
    }
    public static JSONObject capabilities() throws JSONException {
        JSONArray rules = new JSONArray();
        String[][] entries = {
            {"location.position", "object", "latitude/longitude degrees, accuracyMeters"},
            {"time.wallOffsetMillis", "integer", "ms"}, {"time.wallFixedMillis", "integer", "epoch ms"},
            {"locale.timeZone", "string", "IANA"}, {"locale.languageTags", "array", "BCP-47"},
            {"network.connected", "boolean", ""}, {"network.validated", "boolean", ""},
            {"network.metered", "boolean", ""}, {"network.transport", "string", "enum"},
            {"sensors.accelerometer", "array", "m/s^2, xyz"}, {"sensors.gyroscope", "array", "rad/s, xyz"},
            {"sensors.magneticField", "array", "microtesla, xyz"}, {"sensors.light", "array", "lux"},
            {"sensors.pressure", "array", "hPa"}, {"sensors.proximity", "array", "cm"},
            {"state.batteryLevel", "integer", "%"}, {"state.batteryStatus", "integer", "BatteryManager constant"},
            {"state.batteryPlugged", "integer", "BatteryManager constant"},
            {"state.powerSave", "boolean", ""}, {"state.interactive", "boolean", ""}, {"state.deviceIdle", "boolean", ""}
            ,{"capture.screenshotEvent", "boolean", "event: false=suppress, true=emit once per registration/activation"},
            {"capture.recordingVisible", "boolean", "app visible in recording (API 35+)"},
            {"security.keyguardLocked", "boolean", ""}, {"security.deviceLocked", "boolean", ""},
            {"security.keyguardSecure", "boolean", ""}, {"security.deviceSecure", "boolean", ""},
            {"audio.ringerMode", "integer", "0=silent,1=vibrate,2=normal"}, {"audio.mode", "integer", "0..3"},
            {"audio.musicActive", "boolean", ""}, {"audio.microphoneMute", "boolean", ""}, {"audio.speakerphoneOn", "boolean", ""},
            {"audio.musicVolumePercent", "integer", "% of stream index range"}, {"audio.alarmVolumePercent", "integer", "% of stream index range"},
            {"display.rotation", "integer", "Surface rotation 0..3"}, {"display.refreshRate", "number", "Hz"}, {"display.state", "integer", "Display state 0..6"},
            {"state.thermalStatus", "integer", "PowerManager thermal status 0..6"}, {"state.lightDeviceIdle", "boolean", "API 33+"},
            {"settings.airplaneMode", "boolean", ""}, {"settings.autoRotate", "boolean", ""}, {"settings.time24Hour", "boolean", ""},
            {"settings.brightness", "integer", "0..255"}, {"network.wifiEnabled", "boolean", ""},
            {"network.wifiRssi", "integer", "dBm"}, {"network.wifiLinkSpeed", "integer", "Mbps"},
            {"sensors.linearAcceleration", "array", "m/s^2, xyz"}, {"sensors.gravity", "array", "m/s^2, xyz"},
            {"sensors.ambientTemperature", "array", "Celsius"}, {"sensors.relativeHumidity", "array", "%"}, {"sensors.stepCounter", "array", "steps"}
        };
        for (String[] entry : entries) {
            int minimum = entry[0].equals("capture.screenshotEvent") ? 34
                    : entry[0].equals("capture.recordingVisible") ? 35
                    : entry[0].equals("state.lightDeviceIdle") ? 33 : 31;
            rules.put(new JSONObject().put("key", entry[0]).put("type", entry[1])
                    .put("unit", entry[2]).put("minimumSdk", minimum)
                    .put("contractSupported", true).put("runtimeSupported", false));
        }
        return new JSONObject().put("schemaVersion", 1).put("rules", rules)
                .put("maxProfileChars", MAX_JSON_CHARS).put("maxRules", MAX_RULES)
                .put("coreAvailable", false).put("validationOnly", true);
    }
}
