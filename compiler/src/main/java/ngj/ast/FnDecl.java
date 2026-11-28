package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record FnDecl(
    String name,
    boolean isPublic,
    List<Param> params,
    TypeExpr returnType,
    List<String> effects,
    List<String> captureBounds,
    List<Annotation> annotations,
    List<ContractClause> contracts,
    BlockExpr body,
    SourceSpan span
) implements TopLevelDecl {
    public FnDecl(
        String name,
        boolean isPublic,
        List<Param> params,
        TypeExpr returnType,
        List<String> effects,
        BlockExpr body,
        SourceSpan span
    ) {
        this(name, isPublic, params, returnType, effects, List.of(), List.of(), List.of(), body, span);
    }

    public String signature() {
        var sb = new StringBuilder();
        if (isPublic) sb.append("public ");
        sb.append("fn ").append(name).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            var p = params.get(i);
            if (p.isConsume()) sb.append("consume ");
            sb.append(p.name()).append(": ").append(p.type());
        }
        sb.append(") -> ").append(returnType);
        sb.append(" effects(");
        sb.append(String.join(", ", effects));
        sb.append(")");
        if (!captureBounds.isEmpty()) {
            sb.append(" caps(").append(String.join(", ", captureBounds)).append(")");
        }
        return sb.toString();
    }
}
