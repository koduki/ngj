package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record FnDecl(
    String name,
    boolean isPublic,
    List<Param> params,
    TypeExpr returnType,
    List<String> effects,
    BlockExpr body,
    SourceSpan span
) implements TopLevelDecl {
    public String signature() {
        var sb = new StringBuilder();
        if (isPublic) sb.append("pub ");
        sb.append("fn ").append(name).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            var p = params.get(i);
            sb.append(p.name()).append(": ").append(p.type());
        }
        sb.append(") -> ").append(returnType);
        sb.append(" ! {");
        sb.append(String.join(", ", effects));
        sb.append("}");
        return sb.toString();
    }
}
