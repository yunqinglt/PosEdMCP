package dev.posedmcp.xposed;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.posedmcp.plugin.HookApi;

/**
 * Runtime hooks installed into a hooked application's process, for watching and
 * for changing what the application does.
 *
 * <p>Watching is the half that makes static analysis pay off: reading smali says
 * where to look, this says what actually flows through. Changing is the half
 * that makes a lot of code injection unnecessary - forcing a method to return a
 * particular value, or swapping an argument, is a description rather than a
 * program, so no DEX has to be written and nothing has to be compiled.
 *
 * <p>It lives inside the target process and runs on the application's own
 * threads, so every hook must be cheap and must never throw: one that breaks the
 * app it is watching is worse than no hook at all.
 */
public final class HookRegistry {

    private static final int DEFAULT_MAX_RECORDS = 200;
    private static final int HARD_MAX_RECORDS = 2000;
    private static final int MAX_ARGUMENTS = 8;
    private static final int VALUE_LIMIT = 120;

    private static final Map<String, Entry> HOOKS = new ConcurrentHashMap<>();

    /** Correlates the two halves of one call, per thread. */
    private static final ThreadLocal<Record> IN_FLIGHT = new ThreadLocal<>();

    /**
     * Identifies a record within this process.
     *
     * <p>Starts somewhere random in a wide range rather than at one. Pids are
     * reused, and a record is identified by the process it came from plus this
     * number - so a run that began counting at one again under the same pid
     * would hand out seqs that a dead run had already used, and its calls would
     * be taken for copies of that run's and dropped.
     */
    private static final AtomicLong NEXT_SEQ =
            new AtomicLong(java.util.concurrent.ThreadLocalRandom.current().nextLong(1L << 48));

    /** Records produced but not taken by the sink. Reported, never hidden. */
    private static final AtomicLong DROPPED = new AtomicLong();

    private static volatile RecordSink sink;

    private HookRegistry() {
    }

    /**
     * Where a finished record is handed so that it outlives this process.
     *
     * <p>Installed by whatever owns the bridge in this process. It is called on
     * the application's own thread, in the middle of the call being recorded, so
     * an implementation must not block and must not throw.
     */
    public interface RecordSink {
        /** @return {@code false} if the record could not be taken and was dropped */
        boolean accept(JSONObject record);
    }

    public static void setRecordSink(RecordSink newSink) {
        sink = newSink;
    }

    /**
     * What a hook does besides watching.
     *
     * <p>All fields are optional and combine: arguments are rewritten before the
     * call, fields after it, and a return value replaces the call entirely.
     */
    static final class Rule {
        boolean hasReturn;
        Object returnValue;
        final Map<Integer, Object> argOverrides = new LinkedHashMap<>();
        final Map<String, Object> fieldAssignments = new LinkedHashMap<>();
        boolean observe = true;

        boolean changesAnything() {
            return hasReturn || !argOverrides.isEmpty() || !fieldAssignments.isEmpty();
        }

        String describe() {
            if (!changesAnything()) {
                return "observe";
            }
            StringBuilder sb = new StringBuilder();
            if (hasReturn) {
                sb.append("return ").append(abbreviate(returnValue));
            }
            if (!argOverrides.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append("args ").append(argOverrides);
            }
            if (!fieldAssignments.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append("fields ").append(fieldAssignments);
            }
            if (observe) {
                sb.append(" (also recording)");
            }
            return sb.toString();
        }
    }

    /**
     * A hook body the caller supplies, instead of the declarative rule.
     *
     * <p>This is what lets a Lua function be a hook: the interpreter hands the
     * closure here, this module holds it, and the closure outlives the script
     * that defined it. It runs on the application's own thread, in the middle of
     * its call, so an implementation must be cheap and must not throw.
     */
    public interface Body {
        /** @param after whether the original method has already run */
        void call(HookApi.HookParam param, boolean after, String target) throws Exception;
    }

    private static final class Record {
        long seq;
        long timestamp;
        String thread;
        String[] args;
        String result;
        String threw;
        /** Why a caller-supplied body failed on this call, if it did. */
        String bodyError;
        boolean altered;
    }

