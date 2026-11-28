package ngj;

import ngj.cli.ExitCode;
import ngj.cli.Main;
import ngj.diagnostic.Diagnostic;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public record BuildResult(
    boolean success,
    List<Diagnostic> diagnostics,
    List<Path> outputFiles,
    String error
) {
    public int exitCode() {
        if (success) {
            return ExitCode.SUCCESS;
        }
        boolean hasHoles = diagnostics.stream()
            .anyMatch(d -> d.id().equals("NGJ-BUILD-001")
                || d.id().equals("NGJ-HOLE-001")
                || d.id().equals("NGJ-PROOF-001"));
        return hasHoles ? ExitCode.INCOMPLETE : ExitCode.INVALID;
    }

    public String toJson(String sourceRevision) {
        return toJson(
            sourceRevision,
            Main.computePolicyDigest("standard", "default", "tier1", "jvm", List.of()),
            Main.TARGET_PROFILE
        );
    }

    public String toJson(String sourceRevision, String policyDigest, String targetProfile) {
        var diagJson = diagnostics.stream()
            .map(Diagnostic::toJson)
            .collect(Collectors.joining(",\n    "));

        var outputJson = outputFiles == null ? "[]" :
            outputFiles.stream()
                .map(p -> "\"" + escapeJson(p.toString()) + "\"")
                .collect(Collectors.joining(", ", "[", "]"));

        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "%s",
              "policy_digest": "%s",
              "target_profile": "%s",
              "success": %s,
              "output_files": %s,
              "diagnostics": [
                %s
              ]
            }""".formatted(
                Main.DIAGNOSTICS_SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                escapeJson(sourceRevision),
                escapeJson(policyDigest),
                escapeJson(targetProfile),
                success,
                outputJson,
                diagJson
            );
    }

    public void printHuman(PrintStream stdout, PrintStream stderr) {
        for (var diag : diagnostics) {
            stderr.println(diag.toHumanString());
        }

        if (success) {
            stdout.println("Build successful.");
            if (outputFiles != null) {
                for (var file : outputFiles) {
                    stdout.println("  Generated: " + file);
                }
            }
        } else {
            stderr.println("Build failed: " + (error != null ? error : "see diagnostics"));
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
