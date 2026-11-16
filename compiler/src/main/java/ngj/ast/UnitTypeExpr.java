package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record UnitTypeExpr(
    SourceSpan span
) implements TypeExpr {
    @Override
    public String toString() {
        return "Unit";
    }
}
