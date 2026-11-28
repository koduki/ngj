package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ProofStmt(
    BlockExpr body,
    SourceSpan span
) implements Statement {}
