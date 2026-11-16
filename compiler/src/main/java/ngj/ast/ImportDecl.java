package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record ImportDecl(
    String modulePath,
    List<String> names,
    boolean isWildcard,
    SourceSpan span
) implements Node {}
