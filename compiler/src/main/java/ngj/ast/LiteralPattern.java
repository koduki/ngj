package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record LiteralPattern(
    LiteralExpr literal,
    SourceSpan span
) implements Pattern {}
