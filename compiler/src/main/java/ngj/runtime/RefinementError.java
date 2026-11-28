package ngj.runtime;

/**
 * Runtime error variants for refinement type validation failures.
 */
public sealed interface RefinementError permits RefinementError.PredicateFailed, RefinementError.OutOfRange {
    String message();

    record PredicateFailed(String predicate, String message) implements RefinementError {
        public PredicateFailed {
            if (predicate == null) {
                predicate = "predicate";
            }
            if (message == null) {
                message = "Refinement predicate failed: " + predicate;
            }
        }

        public PredicateFailed(String message) {
            this("predicate", message);
        }
    }

    record OutOfRange(long value, long min, long max, String message) implements RefinementError {
        public OutOfRange {
            if (message == null) {
                message = "Value " + value + " out of range [" + min + ".." + max + "]";
            }
        }

        public OutOfRange(long value, long min, long max) {
            this(value, min, max, "Value " + value + " out of range [" + min + ".." + max + "]");
        }
    }
}
