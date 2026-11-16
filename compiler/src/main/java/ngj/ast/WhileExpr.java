package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record WhileExpr(
    Expr condition,
    BlockExpr body,
    SourceSpan span
) implements Expr {}
