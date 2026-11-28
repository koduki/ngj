package ngj.diagnostic;

public enum Certainty {
    DEFINITE,         // Confirmed error
    INCOMPLETE,       // Could be resolved by filling holes
    INCOMPLETE_PROOF  // Could be resolved by filling proof holes or lemmas
}
