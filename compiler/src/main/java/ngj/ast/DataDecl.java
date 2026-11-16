package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record DataDecl(
    String name,
    boolean isPublic,
    List<FieldDecl> fields,
    SourceSpan span
) implements TopLevelDecl {}
