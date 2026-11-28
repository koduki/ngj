package ngj.typechecker;

import ngj.ast.*;
import ngj.diagnostic.*;

import java.util.*;

/**
 * Phase 3 Selective PC-Guard & Boundary Schema Checker (IFC-03):
 * - Tracks Untrusted<T> taint in if/match/while conditions
 * - Rejects privileged_effects inside untrusted PC (NGJ-IFC-PC-001)
 * - Allows validated values from Result<Validated<T>, ValidationError>
 * - Validates @boundary_schema records/data/enums for serializability & cycles (NGJ-SCHEMA-001)
 */
public final class IfcPcChecker {
    public static final Set<String> DEFAULT_PRIVILEGED_EFFECTS = Set.of(
        "PaymentWrite", "DbWrite", "FsWrite", "Net", "Declassify"
    );

    private static final Set<String> NON_SERIALIZABLE_TYPES = Set.of(
        "Arena", "OwnedBytes", "Bytes", "Slice", "Task", "Budget", "MutableList",
        "DbRead", "DbWrite", "FsRead", "FsWrite", "Net", "PaymentWrite", "Declassify"
    );

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Set<String> privilegedEffects;
    private final Map<String, FnDecl> fnDecls = new HashMap<>();
    private final Map<String, TopLevelDecl> typeDecls = new HashMap<>();
    private final Map<String, ProtocolDecl> protocolDecls = new HashMap<>();

    public IfcPcChecker() {
        this(DEFAULT_PRIVILEGED_EFFECTS);
    }

