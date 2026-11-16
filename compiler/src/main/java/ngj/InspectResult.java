package ngj;

import ngj.cli.ExitCode;
import ngj.cli.Main;

import java.io.PrintStream;

public record InspectResult(
    SymbolInfo symbol,
    String error
) {
    public InspectResult(SymbolInfo symbol) {
        this(symbol, null);
    }
    
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
        
        return symbol.toJson();
    }
    
    public void printHuman(PrintStream out) {
        if (error != null) {
            out.println("Error: " + error);
        } else {
            symbol.printHuman(out);
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
