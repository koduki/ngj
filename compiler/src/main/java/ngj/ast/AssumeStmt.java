package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record AssumeStmt(
    Expr condition,
    SourceSpan span
) implements Statement {}
