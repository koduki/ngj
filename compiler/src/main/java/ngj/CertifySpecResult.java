package ngj;

import ngj.cli.ExitCode;
import ngj.cli.Main;
import ngj.diagnostic.Diagnostic;
import ngj.verifier.Post2ExeVerifier.Post2ExeReport;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record CertifySpecResult(
    String symbolId,
    boolean certified,
    boolean hasHoles,
    String surfaceAStatus,
    String surfaceABackend,
    String surfaceADetail,
    boolean surfaceBPassed,
    List<String> surfaceBFindings,
    Post2ExeReport surfaceCReport,
    List<Diagnostic> diagnostics,
    String error
) {
    public static CertifySpecResult badInput(String error) {
        return new CertifySpecResult(null, false, false, "unproven", "jvm", "", false, List.of(), null, List.of(), error);
    }

    public int exitCode() {
        if (error != null) {
            return ExitCode.BAD_INPUT;
        }
        if (hasHoles) {
            return ExitCode.INCOMPLETE;
        }
        return certified ? ExitCode.SUCCESS : ExitCode.INVALID;
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
                  "certified": false,
                  "error": "%s",
                  "diagnostics": []
                }""".formatted(
                    Main.CERTIFY_SCHEMA_VERSION,
                    Main.COMPILER_VERSION,
                    escapeJson(sourceRevision),
                    escapeJson(policyDigest),
                    escapeJson(targetProfile),
                    escapeJson(error)
                );
        }

        String findingsJson = surfaceBFindings.stream()
            .map(f -> "\"" + escapeJson(f) + "\"")
            .collect(Collectors.joining(", "));

        String surfaceCJson = surfaceCReport == null ? "{\"passed\": false}" : mapToJson(surfaceCReport.toMap());

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
              "symbol_id": "%s",
              "certified": %s,
              "surface_a": {
                "status": "%s",
                "backend": "%s",
                "detail": "%s"
              },
              "surface_b": {
                "passed": %s,
                "findings": [%s]
              },
              "surface_c": %s,
              "diagnostics": [
                %s
              ]
            }""".formatted(
                Main.CERTIFY_SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                escapeJson(sourceRevision),
                escapeJson(policyDigest),
                escapeJson(targetProfile),
                escapeJson(symbolId),
                certified,
                escapeJson(surfaceAStatus),
                escapeJson(surfaceABackend),
                escapeJson(surfaceADetail),
                surfaceBPassed,
                findingsJson,
                surfaceCJson,
                diagJson
            );
    }

    public void printHuman(PrintStream out) {
        if (error != null) {
            out.println("Certification error: " + error);
            return;
        }
        out.println("Certify-spec for " + symbolId + ": " + (certified ? "CERTIFIED" : "FAILED"));
        out.println("  Surface A: " + surfaceAStatus + " (" + surfaceABackend + ")");
        out.println("  Surface B: " + (surfaceBPassed ? "passed" : "failed " + surfaceBFindings));
        if (surfaceCReport != null) {
            out.println("  Surface C: " + (surfaceCReport.passed() ? "passed" : "failed")
                + " [" + surfaceCReport.strengthClassification() + "]");
        }
        for (var d : diagnostics) {
            out.println(d.toHumanString());
        }
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append("\"").append(escapeJson(entry.getKey())).append("\": ");
            Object v = entry.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Boolean || v instanceof Number) {
                sb.append(v);
            } else if (v instanceof List<?> list) {
                sb.append(list.stream()
                    .map(item -> "\"" + escapeJson(String.valueOf(item)) + "\"")
                    .collect(Collectors.joining(", ", "[", "]")));
            } else {
                sb.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
            }
        }
        sb.append("}");
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
