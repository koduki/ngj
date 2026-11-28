package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ForkExpr(
    BlockExpr body,
    SourceSpan span
) implements Expr {}
