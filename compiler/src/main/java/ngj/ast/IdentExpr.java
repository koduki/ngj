package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record IdentExpr(
    String name,
    SourceSpan span
) implements Expr {}
