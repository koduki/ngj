package ngj.runtime;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Wrapper marking values arriving from untrusted boundaries until explicitly validated.
 */
public record UntrustedValue<T>(T raw) {
    public static <T> UntrustedValue<T> of(T raw) {
        return new UntrustedValue<>(raw);
    }

    public ValidatedValue<T> validate(Predicate<? super T> predicate, String errorMessage) {
        Objects.requireNonNull(predicate, "predicate");
        if (!predicate.test(raw)) {
            throw new IllegalArgumentException(
                errorMessage == null ? "Untrusted value failed validation" : errorMessage
            );
        }
        return new ValidatedValue<>(raw);
    }

    public <R> ValidatedValue<R> sanitize(Function<? super T, ? extends R> sanitizer) {
        Objects.requireNonNull(sanitizer, "sanitizer");
        return new ValidatedValue<>(sanitizer.apply(raw));
    }
}
