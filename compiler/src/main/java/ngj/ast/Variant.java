package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record Variant(
    String name,
    List<FieldDecl> fields,
    SourceSpan span
) implements Node {}
