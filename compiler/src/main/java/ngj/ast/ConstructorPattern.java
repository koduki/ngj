package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record ConstructorPattern(
    String typePath,
    List<PatternField> fields,
    SourceSpan span
) implements Pattern {
    public record PatternField(String name, Pattern pattern, SourceSpan span) {}
}
