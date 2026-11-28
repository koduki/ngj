package ngj.diagnostic;

import java.util.List;
import java.util.stream.Collectors;

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
    List<Suggestion> suggestions,
    String exprSnippet,
    List<DerivationStep> derivationTrace,
    String obligationId,
    SolverDiagnosis solverDiagnosis
) {
    public Diagnostic {
        related = related == null ? List.of() : List.copyOf(related);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        derivationTrace = derivationTrace == null ? List.of() : List.copyOf(derivationTrace);
    }

    /**
     * Backward-compatible 10-argument constructor.
     */
    public Diagnostic(
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
        this(
            id,
            severity,
            certainty,
            message,
            primarySpan,
            symbolId,
            expected,
            actual,
            related,
            suggestions,
            null,
            List.of(),
            null,
            null
        );
    }

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

    public static Diagnostic incompleteProof(String id, String message, SourceSpan span) {
        return new Diagnostic(
            id,
            Severity.ERROR,
            Certainty.INCOMPLETE_PROOF,
            message,
            span,
            null,
            null,
            null,
            List.of(),
            List.of()
        );
    }

    /**
     * ENUM-02: Create exhaustiveness error with missing variant details for AI fix loop.
     * The related info contains the missing variants for structured diagnostic output.
     */
    public static Diagnostic exhaustivenessError(String id, String message, SourceSpan span,
            String enumTypeName, List<String> missingVariants) {
        var related = missingVariants.stream()
            .map(v -> new RelatedInfo("missing_variant", enumTypeName + "." + v))
            .toList();

        return new Diagnostic(
            id,
            Severity.ERROR,
            Certainty.DEFINITE,
            message,
            span,
            enumTypeName,
            null,
            null,
            related,
            List.of(new Suggestion(
                "add_missing_cases",
                "Add match arms for: " + String.join(", ", missingVariants),
                Suggestion.MACHINE_APPLICABLE
            ))
        );
    }

    public Diagnostic withExprSnippet(String exprSnippet) {
        return new Diagnostic(
            id, severity, certainty, message, primarySpan, symbolId,
            expected, actual, related, suggestions,
            exprSnippet, derivationTrace, obligationId, solverDiagnosis
        );
    }

    public Diagnostic withDerivationTrace(List<DerivationStep> derivationTrace) {
        return new Diagnostic(
            id, severity, certainty, message, primarySpan, symbolId,
            expected, actual, related, suggestions,
            exprSnippet, derivationTrace, obligationId, solverDiagnosis
        );
    }

    public Diagnostic withObligationId(String obligationId) {
        return new Diagnostic(
            id, severity, certainty, message, primarySpan, symbolId,
            expected, actual, related, suggestions,
            exprSnippet, derivationTrace, obligationId, solverDiagnosis
        );
    }

    public Diagnostic withSolverDiagnosis(SolverDiagnosis solverDiagnosis) {
        return new Diagnostic(
            id, severity, certainty, message, primarySpan, symbolId,
            expected, actual, related, suggestions,
            exprSnippet, derivationTrace, obligationId, solverDiagnosis
        );
    }

    public Diagnostic withSuggestions(List<Suggestion> suggestions) {
        return new Diagnostic(
            id, severity, certainty, message, primarySpan, symbolId,
            expected, actual, related, suggestions,
            exprSnippet, derivationTrace, obligationId, solverDiagnosis
        );
    }

    public Diagnostic withCertainty(Certainty certainty) {
        return new Diagnostic(
            id, severity, certainty, message, primarySpan, symbolId,
            expected, actual, related, suggestions,
            exprSnippet, derivationTrace, obligationId, solverDiagnosis
        );
    }

    public String toJson() {
        var relatedJson = related.isEmpty() ? "" :
            related.stream()
                .map(r -> "{\"kind\": \"%s\", \"symbol_id\": \"%s\"}".formatted(
                    escapeJson(r.kind()), escapeJson(r.symbolId())))
                .collect(Collectors.joining(", "));

        var suggestionsJson = suggestions.isEmpty() ? "" :
            suggestions.stream()
                .map(s -> s.replacement() == null
                    ? """
                        {"kind": "%s", "message": "%s", "applicability": "%s"}""".formatted(
                            escapeJson(s.kind()),
                            escapeJson(s.message()),
                            escapeJson(s.applicability())
                        )
                    : """
                        {"kind": "%s", "message": "%s", "applicability": "%s", "replacement": "%s"}""".formatted(
                            escapeJson(s.kind()),
                            escapeJson(s.message()),
                            escapeJson(s.applicability()),
                            escapeJson(s.replacement())
                        ))
                .collect(Collectors.joining(", "));

        var spanJson = primarySpan == null ? "null" :
            """
            {"file": "%s", "start_byte": %d, "end_byte": %d}""".formatted(
                escapeJson(primarySpan.file()),
                primarySpan.startByte(),
                primarySpan.endByte()
            );

        var expectedJson = expected == null ? "null" : expected.toJson();
        var actualJson = actual == null ? "null" : actual.toJson();
        var exprSnippetJson = exprSnippet == null ? "null" : "\"" + escapeJson(exprSnippet) + "\"";
        var traceJson = derivationTrace.isEmpty() ? "" :
            derivationTrace.stream()
                .map(DerivationStep::toJson)
                .collect(Collectors.joining(", "));
        var obligationIdJson = obligationId == null ? "null" : "\"" + escapeJson(obligationId) + "\"";
        var solverDiagnosisJson = solverDiagnosis == null ? "null" : solverDiagnosis.toJson();

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
              "suggestions": [%s],
              "expr_snippet": %s,
              "derivation_trace": [%s],
              "obligation_id": %s,
              "solver_diagnosis": %s
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
                suggestionsJson,
                exprSnippetJson,
                traceJson,
                obligationIdJson,
                solverDiagnosisJson
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
