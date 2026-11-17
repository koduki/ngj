package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ValStmt(
    String name,
    TypeExpr explicitType,
    Expr initializer,
    SourceSpan span
) implements Statement {}
