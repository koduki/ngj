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

    private static final String QUOTED_STRING_REGEX = "\"(?:\\\\.|[^\"\\\\])*\"";
    private static final String QUOTED_CHAR_REGEX = "'(?:\\\\.|[^'\\\\])*'";
    private static final String NONSTR_LITERAL_REGEX = "(?:-?\\d+L?|" + QUOTED_CHAR_REGEX + "|true|false)\\b";

    private static final Pattern SECRET_WRAPPER_PATTERN = Pattern.compile(
        "Secret\\((?:" + QUOTED_STRING_REGEX + "|[^)])*\\)"
    );
    private static final Pattern STACK_TRACE_AT_PATTERN = Pattern.compile("(?m)^\\s*at\\s+[\\w.$<>]+\\([^)]*\\)\\r?\\n?");
    private static final Pattern STACK_TRACE_MORE_PATTERN = Pattern.compile("(?m)^\\s*\\.\\.\\.\\s+\\d+\\s+more\\r?\\n?");
    private static final Pattern CAUSED_BY_PATTERN = Pattern.compile("(?m)^Caused by:\\s+.*\\r?\\n?");
    private static final Pattern SECRET_TYPED_INIT_PATTERN = Pattern.compile(
        "(Secret\\s*(?:<[^>]+>)?\\s*=\\s*)" + QUOTED_STRING_REGEX
    );
    private static final Pattern SECRET_TYPED_NONSTR_INIT_PATTERN = Pattern.compile(
        "(Secret\\s*(?:<[^>]+>)?\\s*=\\s*)(" + NONSTR_LITERAL_REGEX + ")"
    );
    private static final Pattern SECRET_OF_LITERAL_PATTERN = Pattern.compile(
        "(Secret\\.of\\s*\\(\\s*)" + QUOTED_STRING_REGEX + "(\\s*\\))"
    );
    private static final Pattern SECRET_OF_NONSTR_LITERAL_PATTERN = Pattern.compile(
        "(Secret\\.of\\s*\\(\\s*)(" + NONSTR_LITERAL_REGEX + ")(\\s*\\))"
    );
    private static final Pattern ENV_SECRET_QUOTED_ASSIGN_PATTERN = Pattern.compile(
        "(?i)(\\b[a-z_][a-z0-9_]*(?:secret|token|password|passwd|api_key|apikey|private_key|credential|auth)[a-z0-9_]*(?:\\s*:\\s*[^=;\\n]+)?\\s*(?:=|:)\\s*)" + QUOTED_STRING_REGEX
    );
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
        return redactText(input, Set.of(), Set.of(), Set.of());
    }

    public static String redactText(
        String input,
        Set<String> secretKeys,
        Set<String> secretTypeNames,
        Set<String> secretLiterals
    ) {
        if (input == null) {
            return null;
        }
        String result = SECRET_WRAPPER_PATTERN.matcher(input).replaceAll(MASK);
        result = SECRET_TYPED_INIT_PATTERN.matcher(result).replaceAll("$1\"" + MASK + "\"");
        result = SECRET_TYPED_NONSTR_INIT_PATTERN.matcher(result).replaceAll("$1" + MASK);
        result = SECRET_OF_LITERAL_PATTERN.matcher(result).replaceAll("$1\"" + MASK + "\"$2");
        result = SECRET_OF_NONSTR_LITERAL_PATTERN.matcher(result).replaceAll("$1" + MASK + "$2");
        if (secretTypeNames != null && !secretTypeNames.isEmpty()) {
            for (String typeName : secretTypeNames) {
                if (typeName == null || typeName.isBlank()) continue;
                String quoted = Pattern.quote(typeName);
                result = Pattern.compile("(\\b" + quoted + "(?:\\s*<[^>]*>)?\\s*=\\s*)" + QUOTED_STRING_REGEX)
                    .matcher(result)
                    .replaceAll("$1\"" + MASK + "\"");
                result = Pattern.compile("(\\b" + quoted + "(?:\\s*<[^>]*>)?\\s*=\\s*)(" + NONSTR_LITERAL_REGEX + ")")
                    .matcher(result)
                    .replaceAll("$1" + MASK);
            }
        }
        if (secretKeys != null && !secretKeys.isEmpty()) {
            for (String key : secretKeys) {
                if (key == null || key.isBlank()) continue;
                String quoted = Pattern.quote(key);
                result = Pattern.compile("(\\b" + quoted + "(?:\\s*:\\s*[^=;\\n]+)?\\s*(?:=|:)\\s*)" + QUOTED_STRING_REGEX)
                    .matcher(result)
                    .replaceAll("$1\"" + MASK + "\"");
                result = Pattern.compile("(\\b" + quoted + "(?:\\s*:\\s*[^=;\\n]+)?\\s*(?:=|:)\\s*)(" + NONSTR_LITERAL_REGEX + ")")
                    .matcher(result)
                    .replaceAll("$1" + MASK);
            }
        }
        if (secretLiterals != null && !secretLiterals.isEmpty()) {
            for (String lit : secretLiterals) {
                if (lit == null || lit.isEmpty()) continue;
                result = result.replace("\"" + lit + "\"", "\"" + MASK + "\"");
                if (lit.length() >= 6) {
                    result = result.replace(lit, MASK);
                }
            }
        }
        result = ENV_SECRET_QUOTED_ASSIGN_PATTERN.matcher(result).replaceAll("$1\"" + MASK + "\"");
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
        return redactDiagnostic(diagnostic, Set.of(), Set.of(), Set.of());
    }

    public static Diagnostic redactDiagnostic(Diagnostic diagnostic, Set<String> secretKeys) {
        return redactDiagnostic(diagnostic, secretKeys, Set.of(), Set.of());
    }

    public static Diagnostic redactDiagnostic(
        Diagnostic diagnostic,
        Set<String> secretKeys,
        Set<String> secretTypeNames,
        Set<String> secretLiterals
    ) {
        if (diagnostic == null) {
            return null;
        }
        List<DerivationStep> redactedTrace = diagnostic.derivationTrace().stream()
            .map(step -> new DerivationStep(
                step.step(),
                step.span(),
                redactText(step.exprSnippet(), secretKeys, secretTypeNames, secretLiterals),
                redactText(step.reason(), secretKeys, secretTypeNames, secretLiterals)
            ))
            .toList();
        List<Suggestion> redactedSuggestions = diagnostic.suggestions().stream()
            .map(s -> new Suggestion(
                s.kind(),
                redactText(s.message(), secretKeys, secretTypeNames, secretLiterals),
                s.applicability(),
                redactText(s.replacement(), secretKeys, secretTypeNames, secretLiterals)
            ))
            .toList();
        return new Diagnostic(
            diagnostic.id(),
            diagnostic.severity(),
            diagnostic.certainty(),
            redactText(diagnostic.message(), secretKeys, secretTypeNames, secretLiterals),
            diagnostic.primarySpan(),
            diagnostic.symbolId(),
            diagnostic.expected(),
            diagnostic.actual(),
            diagnostic.related(),
            redactedSuggestions,
            redactText(diagnostic.exprSnippet(), secretKeys, secretTypeNames, secretLiterals),
            redactedTrace,
            diagnostic.obligationId(),
            redactSolverDiagnosis(diagnostic.solverDiagnosis(), secretKeys)
        );
    }
}
