package ngj;

public enum CheckStatus {
    VALID,      // No errors, no holes
    INVALID,    // Has errors
    INCOMPLETE  // Has holes but no errors
}
