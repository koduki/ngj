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
            .anyMatch(d -> d.id().equals("NGJ-BUILD-001"));
        return hasHoles ? ExitCode.INCOMPLETE : ExitCode.INVALID;
    }
    
    public String toJson(String sourceRevision) {
        var diagJson = diagnostics.stream()
            .map(Diagnostic::toJson)
            .collect(Collectors.joining(",\n    "));
        
        var outputJson = outputFiles == null ? "[]" :
            outputFiles.stream()
                .map(p -> "\"" + p.toString().replace("\\", "\\\\") + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
        
        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "%s",
              "target_profile": "%s",
              "success": %s,
              "output_files": %s,
              "diagnostics": [
                %s
              ]
            }""".formatted(
                Main.SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                sourceRevision,
                Main.TARGET_PROFILE,
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
}
