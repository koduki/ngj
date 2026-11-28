package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record SpecFnDecl(
    String name,
    boolean isPublic,
    boolean isPredicate,
    List<Param> params,
    TypeExpr returnType,
    BlockExpr body,
    List<Annotation> annotations,
    SourceSpan span
) implements TopLevelDecl {}
