package ngj.runtime;

/**
 * Linear-budget runtime representation supporting deterministic conservation on split and spend.
 */
public record Budget(String kind, long remaining) {
    public Budget {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Budget kind must not be blank");
        }
        if (remaining < 0) {
            throw new IllegalArgumentException("Budget remaining must not be negative: " + remaining);
        }
    }

    public Pair<Budget, Budget> split(long share) {
        if (share < 0 || share > remaining) {
            throw new IllegalArgumentException(
                "Invalid budget split share " + share + " for remaining " + remaining
            );
        }
        return new Pair<>(new Budget(kind, share), new Budget(kind, remaining - share));
    }

    public Budget spend(long amount) {
        if (amount < 0 || amount > remaining) {
            throw new IllegalArgumentException(
                "Invalid budget spend amount " + amount + " for remaining " + remaining
            );
        }
        return new Budget(kind, remaining - amount);
    }

    public static Pair<Budget, Budget> split(Budget budget, long share) {
        if (budget == null) {
            throw new IllegalArgumentException("Budget must not be null");
        }
        return budget.split(share);
    }
}
