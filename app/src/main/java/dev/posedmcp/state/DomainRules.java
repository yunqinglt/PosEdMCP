package dev.posedmcp.state;

import java.net.IDN;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Exact DNS names or explicit *.suffix rules; never substring matching. */
public final class DomainRules {
    public static String[] parse(String source) {
        Set<String> rules = new LinkedHashSet<>();
        for (String line : source.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            boolean wildcard = value.startsWith("*.");
            if (wildcard) value = value.substring(2);
            if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
            try { value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid domain: " + line); }
            if (value.length() > 253 || !value.contains(".") || value.contains("*")
                    || value.matches("[0-9.]+") || value.contains(":")) {
                throw new IllegalArgumentException("Invalid domain: " + line);
            }
            rules.add((wildcard ? "*." : "") + value);
            if (rules.size() > 256) throw new IllegalArgumentException("At most 256 domain rules");
        }
        return rules.toArray(new String[0]);
    }

    private DomainRules() {}
}
