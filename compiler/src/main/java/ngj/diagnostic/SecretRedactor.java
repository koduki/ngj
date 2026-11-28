package ngj.diagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic redactor masking Secret values/variables/messages as "Secret(****)"
 * and stripping stack traces and environment variable leaks from diagnostics.
 */
public final class SecretRedactor {
    public static final String MASK = "Secret(****)";

    private static final Pattern SECRET_WRAPPER_PATTERN = Pattern.compile("Secret\\([^)]*\\)");
    private static final Pattern STACK_TRACE_AT_PATTERN = Pattern.compile("(?m)^\\s*at\\s+[\\w.$<>]+\\([^)]*\\)\\r?\\n?");
    private static final Pattern STACK_TRACE_MORE_PATTERN = Pattern.compile("(?m)^\\s*\\.\\.\\.\\s+\\d+\\s+more\\r?\\n?");
    private static final Pattern CAUSED_BY_PATTERN = Pattern.compile("(?m)^Caused by:\\s+.*\\r?\\n?");
    private static final Pattern ENV_SECRET_ASSIGN_PATTERN = Pattern.compile(
        "(?i)\\b([a-z_][a-z0-9_]*(?:secret|token|password|passwd|api_key|apikey|private_key|credential|auth)[a-z0-9_]*)\\s*(=|:)\\s*([^\\s,;\"'}]+)"
    );

    private SecretRedactor() {}

    public static String maskSecret(Object ignoredValue) {
        return MASK;
    }

    public static boolean isSecretVariableName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("secret")
            || lower.contains("password")
            || lower.contains("passwd")
            || lower.contains("token")
            || lower.contains("api_key")
            || lower.contains("apikey")
            || lower.contains("private_key")
            || lower.contains("credential");
    }

    public static String redactText(String input) {
        if (input == null) {
            return null;
        }
        String result = SECRET_WRAPPER_PATTERN.matcher(input).replaceAll(MASK);
        result = ENV_SECRET_ASSIGN_PATTERN.matcher(result).replaceAll("$1$2" + MASK);
        result = STACK_TRACE_AT_PATTERN.matcher(result).replaceAll("");
        result = STACK_TRACE_MORE_PATTERN.matcher(result).replaceAll("");
        result = CAUSED_BY_PATTERN.matcher(result).replaceAll("");
        return result.stripTrailing();
    }

    public static Map<String, String> redactState(Map<String, String> state, Set<String> secretKeys) {
        if (state == null || state.isEmpty()) {
            return Map.of();
        }
        Map<String, String> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : state.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            boolean isSecretKey = (secretKeys != null && secretKeys.contains(key)) || isSecretVariableName(key);
            if (isSecretKey) {
                redacted.put(key, MASK);
            } else {
                redacted.put(key, redactText(val));
            }
        }
        return Map.copyOf(redacted);
    }

    public static SolverDiagnosis redactSolverDiagnosis(SolverDiagnosis diagnosis, Set<String> secretKeys) {
        if (diagnosis == null) {
            return null;
        }
        return new SolverDiagnosis(
            diagnosis.failureClass(),
            diagnosis.suggestedHintAnchor(),
            redactState(diagnosis.counterexampleState(), secretKeys)
        );
    }

    public static Diagnostic redactDiagnostic(Diagnostic diagnostic) {
        return redactDiagnostic(diagnostic, Set.of());
    }

    public static Diagnostic redactDiagnostic(Diagnostic diagnostic, Set<String> secretKeys) {
        if (diagnostic == null) {
            return null;
        }
        List<DerivationStep> redactedTrace = diagnostic.derivationTrace().stream()
            .map(step -> new DerivationStep(
                step.step(),
                step.span(),
                redactText(step.exprSnippet()),
                redactText(step.reason())
            ))
            .toList();
        List<Suggestion> redactedSuggestions = diagnostic.suggestions().stream()
            .map(s -> new Suggestion(
                s.kind(),
                redactText(s.message()),
                s.applicability(),
                redactText(s.replacement())
            ))
            .toList();
        return new Diagnostic(
            diagnostic.id(),
            diagnostic.severity(),
            diagnostic.certainty(),
            redactText(diagnostic.message()),
            diagnostic.primarySpan(),
            diagnostic.symbolId(),
            diagnostic.expected(),
            diagnostic.actual(),
            diagnostic.related(),
            redactedSuggestions,
            redactText(diagnostic.exprSnippet()),
            redactedTrace,
            diagnostic.obligationId(),
            redactSolverDiagnosis(diagnostic.solverDiagnosis(), secretKeys)
        );
    }
}
