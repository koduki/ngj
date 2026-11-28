package ngj.diagnostic;

/**
 * A single step in an effect, type, or proof derivation trace.
 */
public record DerivationStep(
    int step,
    SourceSpan span,
    String exprSnippet,
    String reason
) {
    public String toJson() {
        var spanJson = span == null ? "null" :
            """
            {"file": "%s", "start_byte": %d, "end_byte": %d}""".formatted(
                escapeJson(span.file()),
                span.startByte(),
                span.endByte()
            );
        var snippetJson = exprSnippet == null ? "null" : "\"" + escapeJson(exprSnippet) + "\"";
        var reasonJson = reason == null ? "null" : "\"" + escapeJson(reason) + "\"";
        return """
            {"step": %d, "span": %s, "expr_snippet": %s, "reason": %s}""".formatted(
            step,
            spanJson,
            snippetJson,
            reasonJson
        );
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
