package ngj.runtime;

/**
 * Structured boundary validation error.
 */
public record ValidationError(String code, String message) {
    public ValidationError {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("ValidationError code must not be blank");
        }
        if (message == null) {
            message = "";
        }
    }
}
