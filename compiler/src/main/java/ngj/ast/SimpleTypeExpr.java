package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record SimpleTypeExpr(
    String name,
    SourceSpan span
) implements TypeExpr {
    @Override
    public String toString() {
        return name;
    }
}
