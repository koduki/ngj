package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record RecordDecl(
    String name,
    boolean isPublic,
    List<Param> params,
    SourceSpan span
) implements TopLevelDecl {}
