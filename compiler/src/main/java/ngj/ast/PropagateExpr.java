package ngj.ast;

import ngj.diagnostic.SourceSpan;

/**
 * Postfix ? operator for Result/Option propagation.
 */
public record PropagateExpr(
    Expr inner,
    SourceSpan span
) implements Expr {}
