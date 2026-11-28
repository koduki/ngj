package ngj;

import ngj.cli.ExitCode;
import ngj.cli.Main;
import ngj.diagnostic.SourceSpan;

import java.io.PrintStream;
import java.util.List;
import java.util.stream.Collectors;

public record ObligationsResult(
    List<Obligation> obligations
) {
    public record Obligation(
        String id,
        String kind,
        String symbolId,
        String status,
        String description,
        SourceSpan span
    ) {
        public String toJson() {
            String spanJson = span == null ? "null" :
                """
                {"file": "%s", "start_byte": %d, "end_byte": %d}""".formatted(
                    escapeJson(span.file()),
                    span.startByte(),
                    span.endByte()
                );
            return """
                {
                  "id": "%s",
                  "kind": "%s",
                  "symbol_id": "%s",
                  "status": "%s",
                  "description": "%s",
                  "span": %s
                }""".formatted(
                    escapeJson(id),
                    escapeJson(kind),
                    escapeJson(symbolId),
                    escapeJson(status),
                    escapeJson(description),
                    spanJson
                );
        }
    }

    public int exitCode() {
        return ExitCode.SUCCESS;
    }

    public long resolvedCount() {
        return obligations.stream().filter(o -> "resolved".equals(o.status())).count();
    }

    public long unresolvedCount() {
        return obligations.stream().filter(o -> "unresolved".equals(o.status())).count();
    }

    public long failedCount() {
        return obligations.stream().filter(o -> "failed".equals(o.status())).count();
    }

    public String toJson(String sourceRevision, String policyDigest, String targetProfile) {
        String oblJson = obligations.stream()
            .map(Obligation::toJson)
            .collect(Collectors.joining(",\n    "));

        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "%s",
              "policy_digest": "%s",
              "target_profile": "%s",
              "summary": {
                "total": %d,
                "resolved": %d,
                "unresolved": %d,
                "failed": %d
              },
              "obligations": [
                %s
              ]
            }""".formatted(
                Main.OBLIGATIONS_SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                escapeJson(sourceRevision),
                escapeJson(policyDigest),
                escapeJson(targetProfile),
                obligations.size(),
                resolvedCount(),
                unresolvedCount(),
                failedCount(),
                oblJson
            );
    }

    public void printHuman(PrintStream out) {
        out.printf("Obligations: %d total (%d resolved, %d unresolved, %d failed)%n",
            obligations.size(), resolvedCount(), unresolvedCount(), failedCount());
        for (var ob : obligations) {
            out.printf("  [%s] %s (%s): %s%n", ob.status(), ob.kind(), ob.symbolId(), ob.description());
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
