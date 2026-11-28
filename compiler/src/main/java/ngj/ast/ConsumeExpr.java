package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ConsumeExpr(
    Expr target,
    SourceSpan span
) implements Expr {}
