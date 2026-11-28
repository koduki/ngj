package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record Annotation(
    String name,
    List<String> args,
    SourceSpan span
) implements Node {}
