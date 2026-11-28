package ngj;

import ngj.cli.ExitCode;
import ngj.cli.Main;
import ngj.diagnostic.Diagnostic;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record PatchCheckResult(
    String status,
    Map<String, Boolean> gates,
    List<Diagnostic> diagnostics,
    int baseUnresolved,
    int patchedUnresolved,
    String error
) {
    public static PatchCheckResult badInput(String error) {
        return new PatchCheckResult("error", Map.of(), List.of(), 0, 0, error);
    }

    public int exitCode() {
        if (error != null) {
            return ExitCode.BAD_INPUT;
        }
        return switch (status) {
            case "passed" -> ExitCode.SUCCESS;
            case "incomplete" -> ExitCode.INCOMPLETE;
            default -> ExitCode.INVALID;
        };
    }

    public String toJson(String sourceRevision, String policyDigest, String targetProfile) {
        if (error != null) {
            return """
                {
                  "schema_version": "%s",
                  "compiler_version": "%s",
                  "source_revision": "%s",
                  "policy_digest": "%s",
                  "target_profile": "%s",
                  "status": "error",
                  "error": "%s",
                  "diagnostics": []
                }""".formatted(
                    Main.PATCH_CHECK_SCHEMA_VERSION,
                    Main.COMPILER_VERSION,
                    escapeJson(sourceRevision),
                    escapeJson(policyDigest),
                    escapeJson(targetProfile),
                    escapeJson(error)
                );
        }

        String gatesJson = gates.entrySet().stream()
            .map(e -> "\"" + escapeJson(e.getKey()) + "\": " + e.getValue())
            .collect(Collectors.joining(", "));

        String diagJson = diagnostics.stream()
            .map(Diagnostic::toJson)
            .collect(Collectors.joining(",\n    "));

        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "%s",
              "policy_digest": "%s",
              "target_profile": "%s",
              "status": "%s",
              "gates": {%s},
              "base_unresolved": %d,
              "patched_unresolved": %d,
              "diagnostics": [
                %s
              ]
            }""".formatted(
                Main.PATCH_CHECK_SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                escapeJson(sourceRevision),
                escapeJson(policyDigest),
                escapeJson(targetProfile),
                escapeJson(status),
                gatesJson,
                baseUnresolved,
                patchedUnresolved,
                diagJson
            );
    }

    public void printHuman(PrintStream out) {
        if (error != null) {
            out.println("Patch check error: " + error);
            return;
        }
        for (var d : diagnostics) {
            out.println(d.toHumanString());
        }
        out.println("Patch check status: " + status + " (gates: " + gates + ")");
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
