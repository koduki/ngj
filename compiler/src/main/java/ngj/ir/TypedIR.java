package ngj.ir;

import ngj.ast.LemmaDecl;
import ngj.ast.ProtocolDecl;
import ngj.ast.SpecFnDecl;
import ngj.ast.TypeAliasDecl;
import ngj.typechecker.protocol.ProtocolAutomaton;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Typed IR metadata produced by {@link ngj.typechecker.TypeChecker} for
 * downstream codegen, inspection, and verification gates.
 */
public final class TypedIR {
    private final Map<String, TypeAliasDecl> typeAliases;
    private final Map<String, ProtocolDecl> protocols;
    private final Map<String, ProtocolAutomaton> protocolAutomata;
    private final Map<String, SpecFnDecl> specFunctions;
    private final Map<String, LemmaDecl> lemmas;
    private final Set<String> boundarySchemaTypes;

    public TypedIR(
        Map<String, TypeAliasDecl> typeAliases,
        Map<String, ProtocolDecl> protocols,
        Map<String, ProtocolAutomaton> protocolAutomata,
        Map<String, SpecFnDecl> specFunctions,
        Map<String, LemmaDecl> lemmas,
        Set<String> boundarySchemaTypes
    ) {
        this.typeAliases = Collections.unmodifiableMap(new LinkedHashMap<>(typeAliases));
        this.protocols = Collections.unmodifiableMap(new LinkedHashMap<>(protocols));
        this.protocolAutomata = Collections.unmodifiableMap(new LinkedHashMap<>(protocolAutomata));
        this.specFunctions = Collections.unmodifiableMap(new LinkedHashMap<>(specFunctions));
        this.lemmas = Collections.unmodifiableMap(new LinkedHashMap<>(lemmas));
        this.boundarySchemaTypes = Collections.unmodifiableSet(new LinkedHashSet<>(boundarySchemaTypes));
    }

    public static TypedIR empty() {
        return new TypedIR(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of());
    }

    public Map<String, TypeAliasDecl> typeAliases() {
        return typeAliases;
    }

    public Map<String, ProtocolDecl> protocols() {
        return protocols;
    }

    public Map<String, ProtocolAutomaton> protocolAutomata() {
        return protocolAutomata;
    }

    public Map<String, SpecFnDecl> specFunctions() {
        return specFunctions;
    }

    public Map<String, LemmaDecl> lemmas() {
        return lemmas;
    }

    public Set<String> boundarySchemaTypes() {
        return boundarySchemaTypes;
    }
}
