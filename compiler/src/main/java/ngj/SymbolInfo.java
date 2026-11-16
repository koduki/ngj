package ngj;

import ngj.ast.TypeExpr;
import ngj.cli.Main;

import java.io.PrintStream;
import java.util.List;
import java.util.stream.Collectors;

public record SymbolInfo(
    String id,
    String kind,
    String name,
    String modulePath,
    TypeExpr type,
    List<String> effects,
    String visibility,
    String signature
) {
    public String toJson() {
        var effectsJson = effects.stream()
            .map(e -> "\"" + escapeJson(e) + "\"")
            .collect(Collectors.joining(", "));
        
        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "symbol_id": "%s",
              "kind": "%s",
              "name": "%s",
              "module": "%s",
              "type": "%s",
              "effects": [%s],
              "visibility": "%s",
              "signature": "%s"
            }""".formatted(
                Main.SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                escapeJson(id),
                escapeJson(kind),
                escapeJson(name),
                escapeJson(modulePath),
                escapeJson(type != null ? type.toString() : ""),
                effectsJson,
                escapeJson(visibility),
                escapeJson(signature)
            );
    }
    
    public void printHuman(PrintStream out) {
        out.println("Symbol: " + id);
        out.println("  Kind: " + kind);
        out.println("  Module: " + modulePath);
        out.println("  Visibility: " + visibility);
        out.println("  Signature: " + signature);
        if (!effects.isEmpty()) {
            out.println("  Effects: " + effects);
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
