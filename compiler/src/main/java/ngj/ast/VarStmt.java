package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record VarStmt(
    String name,
    TypeExpr explicitType,
    Expr initializer,
    SourceSpan span
) implements Statement {}
