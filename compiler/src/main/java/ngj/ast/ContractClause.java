package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ContractClause(
    Kind kind,
    Expr condition,
    SourceSpan span
) implements Node {
    public enum Kind {
        REQUIRES,
        ENSURES
    }
}
