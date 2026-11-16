package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ForExpr(
    String varName,
    Expr iterable,
    boolean hasYield,
    BlockExpr body,
    SourceSpan span
) implements Expr {}
