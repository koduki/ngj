package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record EnumDecl(
    String name,
    boolean isPublic,
    List<Variant> variants,
    SourceSpan span
) implements TopLevelDecl {}
