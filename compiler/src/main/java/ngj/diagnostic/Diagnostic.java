package ngj.diagnostic;

import java.util.List;

/**
 * A compiler diagnostic with structured information for machine processing.
 */
public record Diagnostic(
    String id,
    Severity severity,
    Certainty certainty,
    String message,
    SourceSpan primarySpan,
    String symbolId,
    ExpectedActual expected,
    ExpectedActual actual,
    List<RelatedInfo> related,
    List<Suggestion> suggestions
) {
    public static Diagnostic error(String id, String message, SourceSpan span) {
        return new Diagnostic(
            id,
            Severity.ERROR,
            Certainty.DEFINITE,
            message,
            span,
            null,
            null,
            null,
            List.of(),
            List.of()
        );
    }
    
    public static Diagnostic error(String id, String message, SourceSpan span, 
            ExpectedActual expected, ExpectedActual actual) {
        return new Diagnostic(
            id,
            Severity.ERROR,
            Certainty.DEFINITE,
            message,
            span,
            null,
            expected,
            actual,
            List.of(),
            List.of()
        );
    }
    
    public static Diagnostic warning(String id, String message, SourceSpan span) {
        return new Diagnostic(
            id,
            Severity.WARNING,
            Certainty.DEFINITE,
            message,
            span,
            null,
            null,
            null,
            List.of(),
            List.of()
        );
    }
    
    public static Diagnostic incomplete(String id, String message, SourceSpan span) {
        return new Diagnostic(
            id,
            Severity.ERROR,
            Certainty.INCOMPLETE,
            message,
            span,
            null,
            null,
            null,
            List.of(),
            List.of()
        );
    }
    
    public String toJson() {
        var relatedJson = related.isEmpty() ? "" :
            related.stream()
                .map(r -> "{\"kind\": \"%s\", \"symbol_id\": \"%s\"}".formatted(
                    escapeJson(r.kind()), escapeJson(r.symbolId())))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        
        var suggestionsJson = suggestions.isEmpty() ? "" :
            suggestions.stream()
                .map(s -> """
                    {"kind": "%s", "message": "%s", "applicability": "%s"}""".formatted(
                        escapeJson(s.kind()),
                        escapeJson(s.message()),
                        escapeJson(s.applicability())
                    ))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        
        var spanJson = primarySpan == null ? "null" :
            """
            {"file": "%s", "start_byte": %d, "end_byte": %d}""".formatted(
                escapeJson(primarySpan.file()),
                primarySpan.startByte(),
                primarySpan.endByte()
            );
        
        var expectedJson = expected == null ? "null" : expected.toJson();
        var actualJson = actual == null ? "null" : actual.toJson();
        
        return """
            {
              "id": "%s",
              "severity": "%s",
              "certainty": "%s",
              "message": "%s",
              "primary_span": %s,
              "symbol_id": %s,
              "expected": %s,
              "actual": %s,
              "related": [%s],
              "suggestions": [%s]
            }""".formatted(
                escapeJson(id),
                severity.name().toLowerCase(),
                certainty.name().toLowerCase(),
                escapeJson(message),
                spanJson,
                symbolId == null ? "null" : "\"" + escapeJson(symbolId) + "\"",
                expectedJson,
                actualJson,
                relatedJson,
                suggestionsJson
            );
    }
    
    public String toHumanString() {
        var sb = new StringBuilder();
        sb.append(severity.name().toLowerCase()).append("[").append(id).append("]: ");
        sb.append(message);
        if (primarySpan != null) {
            sb.append("\n  --> ").append(primarySpan.file());
            sb.append(":").append(primarySpan.startByte());
        }
        if (expected != null && actual != null) {
            sb.append("\n  expected: ").append(expected);
            sb.append("\n  actual: ").append(actual);
        }
        return sb.toString();
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
