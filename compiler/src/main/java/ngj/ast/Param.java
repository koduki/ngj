package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record Param(
    String name,
    TypeExpr type,
    boolean isConsume,
    SourceSpan span
) implements Node {
    public Param(String name, TypeExpr type, SourceSpan span) {
        this(name, type, false, span);
    }
}