    private static final class Entry {
        final String className;
        final String methodName;
        final int maxRecords;
        final Rule rule;
        /** Set when the body comes from the caller; null for the declarative rule. */
        final Body body;
        /** What a listing says this hook does, when the rule cannot describe it. */
        final String label;
        /** The resolved member, when the signature identified exactly one. */
        final Executable target;
        final Deque<Record> records = new ArrayDeque<>();
        /** Why the caller-supplied body last failed, or null while it is healthy. */
        volatile String bodyError;
        volatile HookApi.Unhook unhook;

        Entry(String className, String methodName, int maxRecords, Rule rule, Body body,
                String label, Executable target) {
            this.className = className;
            this.methodName = methodName;
            this.maxRecords = maxRecords;
            this.rule = rule;
            this.body = body;
            this.label = label;
            this.target = target;
        }

        String effect() {
            return label != null && !label.isEmpty() ? label : rule.describe();
        }

        String key() {
            return className + "#" + methodName;
        }
    }

    // ---- ops --------------------------------------------------------------

    public static JSONObject install(String className, String methodName, String paramTypes,
            int maxRecords, JSONObject spec, ClassLoader appClassLoader) throws Exception {
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("class is required");
        }
        if (methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("method is required");
        }
        Rule rule = parseRule(spec);
        Entry entry = new Entry(className, methodName, cap(maxRecords), rule, null, null,
                resolveTarget(className, methodName, paramTypes, appClassLoader));
        return arm(entry, paramTypes, appClassLoader);
    }

    /**
     * Installs a hook whose body the caller supplies, rather than a rule.
     *
     * <p>Used by the Lua runtime, where the body is a script function held here
     * in Java so that it survives the script that defined it. {@code label} is
     * the description a listing shows, since a rule has nothing to describe.
     */
    public static JSONObject installCustom(String className, String methodName, String paramTypes,
            int maxRecords, Body body, String label, ClassLoader appClassLoader) throws Exception {
        if (className == null || className.isEmpty()) {
            throw new IllegalArgumentException("class is required");
        }
        if (methodName == null || methodName.isEmpty()) {
            throw new IllegalArgumentException("method is required");
        }
        if (body == null) {
            throw new IllegalArgumentException("body is required");
        }
        Entry entry = new Entry(className, methodName, cap(maxRecords), new Rule(), body, label,
                resolveTarget(className, methodName, paramTypes, appClassLoader));
        return arm(entry, paramTypes, appClassLoader);
    }

    private static int cap(int maxRecords) {
        return maxRecords <= 0 ? DEFAULT_MAX_RECORDS : Math.min(maxRecords, HARD_MAX_RECORDS);
    }

    private static JSONObject arm(Entry entry, String paramTypes, ClassLoader appClassLoader)
            throws Exception {
        String className = entry.className;
        String methodName = entry.methodName;

        HookApi api = Framework.hookApi(appClassLoader);
        HookApi.Callback callback = new HookApi.Callback() {
            @Override
            public void before(HookApi.HookParam param) {
                try {
                    if (entry.body != null) {
                        runBody(entry, param, false);
                    } else {
                        rewriteBefore(entry, param);
                    }
                    if (entry.rule.observe) {
                        IN_FLIGHT.set(openRecord(entry, param));
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void after(HookApi.HookParam param) {
                try {
                    if (entry.body != null) {
                        runBody(entry, param, true);
                    } else {
                        rewriteAfter(entry, param);
                    }
                    if (entry.rule.observe) {
                        closeRecord(entry, param);
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        HookApi.Unhook unhook;
        if (paramTypes == null || paramTypes.trim().isEmpty()) {
            unhook = api.hookAllMethods(className, methodName, callback);
        } else {
            List<Class<?>> resolved = new ArrayList<>();
            for (String token : paramTypes.split(",")) {
                resolved.add(resolveType(appClassLoader, token.trim()));
            }
            unhook = api.hookMethod(className, methodName, resolved.toArray(new Class<?>[0]),
                    callback);
        }
        if (unhook == null) {
            throw new IllegalStateException("could not resolve " + className + "." + methodName
                    + " in this process");
        }
        entry.unhook = unhook;

        Entry previous = HOOKS.put(entry.key(), entry);
        if (previous != null && previous.unhook != null) {
            try {
                previous.unhook.unhook();
            } catch (Throwable ignored) {
            }
        }

        JSONObject out = new JSONObject();
        out.put("hooked", true);
        out.put("class", className);
        out.put("method", methodName);
        out.put("effect", entry.effect());
        if (entry.body == null && entry.rule.changesAnything()) {
            out.put("note", "This hook changes behaviour, not just records it.");
        }
        out.put("maxRecords", entry.maxRecords);
        return out;
    }

    /**
     * Called by a hook body that has just changed the call it is looking at.
     *
     * <p>Only a caller-supplied body knows this: a rule declares up front whether
     * it changes anything, but a Lua body decides per call. Without it,
     * hook_records would show a Lua hook's calls as untouched observations while
     * they were in fact being rewritten - the sort of quiet wrong answer this
     * module exists to avoid.
     */
    static void markAltered() {
        Record record = IN_FLIGHT.get();
        if (record != null) {
            record.altered = true;
        }
    }

    /**
     * Runs a caller-supplied body on the application's own thread.
     *
     * <p>A failure here is recorded rather than allowed to reach the app, but it
     * is <em>not</em> swallowed silently: a Lua hook whose body throws on every
     * call would otherwise look exactly like a hook that matches nothing, which
     * is the failure mode this whole module was rebuilt to avoid.
     */
    private static void runBody(Entry entry, HookApi.HookParam param, boolean after) {
        try {
            entry.body.call(param, after, entry.key());
            entry.bodyError = null;
        } catch (Throwable t) {
            entry.bodyError = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
    }

    /** Applies the declarative rule before the original runs. */
    private static void rewriteBefore(Entry entry, HookApi.HookParam param) {
        Rule r = entry.rule;

        // Rewriting the array in place is how classic Xposed changes what the
        // original receives.
        if (!r.argOverrides.isEmpty() && param.args() != null) {
            Class<?>[] types = parameterTypes(entry.target);
            for (Map.Entry<Integer, Object> override : r.argOverrides.entrySet()) {
                int index = override.getKey();
                if (index < 0 || index >= param.args().length) {
                    continue;
                }
                Class<?> wanted = types != null && index < types.length ? types[index]
                        : typeOf(param.args()[index]);
                param.args()[index] = coerce(override.getValue(), wanted);
            }
        }

        if (r.hasReturn) {
            // Setting a result here makes the framework skip the original method
            // outright.
            param.setResult(coerce(r.returnValue, returnType(entry.target)));
        }
    }

    /** Applies the declarative rule after the original runs. */
    private static void rewriteAfter(Entry entry, HookApi.HookParam param) {
        Rule r = entry.rule;
        if (r.fieldAssignments.isEmpty() || param.thisObject() == null) {
            return;
        }
        for (Map.Entry<String, Object> assignment : r.fieldAssignments.entrySet()) {
            Object current = param.getObjectField(assignment.getKey());
            param.setObjectField(assignment.getKey(),
                    coerce(assignment.getValue(), typeOf(current)));
        }
    }

    private static Record openRecord(Entry entry, HookApi.HookParam param) {
        Record record = new Record();
        record.timestamp = System.currentTimeMillis();
        record.thread = Thread.currentThread().getName();
        Object[] args = param.args();
        int n = args == null ? 0 : Math.min(args.length, MAX_ARGUMENTS);
        record.args = new String[n];
        for (int i = 0; i < n; i++) {
            record.args[i] = describe(args[i]);
        }
        record.altered = entry.body == null && entry.rule.changesAnything();
        return record;
    }

    private static void closeRecord(Entry entry, HookApi.HookParam param) {
        Record record = IN_FLIGHT.get();
        IN_FLIGHT.remove();
        if (record == null) {
            return;
        }
        Throwable thrown = param.throwable();
        if (thrown != null) {
            record.threw = thrown.getClass().getName()
                    + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
        } else {
            record.result = describe(param.result());
        }
        if (entry.bodyError != null) {
            record.bodyError = entry.bodyError;
        }
        // Assigned here rather than only when the record is published, so that a
        // copy of it arriving from either path can be recognised as the same one.
        record.seq = NEXT_SEQ.incrementAndGet();
        synchronized (entry.records) {
            entry.records.addLast(record);
            while (entry.records.size() > entry.maxRecords) {
                entry.records.removeFirst();
            }
        }
        publish(entry.key(), record);
    }

    /**
     * Hands a finished record to the app, which is what makes it outlive this
     * process.
     *
     * <p>The process a hook lives in is the shortest-lived thing involved: a ROM
     * that reclaims an app a few seconds after it leaves the foreground takes
     * every record in it along. Reading records on demand can therefore only
     * ever describe the last few seconds, and reports an empty list for a hook
     * that was in fact recording the whole time - indistinguishable from one
     * that never matched. Pushing each record out as it is made costs nothing
     * the hook was not already paying for, and lets hook_records answer for a
     * process that is already gone.
     *
     * <p>The local deque is still kept: it is the only copy that survives the
     * bridge being down, so a live process can always answer for itself.
     */
    private static void publish(String target, Record record) {
        RecordSink current = sink;
        if (current == null) {
            return;
        }
        boolean taken = false;
        try {
            taken = current.accept(toJson(target, record));
        } catch (Throwable ignored) {
        }
        if (!taken) {
            DROPPED.incrementAndGet();
        }
    }

    public static JSONObject records(String subject, int limit) throws Exception {
        String filter = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        int max = limit <= 0 ? 100 : Math.min(limit, HARD_MAX_RECORDS);

        JSONArray array = new JSONArray();
        JSONArray hooked = new JSONArray();
        for (Entry entry : HOOKS.values()) {
            if (!filter.isEmpty() && !entry.key().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            JSONObject summary = new JSONObject();
            summary.put("target", entry.key());
            summary.put("effect", entry.effect());
            if (entry.bodyError != null) {
                // A hook that is installed but failing looks exactly like one
                // that never matches, unless this is said out loud.
                summary.put("bodyError", entry.bodyError);
            }
            synchronized (entry.records) {
                summary.put("recordCount", entry.records.size());
            }
            hooked.put(summary);

            synchronized (entry.records) {
                Iterator<Record> it = entry.records.descendingIterator();
                while (it.hasNext() && array.length() < max) {
                    array.put(toJson(entry.key(), it.next()));
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("hooks", hooked);
        out.put("records", array);
        long dropped = DROPPED.get();
        if (dropped > 0) {
            // Said out loud rather than left as a gap in the list: an agent that
            // cannot tell "nothing matched" from "the record was lost" draws the
            // wrong conclusion about the hook it is debugging.
            out.put("droppedRecords", dropped);
        }
        if (hooked.length() == 0) {
            out.put("note", HOOKS.isEmpty()
                    ? "nothing is hooked in this process yet"
                    : "no hook matches '" + subject + "'");
        }
        return out;
    }

    public static JSONObject clear(String subject) throws Exception {
        String filter = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        JSONArray removed = new JSONArray();
        for (Iterator<Map.Entry<String, Entry>> it = HOOKS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Entry> mapEntry = it.next();
            if (!filter.isEmpty() && !mapEntry.getKey().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            Entry entry = mapEntry.getValue();
            if (entry.unhook != null) {
                try {
                    entry.unhook.unhook();
                } catch (Throwable ignored) {
                }
            }
            it.remove();
            removed.put(mapEntry.getKey());
        }
        JSONObject out = new JSONObject();
        out.put("removed", removed);
        out.put("remainingHooks", HOOKS.size());
        return out;
    }

    // ---- rule parsing -----------------------------------------------------

    /**
     * Reads the declarative rule out of the request.
     *
     * <p>Values arrive as strings because that is what a tool argument is, and
     * are parsed as JSON when they can be: "false" becomes a boolean, "42" a
     * number, "\"x\"" the string x. Anything that is not valid JSON is taken
     * literally, which is what someone typing a plain word means.
     */
    private static Rule parseRule(JSONObject spec) throws Exception {
        Rule rule = new Rule();
        if (spec == null) {
            return rule;
        }
        rule.observe = spec.optBoolean("observe", true);

        if (spec.has("return_value") && !spec.isNull("return_value")) {
            rule.hasReturn = true;
            rule.returnValue = parseValue(spec.get("return_value"));
        }

        JSONObject setArgs = asObject(spec.opt("set_args"), "set_args");
        if (setArgs != null) {
            Iterator<String> keys = setArgs.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                try {
                    rule.argOverrides.put(Integer.parseInt(key.trim()), parseValue(setArgs.get(key)));
                } catch (NumberFormatException ignored) {
                    throw new IllegalArgumentException(
                            "set_args keys are argument indexes, got '" + key + "'");
                }
            }
        }

        JSONObject setFields = asObject(spec.opt("set_fields"), "set_fields");
        if (setFields != null) {
            Iterator<String> keys = setFields.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                rule.fieldAssignments.put(key, parseValue(setFields.get(key)));
            }
        }
        return rule;
    }

    /**
     * Accepts a JSON object, or a string holding one.
     *
     * <p>Clients differ in how they send a nested structure: a schema typed as
     * an object usually arrives as one, but a client that stringifies it would
     * otherwise be silently ignored, and a hook that quietly does nothing is far
     * worse than an error.
     */
    private static JSONObject asObject(Object raw, String field) {
        if (raw == null || raw == JSONObject.NULL) {
            return null;
        }
        if (raw instanceof JSONObject) {
            return (JSONObject) raw;
        }
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return new JSONObject(text);
            } catch (Throwable t) {
                throw new IllegalArgumentException(
                        field + " must be a JSON object, got: " + text);
            }
        }
        throw new IllegalArgumentException(
                field + " must be a JSON object, got " + raw.getClass().getSimpleName());
    }

    /**
     * Turns a rule value into the Java value it names.
     *
     * <p>Only a value that is <em>shaped</em> like JSON is parsed as JSON, and
     * everything else is taken literally. Handing any string to a JSON reader
     * is not safe: it reads a bare word only up to the first delimiter, so
     * "hello world" would silently become "hello" - a hook that quietly does
     * almost the right thing is worse than one that fails.
     */
    private static Object parseValue(Object raw) {
        if (raw == null || raw == JSONObject.NULL) {
            return null;
        }
        if (!(raw instanceof String)) {
            return raw;
        }
        String text = (String) raw;
        String trimmed = text.trim();
        if (trimmed.isEmpty() || !looksLikeJson(trimmed)) {
            return text;
        }
        try {
            return new JSONTokener(trimmed).nextValue();
        } catch (Throwable t) {
            return text;
        }
    }

    private static boolean looksLikeJson(String trimmed) {
        char first = trimmed.charAt(0);
        if (first == '{' || first == '[' || first == '"') {
            return true;
        }
        if ("true".equals(trimmed) || "false".equals(trimmed) || "null".equals(trimmed)) {
            return true;
        }
        // A number, and nothing that merely starts with one.
        try {
            Double.parseDouble(trimmed);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Fits a value to the type it is being placed into.
     *
     * <p>Without this, "false" would arrive as the String "false" and a method
     * expecting a boolean would throw - which is the kind of failure that looks
     * like the hook did nothing.
     */
    static Object coerce(Object value, Class<?> target) {
        if (target == null || target == Object.class) {
            return value;
        }
        if (value == null) {
            // Primitives have no null; the framework would reject it.
            if (target == boolean.class) {
                return false;
            }
            if (target == char.class) {
                return (char) 0;
            }
            if (target.isPrimitive()) {
                return 0;
            }
            if (target.isInstance(null)) {
                return null;
            }
            return null;
        }
        if (target.isInstance(value)) {
            return value;
        }
        if (target == String.class || CharSequence.class.isAssignableFrom(target)) {
            return String.valueOf(value);
        }
        if (value instanceof Number) {
            Number n = (Number) value;
            if (target == int.class || target == Integer.class) {
                return n.intValue();
            }
            if (target == long.class || target == Long.class) {
                return n.longValue();
            }
            if (target == short.class || target == Short.class) {
                return n.shortValue();
            }
            if (target == byte.class || target == Byte.class) {
                return n.byteValue();
            }
            if (target == float.class || target == Float.class) {
                return n.floatValue();
            }
            if (target == double.class || target == Double.class) {
                return n.doubleValue();
            }
            if (target == char.class || target == Character.class) {
                return (char) n.intValue();
            }
        }
        if (value instanceof Boolean) {
            if (target == boolean.class || target == Boolean.class) {
                return value;
            }
            if (Number.class.isAssignableFrom(target) || target.isPrimitive()) {
                return ((Boolean) value) ? 1 : 0;
            }
        }
        if (target.isEnum() && value instanceof String) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object constant = Enum.valueOf((Class<? extends Enum>) target, (String) value);
                return constant;
            } catch (Throwable ignored) {
                // Fall through and let the caller see the raw value.
            }
        }
        return value;
    }

    private static Class<?> typeOf(Object value) {
        return value == null ? null : value.getClass();
    }

    private static Class<?>[] parameterTypes(Executable target) {
        return target == null ? null : target.getParameterTypes();
    }

    private static Class<?> returnType(Executable target) {
        return target instanceof Method ? ((Method) target).getReturnType() : null;
    }

    /**
     * Finds the member being hooked, when the request identifies exactly one.
     *
     * <p>Its declared types are what make argument and return rewriting
     * reliable; when the hook is a wildcard over several overloads there is
     * nothing to resolve, and coercion falls back to the type of whatever value
     * is already there.
     */
    private static Executable resolveTarget(String className, String methodName, String paramTypes,
            ClassLoader loader) {
        try {
            Class<?> clazz = Class.forName(className, false, loader);
            if (paramTypes != null && !paramTypes.trim().isEmpty()) {
                List<Class<?>> types = new ArrayList<>();
                for (String token : paramTypes.split(",")) {
                    types.add(resolveType(loader, token.trim()));
                }
                return clazz.getDeclaredMethod(methodName, types.toArray(new Class<?>[0]));
            }
            Executable found = null;
            int matches = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    found = method;
                    matches++;
                }
            }
            return matches == 1 ? found : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Shared with {@link MethodInvoker}, which resolves the same type names. */
    static Class<?> resolveType(ClassLoader loader, String token) throws Exception {
        switch (token) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "float": return float.class;
            case "double": return double.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "void": return void.class;
            default:
                if (token.endsWith("[]")) {
                    return java.lang.reflect.Array.newInstance(
                            resolveType(loader, token.substring(0, token.length() - 2)), 0).getClass();
                }
                return Class.forName(token, false, loader);
        }
    }

    // ---- helpers ----------------------------------------------------------

    private static JSONObject toJson(String key, Record record) {
        JSONObject o = new JSONObject();
        try {
            o.put("target", key);
            o.put("seq", record.seq);
            o.put("ts", record.timestamp);
            o.put("thread", record.thread);
            JSONArray args = new JSONArray();
            if (record.args != null) {
                for (String a : record.args) {
                    args.put(a);
                }
            }
            o.put("args", args);
            if (record.threw != null) {
                o.put("threw", record.threw);
            } else {
                o.put("result", record.result);
            }
            if (record.bodyError != null) {
                o.put("bodyError", record.bodyError);
            }
            if (record.altered) {
                o.put("altered", true);
            }
        } catch (Throwable ignored) {
        }
        return o;
    }

    /** Values are truncated hard: a hooked method can return anything at all. */
    private static String describe(Object value) {
        try {
            if (value == null) {
                return "null";
            }
            String text = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
            return text.length() <= VALUE_LIMIT ? text : text.substring(0, VALUE_LIMIT) + "...";
        } catch (Throwable t) {
            return "<unprintable>";
        }
    }

    private static String abbreviate(Object value) {
        String text = describe(value);
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }

    /** Exposed so a listing can show what is currently installed. */
    public static Map<String, String> snapshot() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Entry entry : HOOKS.values()) {
            out.put(entry.key(), entry.effect());
        }
        return out;
    }
}
