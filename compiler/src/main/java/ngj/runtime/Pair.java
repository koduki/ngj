package ngj.runtime;

/**
 * Immutable deterministic pair value for NGJ runtime operations such as Budget.split.
 */
public record Pair<A, B>(A first, B second) {
    public static <A, B> Pair<A, B> of(A first, B second) {
        return new Pair<>(first, second);
    }
}
