package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record ConstructorExpr(
    String typePath,
    List<FieldInit> fields,
    SourceSpan span
) implements Expr {
    public record FieldInit(String name, Expr value, SourceSpan span) {}
}
