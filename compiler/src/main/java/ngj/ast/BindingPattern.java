package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record BindingPattern(
    String name,
    SourceSpan span
) implements Pattern {}
