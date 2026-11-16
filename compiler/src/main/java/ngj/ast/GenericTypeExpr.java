package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;
import java.util.stream.Collectors;

public record GenericTypeExpr(
    String name,
    List<TypeExpr> typeArgs,
    SourceSpan span
) implements TypeExpr {
    @Override
    public String toString() {
        return name + "<" + typeArgs.stream()
            .map(TypeExpr::toString)
            .collect(Collectors.joining(", ")) + ">";
    }
}
