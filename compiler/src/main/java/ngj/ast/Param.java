package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record Param(
    String name,
    TypeExpr type,
    SourceSpan span
) implements Node {}
