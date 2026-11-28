package ngj;

import ngj.ast.TypeExpr;
import ngj.cli.Main;

import java.io.PrintStream;
import java.util.List;
import java.util.stream.Collectors;

public record HoleContext(
    String holeId,
    TypeExpr expectedType,
    List<String> allowedEffects,
    List<Binding> bindings,
    List<RelevantSymbol> relevantSymbols,
    List<ContextObligation> obligations,
    boolean truncated,
    int omittedSymbolCount
) {
    public record Binding(String name, TypeExpr type, String ownership) {}
    public record RelevantSymbol(String id, String signature, String origin, String contentDigest) {}
    public record ContextObligation(String kind, String description) {}

    public HoleContext(
        String holeId,
        TypeExpr expectedType,
        List<String> allowedEffects,
        List<Binding> bindings,
        List<RelevantSymbol> relevantSymbols
    ) {
        this(holeId, expectedType, allowedEffects, bindings, relevantSymbols, List.of(), false, 0);
    }

    public String toJson() {
        return toJson(
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            Main.computePolicyDigest("standard", "default", "tier1", "jvm", List.of()),
            Main.TARGET_PROFILE
        );
    }

    public String toJson(String sourceRevision, String policyDigest, String targetProfile) {
        var bindingsJson = bindings.stream()
            .map(b -> """
                {"name": "%s", "type": "%s", "capability_mode": "%s"}""".formatted(
                    escapeJson(b.name()),
                    escapeJson(b.type() != null ? b.type().toString() : "unknown"),
                    escapeJson(b.ownership() != null ? b.ownership() : "owned")
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

        var obligationsJson = obligations.stream()
            .map(o -> """
                {"kind": "%s", "description": "%s"}""".formatted(
                    escapeJson(o.kind()),
                    escapeJson(o.description())
                ))
            .collect(Collectors.joining(",\n    "));

        return """
            {
              "schema_version": "%s",
              "compiler_version": "%s",
              "source_revision": "%s",
              "policy_digest": "%s",
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
              "obligations": [
                %s
              ],
              "truncated": %s,
              "omitted_symbol_count": %d
            }""".formatted(
                Main.CONTEXT_SCHEMA_VERSION,
                Main.COMPILER_VERSION,
                escapeJson(sourceRevision),
                escapeJson(policyDigest),
                escapeJson(targetProfile),
                escapeJson(holeId),
                escapeJson(expectedType != null ? expectedType.toString() : "unknown"),
                effectsJson,
                bindingsJson,
                symbolsJson,
                obligationsJson,
                truncated,
                omittedSymbolCount
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
        if (!obligations.isEmpty()) {
            out.println("\nObligations:");
            for (var ob : obligations) {
                out.printf("  [%s] %s%n", ob.kind(), ob.description());
            }
        }
        if (truncated) {
            out.printf("%n(Truncated: %d symbol(s) omitted)%n", omittedSymbolCount);
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
