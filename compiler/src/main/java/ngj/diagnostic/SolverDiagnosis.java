package ngj.diagnostic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Structured solver diagnosis for contract, refinement, or proof failures.
 */
public record SolverDiagnosis(
    String failureClass,
    SourceSpan suggestedHintAnchor,
    Map<String, String> counterexampleState
) {
    public static final Set<String> ALLOWED_FAILURE_CLASSES = Set.of(
        "counterexample_found",
        "loop_invariant_missing",
        "quantifier_instantiation_gap",
        "axiomatization_gap",
        "timeout"
    );

    public SolverDiagnosis {
        if (failureClass == null || !ALLOWED_FAILURE_CLASSES.contains(failureClass)) {
            throw new IllegalArgumentException(
                "Invalid solver failureClass: " + failureClass + ", expected one of " + ALLOWED_FAILURE_CLASSES
            );
        }
        counterexampleState = counterexampleState == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(counterexampleState));
    }

    public String toJson() {
        var anchorJson = suggestedHintAnchor == null ? "null" :
            """
            {"file": "%s", "start_byte": %d, "end_byte": %d}""".formatted(
                escapeJson(suggestedHintAnchor.file()),
                suggestedHintAnchor.startByte(),
                suggestedHintAnchor.endByte()
            );
        var stateJson = counterexampleState.isEmpty() ? "{}" :
            "{" + counterexampleState.entrySet().stream()
                .map(e -> "\"%s\": \"%s\"".formatted(escapeJson(e.getKey()), escapeJson(e.getValue())))
                .collect(Collectors.joining(", ")) + "}";
        return """
            {"failure_class": "%s", "suggested_hint_anchor": %s, "counterexample_state": %s}""".formatted(
            escapeJson(failureClass),
            anchorJson,
            stateJson
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
