package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record ModuleDecl(
    String path,
    SourceSpan span
) implements Node {}
