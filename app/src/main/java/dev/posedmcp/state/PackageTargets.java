package dev.posedmcp.state;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;

/** Exact, explicit targets for experimental scope policies; an empty list selects no app. */
public final class PackageTargets {
    public static String[] parse(String text) {
        Set<String> packages = new LinkedHashSet<>();
        for (String name : text.trim().split("[\\s,]+")) {
            if (name.isEmpty()) continue;
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+") || name.length() > 255) {
                throw new IllegalArgumentException("Invalid package name: " + name);
            }
            packages.add(name);
            if (packages.size() > 64) throw new IllegalArgumentException("At most 64 package names");
        }
        return packages.toArray(new String[0]);
    }
    public static boolean includes(JSONArray names, String target) {
        if (names != null && target != null) {
            for (int i = 0; i < names.length(); i++) if (target.equals(names.optString(i))) return true;
        }
        return false;
    }
    private PackageTargets() {}
}
