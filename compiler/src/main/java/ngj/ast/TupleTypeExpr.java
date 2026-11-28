package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;
import java.util.stream.Collectors;

public record TupleTypeExpr(
    List<TypeExpr> elements,
    SourceSpan span
) implements TypeExpr {
    @Override
    public String toString() {
        return "(" + elements.stream().map(TypeExpr::toString).collect(Collectors.joining(", ")) + ")";
    }
}
