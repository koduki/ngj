package ngj;

import ngj.cli.Main;
import ngj.ast.TypeExpr;

import java.io.PrintStream;
import java.util.List;
import java.util.stream.Collectors;

public record HoleContext(
    String holeId,
    TypeExpr expectedType,
    List<String> allowedEffects,
    List<Binding> bindings,
    List<RelevantSymbol> relevantSymbols
) {
    public record Binding(String name, TypeExpr type, String ownership) {}
    public record RelevantSymbol(String id, String signature, String origin, String contentDigest) {}
    
    public String toJson() {
        var bindingsJson = bindings.stream()
            .map(b -> """
                {"name": "%s", "type": "%s", "ownership": "%s"}""".formatted(
                    escapeJson(b.name()),
                    escapeJson(b.type().toString()),
                    escapeJson(b.ownership())
                ))
            .collect(Collectors.joining(",\n      "));
        
        var symbolsJson = relevantSymbols.stream()
            .map(s -> """
                {
                  "id": "%s",
                  "signature": "%s",
                  "origin": "%s",
                  "content_digest": "%s"
                }""".formatted(
                    escapeJson(s.id()),
                    escapeJson(s.signature()),
                    escapeJson(s.origin()),
                    escapeJson(s.contentDigest())
                ))
            .collect(Collectors.joining(",\n    "));
        
        var effectsJson = allowedEffects.stream()
            .map(e -> "\"" + escapeJson(e) + "\"")
            .collect(Collectors.joining(", "));
        
        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "sha256:current",
              "policy_digest": "sha256:mvp-pure-v1",
              "target_profile": "%s",
              "hole_id": "%s",
              "expected_type": "%s",
              "allowed_effects": [%s],
              "bindings": [
                %s
              ],
              "relevant_symbols": [
                %s
              ],
              "obligations": [],
              "truncated": false,
              "omitted_symbol_count": 0
            }""".formatted(
                Main.SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                Main.TARGET_PROFILE,
                escapeJson(holeId),
                escapeJson(expectedType != null ? expectedType.toString() : "unknown"),
                effectsJson,
                bindingsJson,
                symbolsJson
            );
    }
    
    public void printHuman(PrintStream out) {
        out.println("Hole: " + holeId);
        out.println("Expected type: " + (expectedType != null ? expectedType : "unknown"));
        out.println("Allowed effects: " + (allowedEffects.isEmpty() ? "{}" : allowedEffects));
        out.println("\nBindings in scope:");
        for (var binding : bindings) {
            out.printf("  %s: %s (%s)%n", binding.name(), binding.type(), binding.ownership());
        }
        out.println("\nRelevant symbols:");
        for (var symbol : relevantSymbols) {
            out.printf("  %s%n", symbol.signature());
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
