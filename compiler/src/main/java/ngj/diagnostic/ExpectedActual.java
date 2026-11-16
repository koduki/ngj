package ngj.diagnostic;

import java.util.List;
import java.util.stream.Collectors;

public record ExpectedActual(
    String type,
    List<String> effects
) {
    public ExpectedActual(String type) {
        this(type, List.of());
    }
    
    public String toJson() {
        if (effects.isEmpty()) {
            return """
                {"type": "%s"}""".formatted(escapeJson(type));
        }
        var effectsJson = effects.stream()
            .map(e -> "\"" + escapeJson(e) + "\"")
            .collect(Collectors.joining(", "));
        return """
            {"type": "%s", "effects": [%s]}""".formatted(escapeJson(type), effectsJson);
    }
    
    @Override
    public String toString() {
        if (effects.isEmpty()) {
            return type;
        }
        return type + " ! {" + String.join(", ", effects) + "}";
    }
    
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
