package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record TupleDestructureStmt(
    List<String> names,
    List<TypeExpr> explicitTypes,
    Expr initializer,
    SourceSpan span
) implements Statement {}
