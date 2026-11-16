package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record CallExpr(
    Expr callee,
    List<Expr> args,
    SourceSpan span
) implements Expr {}
