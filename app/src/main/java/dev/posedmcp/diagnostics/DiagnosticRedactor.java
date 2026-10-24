package dev.posedmcp.diagnostics;

/** Applied at export, including secrets logged before the current credentials existed. */
public final class DiagnosticRedactor {
    public static String redact(String value, String... secrets) {
        if (value == null) return "";
        for (String secret : secrets) {
            if (secret != null && !secret.isEmpty()) value = value.replace(secret, "[REDACTED]");
        }
        return value.replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+=*", "$1[REDACTED]")
                .replaceAll("(?i)(<string\\s+name=[\"'](?:mcp_token|bridge_token)[\"']>)[^<]*", "$1[REDACTED]")
                .replaceAll("\\b[0-9a-fA-F]{64}\\b", "[REDACTED]")
                .replaceAll("(?i)((?:mcp_token|bridge_token|authorization)[\\\"'\\s:=]+)[^\\\"'\\s,}<>]+",
                        "$1[REDACTED]");
    }
    private DiagnosticRedactor() {}
}
