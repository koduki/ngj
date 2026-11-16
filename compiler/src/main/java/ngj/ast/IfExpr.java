package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record IfExpr(
    Expr condition,
    BlockExpr thenBranch,
    Expr elseBranch,  // Can be BlockExpr or another IfExpr
    SourceSpan span
) implements Expr {}
