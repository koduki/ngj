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
            case INCOMPLETE -> ExitCode.INCOMPLETE;
        };
    }
    
    public String toJson(String sourceRevision) {
        var diagJson = diagnostics.stream()
            .map(Diagnostic::toJson)
            .collect(Collectors.joining(",\n    "));
        
        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "%s",
              "policy_digest": "sha256:mvp-pure-v1",
              "target_profile": "%s",
              "status": "%s",
              "diagnostics": [
                %s
              ]
            }""".formatted(
                Main.SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                sourceRevision,
                Main.TARGET_PROFILE,
                status.name().toLowerCase(),
                diagJson
            );
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
}
