package ngj;

public enum CheckStatus {
    VALID,            // No errors, no holes
    INVALID,          // Has definite errors
    INCOMPLETE,       // Has code holes (?name) but no definite errors
    INCOMPLETE_PROOF  // Has only proof holes (?proof_name) but no definite errors
}
