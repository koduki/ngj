package ngj.ast;

import ngj.diagnostic.SourceSpan;

public record CapabilityTypeExpr(
    CapabilityMode mode,
    TypeExpr inner,
    SourceSpan span
) implements TypeExpr {
    public enum CapabilityMode {
        READ,
        UPDATE
    }

    @Override
    public String toString() {
        return (mode == CapabilityMode.READ ? "read " : "update ") + inner;
    }
}
