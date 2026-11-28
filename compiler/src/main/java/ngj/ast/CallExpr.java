package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record CallExpr(
    Expr callee,
    List<TypeExpr> typeArgs,
    List<Expr> args,
    SourceSpan span
) implements Expr {
    public CallExpr(Expr callee, List<Expr> args, SourceSpan span) {
        this(callee, List.of(), args, span);
    }
}
