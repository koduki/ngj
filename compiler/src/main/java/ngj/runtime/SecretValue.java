package ngj.runtime;

import java.util.Objects;
import java.util.function.Function;

/**
 * Non-leaky wrapper for secret values. {@link #toString()} always returns "Secret(****)".
 */
public final class SecretValue<T> {
    public static final String MASK = "Secret(****)";

    private final T value;

    private SecretValue(T value) {
        this.value = value;
    }

    public static <T> SecretValue<T> of(T value) {
        return new SecretValue<>(value);
    }

    public T expose() {
        return value;
    }

    public T value() {
        return value;
    }

    public <R> SecretValue<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new SecretValue<>(mapper.apply(value));
    }

    @Override
    public String toString() {
        return MASK;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SecretValue<?> that)) return false;
        return Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
