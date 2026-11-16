package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record LiteralExpr(
    LiteralKind kind,
    Object value,
    SourceSpan span
) implements Expr {
    public enum LiteralKind {
        INT,
        LONG,
        BOOL,
        STRING,
        UNIT
    }
}
