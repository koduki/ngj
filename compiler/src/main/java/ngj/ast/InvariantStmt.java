package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record InvariantStmt(
    Expr condition,
    SourceSpan span
) implements Statement {}
