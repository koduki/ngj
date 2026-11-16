package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record LetStmt(
    String name,
    TypeExpr explicitType,
    Expr initializer,
    SourceSpan span
) implements Statement {}
