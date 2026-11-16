package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ExprStmt(
    Expr expr,
    SourceSpan span
) implements Statement {}
