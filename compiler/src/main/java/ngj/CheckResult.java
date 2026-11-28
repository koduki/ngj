package ngj;

import ngj.ast.CompilationUnit;
import ngj.cli.ExitCode;
import ngj.cli.Main;
import ngj.diagnostic.Diagnostic;
import ngj.diagnostic.Severity;

import java.io.PrintStream;
import java.util.List;
import java.util.stream.Collectors;

public record CheckResult(
    CheckStatus status,
    List<Diagnostic> diagnostics,
    List<CompilationUnit> units
) {
    public int exitCode() {
        return switch (status) {
            case VALID -> ExitCode.SUCCESS;
            case INVALID -> ExitCode.INVALID;
            case INCOMPLETE, INCOMPLETE_PROOF -> ExitCode.INCOMPLETE;
        };
    }

    public String toJson(String sourceRevision) {
        return toJson(sourceRevision, Main.computePolicyDigest("standard", "default", "tier1", "jvm", List.of()), Main.TARGET_PROFILE, "human");
    }

    public String toJson(String sourceRevision, String policyDigest, String targetProfile, String diagnosticDetail) {
        String detail = "agent".equals(diagnosticDetail) ? "agent" : "human";
        var diagJson = diagnostics.stream()
            .map(d -> renderDiagnostic(d, detail))
            .map(Diagnostic::toJson)
            .collect(Collectors.joining(",\n    "));

        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "%s",
              "policy_digest": "%s",
              "target_profile": "%s",
              "diagnostic_detail": "%s",
              "status": "%s",
              "diagnostics": [
                %s
              ]
            }""".formatted(
                Main.DIAGNOSTICS_SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                escapeJson(sourceRevision),
                escapeJson(policyDigest),
                escapeJson(targetProfile),
                detail,
                status.name().toLowerCase(),
                diagJson
            );
    }

    private static Diagnostic renderDiagnostic(Diagnostic d, String detail) {
        if ("human".equals(detail)) {
            return new Diagnostic(
                d.id(),
                d.severity(),
                d.certainty(),
                d.message(),
                d.primarySpan(),
                d.symbolId(),
                d.expected(),
                d.actual(),
                d.related(),
                d.suggestions(),
                null,
                List.of(),
                null,
                null
            );
        }
        return d;
    }

    public void printHuman(PrintStream out) {
        for (var diag : diagnostics) {
            out.println(diag.toHumanString());
        }

        long errorCount = diagnostics.stream()
            .filter(d -> d.severity() == Severity.ERROR)
            .count();
        long warningCount = diagnostics.stream()
            .filter(d -> d.severity() == Severity.WARNING)
            .count();

        if (errorCount > 0 || warningCount > 0) {
            out.printf("%nCheck result: %s (%d error(s), %d warning(s))%n",
                status.name().toLowerCase(), errorCount, warningCount);
        } else {
            out.println("Check result: " + status.name().toLowerCase());
        }
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
