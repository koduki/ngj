package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record FieldDecl(
    String name,
    TypeExpr type,
    SourceSpan span
) implements Node {}
