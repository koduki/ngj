package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record TupleExpr(
    List<Expr> elements,
    SourceSpan span
) implements Expr {}