    public IfcPcChecker(Set<String> privilegedEffects) {
        this.privilegedEffects = privilegedEffects != null && !privilegedEffects.isEmpty()
            ? Set.copyOf(privilegedEffects)
            : DEFAULT_PRIVILEGED_EFFECTS;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public void checkAll(List<CompilationUnit> units) {
        fnDecls.clear();
        typeDecls.clear();
        protocolDecls.clear();

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case FnDecl fn -> fnDecls.put(fn.name(), fn);
                    case DataDecl d -> typeDecls.put(d.name(), d);
                    case RecordDecl r -> typeDecls.put(r.name(), r);
                    case EnumDecl e -> typeDecls.put(e.name(), e);
                    case ProtocolDecl p -> protocolDecls.put(p.name(), p);
                    default -> {}
                }
            }
        }

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case RecordDecl r -> {
                        if (hasBoundarySchema(r.annotations())) {
                            checkBoundarySchema(r.name(), r.params().stream().map(Param::type).toList(), r.span());
                        }
                    }
                    case EnumDecl e -> {
                        if (hasBoundarySchema(e.annotations())) {
                            List<TypeExpr> types = new ArrayList<>();
                            for (var v : e.variants()) {
                                for (var f : v.fields()) types.add(f.type());
                            }
                            checkBoundarySchema(e.name(), types, e.span());
                        }
                    }
                    case FnDecl fn -> checkFn(fn);
                    default -> {}
                }
            }
        }
    }

    private boolean hasBoundarySchema(List<Annotation> annotations) {
        return annotations.stream().anyMatch(a -> a.name().equals("boundary_schema"));
    }

    private void checkBoundarySchema(String name, List<TypeExpr> fieldTypes, SourceSpan span) {
        for (var ft : fieldTypes) {
            checkSerializableType(ft, span);
        }
        // Cycle detection in @boundary_schema types
        if (hasSchemaCycle(name, new LinkedHashSet<>())) {
            diagnostics.add(Diagnostic.error(
                "NGJ-SCHEMA-001",
                "@boundary_schema type '" + name + "' contains an unsupported recursive cycle",
                span
            ));
        }
    }

    private void checkSerializableType(TypeExpr type, SourceSpan span) {
        if (type == null) return;
        switch (type) {
            case CapabilityTypeExpr _, CaptureSetTypeExpr _, FunctionTypeExpr _ -> diagnostics.add(Diagnostic.error(
                "NGJ-SCHEMA-001",
                "@boundary_schema cannot contain capability or function field type: " + type,
                type.span() != null ? type.span() : span
            ));
            case SimpleTypeExpr s -> {
                if (NON_SERIALIZABLE_TYPES.contains(s.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-SCHEMA-001",
                        "@boundary_schema cannot contain non-serializable resource type '" + s.name() + "'",
                        s.span() != null ? s.span() : span
                    ));
                }
            }
            case GenericTypeExpr g -> {
                if (NON_SERIALIZABLE_TYPES.contains(g.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-SCHEMA-001",
                        "@boundary_schema cannot contain non-serializable resource type '" + g.name() + "'",
                        g.span() != null ? g.span() : span
                    ));
                }
                for (var arg : g.typeArgs()) {
                    checkSerializableType(arg, span);
                }
            }
            case RefinedTypeExpr r -> checkSerializableType(r.baseType(), span);
            case RangeTypeExpr r -> checkSerializableType(r.baseType(), span);
            case TupleTypeExpr t -> {
                for (var elem : t.elements()) checkSerializableType(elem, span);
            }
            default -> {}
        }
    }

    private boolean hasSchemaCycle(String typeName, Set<String> visiting) {
        if (!visiting.add(typeName)) {
            return true;
        }
        TopLevelDecl decl = typeDecls.get(typeName);
        List<TypeExpr> refs = switch (decl) {
            case DataDecl d -> d.fields().stream().map(FieldDecl::type).toList();
            case RecordDecl r -> r.params().stream().map(Param::type).toList();
            case EnumDecl e -> e.variants().stream().flatMap(v -> v.fields().stream()).map(FieldDecl::type).toList();
            case null, default -> List.of();
        };
        for (var ref : refs) {
            for (String dep : referencedTypes(ref)) {
                if (typeDecls.containsKey(dep) && hasSchemaCycle(dep, visiting)) {
                    return true;
                }
            }
        }
        visiting.remove(typeName);
        return false;
    }

    private List<String> referencedTypes(TypeExpr t) {
        if (t == null) return List.of();
        return switch (t) {
            case SimpleTypeExpr s -> List.of(s.name());
            case GenericTypeExpr g -> {
                List<String> out = new ArrayList<>();
                out.add(g.name());
                for (var a : g.typeArgs()) out.addAll(referencedTypes(a));
                yield out;
            }
            case RefinedTypeExpr r -> referencedTypes(r.baseType());
            case RangeTypeExpr r -> referencedTypes(r.baseType());
            case TupleTypeExpr tup -> tup.elements().stream().flatMap(e -> referencedTypes(e).stream()).toList();
            default -> List.of();
        };
    }

    private void checkFn(FnDecl fn) {
        Set<String> untrustedVars = new LinkedHashSet<>();
        for (var p : fn.params()) {
            if (isUntrustedType(p.type())) {
                untrustedVars.add(p.name());
            }
        }
        if (fn.body() != null) {
            checkBlock(fn.body(), untrustedVars, false, new LinkedHashSet<>());
        }
    }

    private void checkBlock(BlockExpr block, Set<String> untrustedVars, boolean inUntrustedPc, Set<String> localDeclared) {
        if (block == null) return;
        for (var stmt : block.statements()) {
            checkStmt(stmt, untrustedVars, inUntrustedPc, localDeclared);
        }
        if (block.tailExpr() != null) {
            checkExpr(block.tailExpr(), untrustedVars, inUntrustedPc);
        }
    }

    private void checkStmt(Statement stmt, Set<String> untrustedVars, boolean inUntrustedPc, Set<String> localDeclared) {
        switch (stmt) {
            case ValStmt val -> {
                localDeclared.add(val.name());
                checkExpr(val.initializer(), untrustedVars, inUntrustedPc);
                if (isValidatedType(val.explicitType()) || isValidatedExpr(val.initializer())) {
                    untrustedVars.remove(val.name());
                } else if (isUntrustedType(val.explicitType()) || isExprUntrusted(val.initializer(), untrustedVars)) {
                    untrustedVars.add(val.name());
                }
            }
            case LetStmt let -> {
                localDeclared.add(let.name());
                checkExpr(let.initializer(), untrustedVars, inUntrustedPc);
                if (isValidatedType(let.explicitType()) || isValidatedExpr(let.initializer())) {
                    untrustedVars.remove(let.name());
                } else if (isUntrustedType(let.explicitType()) || isExprUntrusted(let.initializer(), untrustedVars)) {
                    untrustedVars.add(let.name());
                }
            }
            case VarStmt varStmt -> {
                localDeclared.add(varStmt.name());
                checkExpr(varStmt.initializer(), untrustedVars, inUntrustedPc);
                if (isUntrustedType(varStmt.explicitType()) || isExprUntrusted(varStmt.initializer(), untrustedVars)) {
                    untrustedVars.add(varStmt.name());
                }
            }
            case AssignStmt assign -> {
                checkExpr(assign.value(), untrustedVars, inUntrustedPc);
                if (inUntrustedPc || isExprUntrusted(assign.value(), untrustedVars)) {
                    untrustedVars.add(assign.name());
                }
            }
            case TupleDestructureStmt td -> {
                checkExpr(td.initializer(), untrustedVars, inUntrustedPc);
                if (td.initializer() instanceof TupleExpr te) {
                    for (int i = 0; i < td.names().size(); i++) {
                        boolean compUntrusted = inUntrustedPc
                            || (i < te.elements().size() && isExprUntrusted(te.elements().get(i), untrustedVars));
                        if (compUntrusted) {
                            untrustedVars.add(td.names().get(i));
                        }
                    }
                } else if (inUntrustedPc || isExprUntrusted(td.initializer(), untrustedVars)) {
                    untrustedVars.addAll(td.names());
                }
            }
            case ReturnStmt ret -> {
                if (ret.value() != null) checkExpr(ret.value(), untrustedVars, inUntrustedPc);
            }
            case ExprStmt es -> checkExpr(es.expr(), untrustedVars, inUntrustedPc);
            default -> {}
        }
    }

    private void checkExpr(Expr expr, Set<String> untrustedVars, boolean inUntrustedPc) {
        if (expr == null) return;
        switch (expr) {
            case IfExpr ifExpr -> {
                checkExpr(ifExpr.condition(), untrustedVars, inUntrustedPc);
                boolean pc = inUntrustedPc || isExprUntrusted(ifExpr.condition(), untrustedVars);
                Set<String> thenUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> thenDeclared = new LinkedHashSet<>();
                checkBlock(ifExpr.thenBranch(), thenUntrusted, pc, thenDeclared);
                thenUntrusted.removeAll(thenDeclared);

                Set<String> elseUntrusted = new LinkedHashSet<>();
                if (ifExpr.elseBranch() != null) {
                    Set<String> elseBranchUntrusted = new LinkedHashSet<>(untrustedVars);
                    if (ifExpr.elseBranch() instanceof BlockExpr eb) {
                        Set<String> elseDeclared = new LinkedHashSet<>();
                        checkBlock(eb, elseBranchUntrusted, pc, elseDeclared);
                        elseBranchUntrusted.removeAll(elseDeclared);
                    } else {
                        checkExpr(ifExpr.elseBranch(), elseBranchUntrusted, pc);
                    }
                    elseUntrusted.addAll(elseBranchUntrusted);
                }

                untrustedVars.addAll(thenUntrusted);
                untrustedVars.addAll(elseUntrusted);
            }
            case MatchExpr matchExpr -> {
                checkExpr(matchExpr.scrutinee(), untrustedVars, inUntrustedPc);
                boolean validatedScrutinee = isValidatedExpr(matchExpr.scrutinee());
                boolean pc = inUntrustedPc || (!validatedScrutinee && isExprUntrusted(matchExpr.scrutinee(), untrustedVars));
                Set<String> mergedArms = new LinkedHashSet<>();
                for (var arm : matchExpr.arms()) {
                    Set<String> armUntrusted = new LinkedHashSet<>(untrustedVars);
                    Set<String> armDeclared = new LinkedHashSet<>();
                    if (validatedScrutinee && arm.pattern() instanceof ConstructorPattern cp
                        && (cp.typePath().equals("Ok") || cp.typePath().endsWith(".Ok"))) {
                        for (var f : cp.fields()) {
                            armUntrusted.remove(f.name());
                            armDeclared.add(f.name());
                        }
                    }
                    if (arm.body() instanceof BlockExpr eb) {
                        checkBlock(eb, armUntrusted, pc, armDeclared);
                    } else {
                        checkExpr(arm.body(), armUntrusted, pc);
                    }
                    armUntrusted.removeAll(armDeclared);
                    mergedArms.addAll(armUntrusted);
                }
                untrustedVars.addAll(mergedArms);
            }
            case WhileExpr whileExpr -> {
                Set<String> loopUntrusted = new LinkedHashSet<>(untrustedVars);
                int diagBaseline = diagnostics.size();
                while (true) {
                    while (diagnostics.size() > diagBaseline) {
                        diagnostics.remove(diagnostics.size() - 1);
                    }
                    Set<String> prev = new LinkedHashSet<>(loopUntrusted);
                    checkExpr(whileExpr.condition(), loopUntrusted, inUntrustedPc);
                    boolean pc = inUntrustedPc || isExprUntrusted(whileExpr.condition(), loopUntrusted);
                    Set<String> bodyUntrusted = new LinkedHashSet<>(loopUntrusted);
                    Set<String> bodyDeclared = new LinkedHashSet<>();
                    checkBlock(whileExpr.body(), bodyUntrusted, pc, bodyDeclared);
                    bodyUntrusted.removeAll(bodyDeclared);
                    loopUntrusted.addAll(bodyUntrusted);
                    if (loopUntrusted.equals(prev)) {
                        break;
                    }
                }
                untrustedVars.addAll(loopUntrusted);
            }
            case CallExpr call -> {
                if (inUntrustedPc) {
                    checkPrivilegedCall(call);
                }
                checkExpr(call.callee(), untrustedVars, inUntrustedPc);
                for (var arg : call.args()) {
                    checkExpr(arg, untrustedVars, inUntrustedPc);
                }
            }
            case BlockExpr b -> {
                Set<String> blockUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> blockDeclared = new LinkedHashSet<>();
                checkBlock(b, blockUntrusted, inUntrustedPc, blockDeclared);
                blockUntrusted.removeAll(blockDeclared);
                untrustedVars.addAll(blockUntrusted);
            }
            case ScopeExpr s -> {
                Set<String> scopeUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> scopeDeclared = new LinkedHashSet<>();
                checkBlock(s.body(), scopeUntrusted, inUntrustedPc, scopeDeclared);
                scopeUntrusted.removeAll(scopeDeclared);
                untrustedVars.addAll(scopeUntrusted);
            }
            case ForkExpr f -> {
                Set<String> forkUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> forkDeclared = new LinkedHashSet<>();
                checkBlock(f.body(), forkUntrusted, inUntrustedPc, forkDeclared);
                forkUntrusted.removeAll(forkDeclared);
                untrustedVars.addAll(forkUntrusted);
            }
            case ForExpr f -> {
                checkExpr(f.iterable(), untrustedVars, inUntrustedPc);
                boolean iterUntrusted = isExprUntrusted(f.iterable(), untrustedVars);
                boolean pc = inUntrustedPc || iterUntrusted;
                Set<String> loopUntrusted = new LinkedHashSet<>(untrustedVars);
                if (iterUntrusted) {
                    loopUntrusted.add(f.varName());
                }
                int diagBaseline = diagnostics.size();
                while (true) {
                    while (diagnostics.size() > diagBaseline) {
                        diagnostics.remove(diagnostics.size() - 1);
                    }
                    Set<String> prev = new LinkedHashSet<>(loopUntrusted);
                    Set<String> bodyUntrusted = new LinkedHashSet<>(loopUntrusted);
                    Set<String> bodyDeclared = new LinkedHashSet<>();
                    bodyDeclared.add(f.varName());
                    checkBlock(f.body(), bodyUntrusted, pc, bodyDeclared);
                    bodyUntrusted.removeAll(bodyDeclared);
                    loopUntrusted.addAll(bodyUntrusted);
                    if (loopUntrusted.equals(prev)) {
                        break;
                    }
                }
                loopUntrusted.remove(f.varName());
                untrustedVars.addAll(loopUntrusted);
            }
            case BinaryExpr b -> {
                checkExpr(b.left(), untrustedVars, inUntrustedPc);
                checkExpr(b.right(), untrustedVars, inUntrustedPc);
            }
            case UnaryExpr u -> checkExpr(u.operand(), untrustedVars, inUntrustedPc);
            case ParenExpr p -> checkExpr(p.inner(), untrustedVars, inUntrustedPc);
            case PropagateExpr p -> checkExpr(p.inner(), untrustedVars, inUntrustedPc);
            case FieldAccessExpr fa -> checkExpr(fa.object(), untrustedVars, inUntrustedPc);
            case ConsumeExpr c -> checkExpr(c.target(), untrustedVars, inUntrustedPc);
            case TupleExpr t -> {
                for (var elem : t.elements()) {
                    checkExpr(elem, untrustedVars, inUntrustedPc);
                }
            }
            case ConstructorExpr c -> {
                for (var field : c.fields()) {
                    checkExpr(field.value(), untrustedVars, inUntrustedPc);
                }
            }
            case LambdaExpr l -> {
                Set<String> lambdaUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> lambdaDeclared = new LinkedHashSet<>();
                for (var p : l.params()) {
                    lambdaDeclared.add(p.name());
                    if (isUntrustedType(p.type())) {
                        lambdaUntrusted.add(p.name());
                    } else {
                        lambdaUntrusted.remove(p.name());
                    }
                }
                checkBlock(l.body(), lambdaUntrusted, inUntrustedPc, lambdaDeclared);
            }
            default -> {}
        }
    }

    private void checkPrivilegedCall(CallExpr call) {
        if (call.callee() instanceof IdentExpr id) {
            if (id.name().equals("declassify") && privilegedEffects.contains("Declassify")) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-IFC-PC-001",
                    "Privileged effect 'Declassify' cannot be invoked under an Untrusted control-flow guard",
                    call.span()
                ));
                return;
            }
            var fn = fnDecls.get(id.name());
            if (fn != null) {
                for (var eff : fn.effects()) {
                    if (privilegedEffects.contains(eff)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-IFC-PC-001",
                            "Privileged effect '" + eff + "' in call to '" + id.name()
                                + "' cannot be executed under an Untrusted control-flow guard",
                            call.span()
                        ));
                    }
                }
            }
        } else if (call.callee() instanceof FieldAccessExpr fa && fa.object() instanceof IdentExpr objId) {
            if (privilegedEffects.contains(objId.name())) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-IFC-PC-001",
                    "Privileged effect '" + objId.name() + "' cannot be executed under an Untrusted control-flow guard",
                    call.span()
                ));
            }
            ProtocolDecl proto = protocolDecls.get(objId.name());
            if (proto != null) {
                for (var tr : proto.transitions()) {
                    if (tr.name().equals(fa.fieldName())) {
                        for (var eff : tr.effects()) {
                            if (privilegedEffects.contains(eff)) {
                                diagnostics.add(Diagnostic.error(
                                    "NGJ-IFC-PC-001",
                                    "Privileged effect '" + eff + "' in protocol transition '"
                                        + proto.name() + "." + tr.name()
                                        + "' cannot be executed under an Untrusted control-flow guard",
                                    call.span()
                                ));
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isUntrustedType(TypeExpr type) {
        if (type == null) return false;
        return switch (type) {
            case GenericTypeExpr g -> g.name().equals("Untrusted");
            case SimpleTypeExpr s -> s.name().equals("Untrusted");
            case CapabilityTypeExpr c -> isUntrustedType(c.inner());
            default -> false;
        };
    }

    private boolean isValidatedType(TypeExpr type) {
        if (type == null) return false;
        return switch (type) {
            case GenericTypeExpr g -> g.name().equals("Validated")
                || (g.name().equals("Result") && !g.typeArgs().isEmpty() && isValidatedType(g.typeArgs().get(0)));
            case SimpleTypeExpr s -> s.name().equals("Validated");
            case CapabilityTypeExpr c -> isValidatedType(c.inner());
            default -> false;
        };
    }

    private boolean isValidatedExpr(Expr expr) {
        if (expr == null) return false;
        return switch (expr) {
            case PropagateExpr p -> isValidatedExpr(p.inner());
            case CallExpr c -> {
                if (c.callee() instanceof IdentExpr id) {
                    if (id.name().equals("validate")) yield true;
                    FnDecl fn = fnDecls.get(id.name());
                    if (fn != null && isValidatedType(fn.returnType())) yield true;
                }
                if (c.callee() instanceof FieldAccessExpr fa
                    && (fa.fieldName().equals("validate") || fa.fieldName().equals("parse"))) yield true;
                yield false;
            }
            case ParenExpr p -> isValidatedExpr(p.inner());
            default -> false;
        };
    }

    private boolean isExprUntrusted(Expr expr, Set<String> untrustedVars) {
        if (expr == null || isValidatedExpr(expr)) return false;
        return switch (expr) {
            case IdentExpr id -> untrustedVars.contains(id.name());
            case FieldAccessExpr fa -> isExprUntrusted(fa.object(), untrustedVars);
            case CallExpr c -> {
                if (c.callee() instanceof FieldAccessExpr fa && isExprUntrusted(fa.object(), untrustedVars)) {
                    yield true;
                }
                for (var arg : c.args()) {
                    if (isExprUntrusted(arg, untrustedVars)) yield true;
                }
                yield false;
            }
            case BinaryExpr b -> isExprUntrusted(b.left(), untrustedVars) || isExprUntrusted(b.right(), untrustedVars);
            case UnaryExpr u -> isExprUntrusted(u.operand(), untrustedVars);
            case ParenExpr p -> isExprUntrusted(p.inner(), untrustedVars);
            case PropagateExpr p -> isExprUntrusted(p.inner(), untrustedVars);
            case IfExpr i -> isExprUntrusted(i.condition(), untrustedVars)
                || isBlockUntrusted(i.thenBranch(), untrustedVars)
                || (i.elseBranch() != null && isExprUntrusted(i.elseBranch(), untrustedVars));
            case MatchExpr m -> {
                if (!isValidatedExpr(m.scrutinee()) && isExprUntrusted(m.scrutinee(), untrustedVars)) {
                    yield true;
                }
                for (var arm : m.arms()) {
                    if (isExprUntrusted(arm.body(), untrustedVars)) {
                        yield true;
                    }
                }
                yield false;
            }
            case BlockExpr b -> isBlockUntrusted(b, untrustedVars);
            case ScopeExpr s -> isBlockUntrusted(s.body(), untrustedVars);
            case ForkExpr f -> isBlockUntrusted(f.body(), untrustedVars);
            case TupleExpr t -> {
                for (var elem : t.elements()) {
                    if (isExprUntrusted(elem, untrustedVars)) yield true;
                }
                yield false;
            }
            case ConstructorExpr c -> {
                for (var field : c.fields()) {
                    if (isExprUntrusted(field.value(), untrustedVars)) yield true;
                }
                yield false;
            }
            case ForExpr f -> {
                if (isExprUntrusted(f.iterable(), untrustedVars)) yield true;
                yield isBlockUntrusted(f.body(), untrustedVars);
            }
            case ConsumeExpr c -> isExprUntrusted(c.target(), untrustedVars);
            default -> false;
        };
    }

    private boolean isBlockUntrusted(BlockExpr block, Set<String> untrustedVars) {
        if (block == null) return false;
        Set<String> localUntrusted = new LinkedHashSet<>(untrustedVars);
        for (var stmt : block.statements()) {
            switch (stmt) {
                case ValStmt val -> {
                    if (isValidatedType(val.explicitType()) || isValidatedExpr(val.initializer())) {
                        localUntrusted.remove(val.name());
                    } else if (isUntrustedType(val.explicitType()) || isExprUntrusted(val.initializer(), localUntrusted)) {
                        localUntrusted.add(val.name());
                    }
                }
                case LetStmt let -> {
                    if (isValidatedType(let.explicitType()) || isValidatedExpr(let.initializer())) {
                        localUntrusted.remove(let.name());
                    } else if (isUntrustedType(let.explicitType()) || isExprUntrusted(let.initializer(), localUntrusted)) {
                        localUntrusted.add(let.name());
                    }
                }
                case VarStmt varStmt -> {
                    if (isUntrustedType(varStmt.explicitType()) || isExprUntrusted(varStmt.initializer(), localUntrusted)) {
                        localUntrusted.add(varStmt.name());
                    }
                }
                case AssignStmt assign -> {
                    if (isExprUntrusted(assign.value(), localUntrusted)) {
                        localUntrusted.add(assign.name());
                    }
                }
                case ReturnStmt ret -> {
                    if (ret.value() != null && isExprUntrusted(ret.value(), localUntrusted)) {
                        return true;
                    }
                }
                case ExprStmt es -> {
                    if (es.expr() instanceof IfExpr || es.expr() instanceof MatchExpr || es.expr() instanceof BlockExpr) {
                        if (isExprUntrusted(es.expr(), localUntrusted)) {
                            return true;
                        }
                    }
                }
                default -> {}
            }
        }
        return block.tailExpr() != null && isExprUntrusted(block.tailExpr(), localUntrusted);
    }
}
