package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

public record CaptureSetTypeExpr(
    List<String> targets,
    SourceSpan span
) implements TypeExpr {
    @Override
    public String toString() {
        return "caps(" + String.join(", ", targets) + ")";
    }
}
