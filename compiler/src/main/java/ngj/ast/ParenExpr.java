package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ParenExpr(
    Expr inner,
    SourceSpan span
) implements Expr {}
