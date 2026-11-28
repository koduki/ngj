package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record WhileExpr(
    Expr condition,
    Expr invariant,
    BlockExpr body,
    SourceSpan span
) implements Expr {
    public WhileExpr(Expr condition, BlockExpr body, SourceSpan span) {
        this(condition, null, body, span);
    }
}
