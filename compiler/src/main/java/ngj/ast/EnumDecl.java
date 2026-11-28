package ngj.ast;

import ngj.diagnostic.SourceSpan;
import java.util.List;

/**
 * Enum declaration - sealed algebraic data type (ADT) with payload-carrying variants.
 * 
 * All enums and their variants are value types (identity-free, immutable).
 * 
 * Example:
 *   enum Option<T> {
 *       Some(value: T),
 *       None()
 *   }
 * 
 * JVM representation: sealed hierarchy of value classes
 */
public record EnumDecl(
    String name,
    boolean isPublic,
    List<String> typeParams,
    List<Variant> variants,
    List<Annotation> annotations,
    SourceSpan span
) implements TopLevelDecl {
    
    public EnumDecl(String name, boolean isPublic, List<String> typeParams, List<Variant> variants, SourceSpan span) {
        this(name, isPublic, typeParams, variants, List.of(), span);
    }

    public EnumDecl(String name, boolean isPublic, List<Variant> variants, SourceSpan span) {
        this(name, isPublic, List.of(), variants, List.of(), span);
    }
    
    public boolean isGeneric() {
        return !typeParams.isEmpty();
    }
}
