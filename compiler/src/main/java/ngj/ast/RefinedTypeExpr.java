package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record RefinedTypeExpr(
    String binderName,
    TypeExpr baseType,
    Expr predicate,
    SourceSpan span
) implements TypeExpr {
    @Override
    public String toString() {
        if (binderName != null) {
            return "{" + binderName + ": " + baseType + " where ...}";
        }
        return baseType + " where ...";
    }
}
