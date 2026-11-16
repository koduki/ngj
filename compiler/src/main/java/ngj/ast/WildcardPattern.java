package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record WildcardPattern(
    SourceSpan span
) implements Pattern {}
