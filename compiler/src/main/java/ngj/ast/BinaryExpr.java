package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record BinaryExpr(
    Expr left,
    BinaryOp op,
    Expr right,
    SourceSpan span
) implements Expr {
    public enum BinaryOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"),
        EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
        AND("&&"), OR("||");
        
        private final String symbol;
        BinaryOp(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
    }
}
