package ngj.diagnostic;

public record Suggestion(
    String kind,
    String message,
    String applicability,
    String replacement
) {
    public static final String MACHINE_APPLICABLE = "machine_applicable";
    public static final String REQUIRES_REVIEW = "requires_review";

    public Suggestion {
        if (applicability == null || applicability.isBlank()) {
            applicability = REQUIRES_REVIEW;
        }
    }

    public Suggestion(String kind, String message, String applicability) {
        this(kind, message, applicability, null);
    }

    public static Suggestion machineApplicable(String kind, String message, String replacement) {
        return new Suggestion(kind, message, MACHINE_APPLICABLE, replacement);
    }

    public static Suggestion requiresReview(String kind, String message, String replacement) {
        return new Suggestion(kind, message, REQUIRES_REVIEW, replacement);
    }
}
