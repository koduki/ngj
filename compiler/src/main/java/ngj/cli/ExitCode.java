package ngj.cli;

/**
 * CLI exit codes as specified in AGENT_TOOLING.md
 */
public final class ExitCode {
    public static final int SUCCESS = 0;      // Valid, operation completed
    public static final int INVALID = 1;      // Definite errors present
    public static final int INCOMPLETE = 2;   // Holes/unresolved obligations (no errors)
    public static final int BAD_INPUT = 3;    // Revision mismatch or invalid input
    public static final int INTERNAL_ERROR = 4; // Compiler internal failure
    
    private ExitCode() {}
}
