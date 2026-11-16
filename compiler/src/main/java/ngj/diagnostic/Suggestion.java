package ngj.diagnostic;

public record Suggestion(
    String kind,
    String message,
    String applicability,
    String replacement
) {
    public Suggestion(String kind, String message, String applicability) {
        this(kind, message, applicability, null);
    }
}
