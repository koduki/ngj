package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record BlockExpr(
    List<Statement> statements,
    Expr tailExpr,
    SourceSpan span
) implements Expr {}
