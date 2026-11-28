package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record RecordDecl(
    String name,
    boolean isPublic,
    List<Param> params,
    List<Annotation> annotations,
    SourceSpan span
) implements TopLevelDecl {
    public RecordDecl(String name, boolean isPublic, List<Param> params, SourceSpan span) {
        this(name, isPublic, params, List.of(), span);
    }
}
