package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;
import java.util.stream.Collectors;

public record FunctionTypeExpr(
    List<TypeExpr> paramTypes,
    TypeExpr returnType,
    List<String> effects,
    List<String> captureBounds,
    SourceSpan span
) implements TypeExpr {
    public FunctionTypeExpr(
        List<TypeExpr> paramTypes,
        TypeExpr returnType,
        SourceSpan span
    ) {
        this(paramTypes, returnType, List.of(), List.of(), span);
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append("(");
        sb.append(paramTypes.stream().map(TypeExpr::toString).collect(Collectors.joining(", ")));
        sb.append(") -> ").append(returnType);
        if (!effects.isEmpty()) {
            sb.append(" effects(").append(String.join(", ", effects)).append(")");
        }
        if (!captureBounds.isEmpty()) {
            sb.append(" caps(").append(String.join(", ", captureBounds)).append(")");
        }
        return sb.toString();
    }
}
