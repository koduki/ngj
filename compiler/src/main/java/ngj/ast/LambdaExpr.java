package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record LambdaExpr(
    List<Param> params,
    BlockExpr body,
    SourceSpan span
) implements Expr {}
