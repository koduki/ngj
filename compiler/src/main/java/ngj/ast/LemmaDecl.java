package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record LemmaDecl(
    String name,
    boolean isPublic,
    List<Param> params,
    List<ContractClause> contracts,
    BlockExpr body,
    List<Annotation> annotations,
    SourceSpan span
) implements TopLevelDecl {}
