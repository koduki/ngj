package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ReturnStmt(
    Expr value,
    SourceSpan span
) implements Statement {}
