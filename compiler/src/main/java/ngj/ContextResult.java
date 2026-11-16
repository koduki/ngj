package ngj;

import ngj.cli.ExitCode;
import ngj.cli.Main;

import java.io.PrintStream;

public record ContextResult(
    HoleContext context,
    String error
) {
    public int exitCode() {
        return error == null ? ExitCode.SUCCESS : ExitCode.BAD_INPUT;
    }
    
    public String toJson() {
        if (error != null) {
            return """
                {
                  "schema_version": "%s",
                  "compiler_version": "%s",
                  "error": "%s"
                }""".formatted(Main.SCHEMA_VERSION, Main.COMPILER_VERSION, escapeJson(error));
        }
        
        return context.toJson();
    }
    
    public void printHuman(PrintStream out) {
        if (error != null) {
            out.println("Error: " + error);
        } else {
            context.printHuman(out);
        }
    }
    
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
