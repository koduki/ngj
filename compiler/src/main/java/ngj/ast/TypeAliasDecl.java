package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record TypeAliasDecl(
    String name,
    boolean isPublic,
    List<String> typeParams,
    TypeExpr targetType,
    List<Annotation> annotations,
    SourceSpan span
) implements TopLevelDecl {
    public TypeAliasDecl(
        String name,
        boolean isPublic,
        List<String> typeParams,
        TypeExpr targetType,
        SourceSpan span
    ) {
        this(name, isPublic, typeParams, targetType, List.of(), span);
    }
}
