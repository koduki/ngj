package ngj.ast;

import ngj.diagnostic.SourceSpan;
import ngj.HoleContext.Binding;
import ngj.HoleContext.RelevantSymbol;
import java.util.List;
import java.util.ArrayList;

/**
 * A typed hole: ?name or ?proof_hole
 * The expected type and context are computed during type checking.
 */
public final class HoleExpr implements Expr {
    public enum HoleKind {
        CODE,
        PROOF
    }

    private final String name;
    private final HoleKind kind;
    private final SourceSpan span;
    
    private TypeExpr expectedType;
    private List<String> allowedEffects = List.of();
    private List<Binding> bindings = new ArrayList<>();
    private List<RelevantSymbol> relevantSymbols = new ArrayList<>();
    
    public HoleExpr(String name, SourceSpan span) {
        this(name, HoleKind.CODE, span);
    }

    public HoleExpr(String name, HoleKind kind, SourceSpan span) {
        this.name = name;
        this.kind = kind;
        this.span = span;
    }
    
    public String name() { return name; }
    public HoleKind kind() { return kind; }
    public boolean isProofHole() { return kind == HoleKind.PROOF; }
    public SourceSpan span() { return span; }
    
    public TypeExpr expectedType() { return expectedType; }
    public void setExpectedType(TypeExpr type) { this.expectedType = type; }
    
    public List<String> allowedEffects() { return allowedEffects; }
    public void setAllowedEffects(List<String> effects) { this.allowedEffects = effects; }
    
    public List<Binding> bindings() { return bindings; }
    public void addBinding(Binding binding) { this.bindings.add(binding); }
    
    public List<RelevantSymbol> relevantSymbols() { return relevantSymbols; }
    public void addRelevantSymbol(RelevantSymbol symbol) { this.relevantSymbols.add(symbol); }
}
