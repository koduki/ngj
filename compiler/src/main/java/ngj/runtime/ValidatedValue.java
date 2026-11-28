package ngj.runtime;

/**
 * Wrapper marking values that have passed boundary/refinement validation.
 */
public record ValidatedValue<T>(T value) {
    public static <T> ValidatedValue<T> of(T value) {
        return new ValidatedValue<>(value);
    }

    public T get() {
        return value;
    }
}
