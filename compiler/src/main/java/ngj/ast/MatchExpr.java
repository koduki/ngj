package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record MatchExpr(
    Expr scrutinee,
    List<MatchArm> arms,
    SourceSpan span
) implements Expr {
    public record MatchArm(Pattern pattern, Expr body, SourceSpan span) {}
}
