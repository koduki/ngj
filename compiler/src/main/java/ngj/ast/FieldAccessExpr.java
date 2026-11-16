package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record FieldAccessExpr(
    Expr object,
    String fieldName,
    SourceSpan span
) implements Expr {}
