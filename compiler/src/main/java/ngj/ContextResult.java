package ngj;

import ngj.cli.ExitCode;
import ngj.cli.Main;

import java.io.PrintStream;
import java.util.List;

public record ContextResult(
    HoleContext context,
    String error
) {
    public int exitCode() {
        return error == null ? ExitCode.SUCCESS : ExitCode.BAD_INPUT;
    }

    public String toJson() {
        return toJson(
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            Main.computePolicyDigest("standard", "default", "tier1", "jvm", List.of()),
            Main.TARGET_PROFILE
        );
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
                  "error": "%s"
                }""".formatted(
                    Main.CONTEXT_SCHEMA_VERSION,
                    Main.COMPILER_VERSION,
                    escapeJson(sourceRevision),
                    escapeJson(policyDigest),
                    escapeJson(targetProfile),
                    escapeJson(error)
                );
        }

        return context.toJson(sourceRevision, policyDigest, targetProfile);
    }

    public void printHuman(PrintStream out) {
        if (error != null) {
            out.println("Error: " + error);
        } else {
            context.printHuman(out);
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
