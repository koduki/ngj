package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ScopeExpr(
    BlockExpr body,
    SourceSpan span
) implements Expr {}
