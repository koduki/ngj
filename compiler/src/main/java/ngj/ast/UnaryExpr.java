package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record UnaryExpr(
    UnaryOp op,
    Expr operand,
    SourceSpan span
) implements Expr {
    public enum UnaryOp {
        NEG("-"),
        NOT("!");
        
        private final String symbol;
        UnaryOp(String symbol) { this.symbol = symbol; }
        public String symbol() { return symbol; }
    }
}
