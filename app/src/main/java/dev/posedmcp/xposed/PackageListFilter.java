package dev.posedmcp.xposed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Filters bounded stdout records; never executes or rewrites Java child commands. */
final class PackageListFilter extends InputStream {
    private final InputStream source;
    private final Predicate<String> hidden;
    private final BooleanSupplier active;
    private byte[] pending = new byte[0];
    private int offset;
    private boolean continuation;

    PackageListFilter(InputStream source, String target, BooleanSupplier active) {
        this(source, active, line -> hiddenLine(line, target));
    }

    PackageListFilter(InputStream source, BooleanSupplier active, Predicate<String> hidden) {
        this.source = new java.io.BufferedInputStream(source); this.active = active; this.hidden = hidden;
    }

    static boolean command(List<String> args) {
        if (args == null || args.isEmpty()) return false;
        String program = args.get(0);
        program = program.substring(program.lastIndexOf('/') + 1);
        if ((program.equals("sh") || program.equals("bash")) && args.size() == 3
                && args.get(1).equals("-c")) {
            List<String> words = words(args.get(2));
            return words != null && command(words);
        }
        int start = program.equals("pm") ? 1
                : program.equals("cmd") && args.size() > 1 && args.get(1).equals("package") ? 2 : -1;
        return start >= 0 && args.size() > start + 1 && args.get(start).equals("list")
                && (args.get(start + 1).equals("packages") || args.get(start + 1).equals("package"));
    }

    static List<String> words(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder(); char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ("|;&><\n\r`$\\".indexOf(c) >= 0) return null;
            if (c == '\'' || c == '"') {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
                else word.append(c);
            } else if (Character.isWhitespace(c) && quote == 0) {
                if (word.length() > 0) { out.add(word.toString()); word.setLength(0); }
            } else word.append(c);
        }
        if (quote != 0) return null;
        if (word.length() > 0) out.add(word.toString());
        return out;
    }

    static boolean hiddenLine(String line, String target) {
        String value = line.trim();
        if (!value.startsWith("package:")) return false;
        value = value.substring(8).trim();
        int space = 0;
        while (space < value.length() && !Character.isWhitespace(value.charAt(space))) space++;
        value = value.substring(0, space);
        int equals = value.lastIndexOf('='); // pm -f: APK path=packageName
        if (equals >= 0) value = value.substring(equals + 1);
        return ConcealmentPackages.hidden(value, target);
    }

    private boolean fill() throws IOException {
        while (offset == pending.length) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(256);
            int value;
            // Bound memory even when a child emits a nonstandard, unterminated line.
            while (line.size() < 65536 && (value = source.read()) != -1) {
                line.write(value);
                if (value == '\n') break;
            }
            if (line.size() == 0) return false;
            byte[] bytes = line.toByteArray();
            boolean complete = !continuation && (bytes.length < 65536 || bytes[bytes.length - 1] == '\n');
            continuation = bytes.length == 65536 && bytes[bytes.length - 1] != '\n';
            if (complete && active.getAsBoolean()
                    && hidden.test(new String(bytes, StandardCharsets.UTF_8).trim())) continue;
            pending = bytes; offset = 0;
        }
        return true;
    }

    @Override public int read() throws IOException { return fill() ? pending[offset++] & 255 : -1; }
    @Override public int read(byte[] b, int off, int len) throws IOException {
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) return 0;
        if (!fill()) return -1;
        int count = Math.min(len, pending.length - offset);
        System.arraycopy(pending, offset, b, off, count); offset += count;
        return count;
    }
    @Override public int available() { return pending.length - offset; }
    @Override public void close() throws IOException { source.close(); }
}
