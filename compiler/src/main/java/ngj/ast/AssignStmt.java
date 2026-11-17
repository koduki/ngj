package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record AssignStmt(
    String name,
    Expr value,
    SourceSpan span
) implements Statement {}
