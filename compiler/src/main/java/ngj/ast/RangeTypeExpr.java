package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record RangeTypeExpr(
    TypeExpr baseType,
    Expr lowerBound,
    Expr upperBound,
    boolean upperInclusive,
    SourceSpan span
) implements TypeExpr {
    @Override
    public String toString() {
        String op = upperInclusive ? ".." : "..<";
        return baseType + "[" + lowerBound + op + (upperBound != null ? upperBound : "") + "]";
    }
}
