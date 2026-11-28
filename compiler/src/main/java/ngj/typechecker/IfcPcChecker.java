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
    private final Map<String, TypeAliasDecl> typeAliases = new HashMap<>();

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
        typeAliases.clear();

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case FnDecl fn -> fnDecls.put(fn.name(), fn);
                    case DataDecl d -> typeDecls.put(d.name(), d);
                    case RecordDecl r -> typeDecls.put(r.name(), r);
                    case EnumDecl e -> typeDecls.put(e.name(), e);
                    case ProtocolDecl p -> protocolDecls.put(p.name(), p);
                    case TypeAliasDecl ta -> typeAliases.put(ta.name(), ta);
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
        TypeExpr resolved = resolveAlias(type);
        if (resolved == null) return;
        switch (resolved) {
            case CapabilityTypeExpr _, CaptureSetTypeExpr _, FunctionTypeExpr _ -> diagnostics.add(Diagnostic.error(
                "NGJ-SCHEMA-001",
                "@boundary_schema cannot contain capability or function field type: " + resolved,
                resolved.span() != null ? resolved.span() : span
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
        TypeExpr resolved = resolveAlias(t);
        if (resolved == null) return List.of();
        return switch (resolved) {
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
        Map<String, TypeExpr> varTypes = new LinkedHashMap<>();
        for (var p : fn.params()) {
            TypeExpr resolved = resolveAlias(p.type());
            if (resolved != null) {
                varTypes.put(p.name(), resolved);
            }
            if (isUntrustedType(resolved)) {
                untrustedVars.add(p.name());
            }
        }
        if (fn.body() != null) {
            checkBlock(fn.body(), untrustedVars, varTypes, false, new LinkedHashSet<>());
        }
    }

    private boolean checkBlock(
        BlockExpr block,
        Set<String> untrustedVars,
        Map<String, TypeExpr> varTypes,
        boolean inUntrustedPc,
        Set<String> localDeclared
    ) {
        if (block == null) return false;
        boolean curPc = inUntrustedPc;
        boolean exited = false;
        for (var stmt : block.statements()) {
            if (checkStmt(stmt, untrustedVars, varTypes, curPc, localDeclared)) {
                curPc = true;
                exited = true;
            }
        }
        if (block.tailExpr() != null) {
            if (checkExpr(block.tailExpr(), untrustedVars, varTypes, curPc)) {
                exited = true;
            }
        }
        return exited;
    }

    private boolean checkStmt(
        Statement stmt,
        Set<String> untrustedVars,
        Map<String, TypeExpr> varTypes,
        boolean inUntrustedPc,
        Set<String> localDeclared
    ) {
        return switch (stmt) {
            case ValStmt val -> {
                boolean exited = checkExpr(val.initializer(), untrustedVars, varTypes, inUntrustedPc);
                TypeExpr effectiveType = val.explicitType() != null
                    ? resolveAlias(val.explicitType())
                    : inferExprType(val.initializer(), varTypes);
                boolean initUntrusted = isExprUntrusted(val.initializer(), untrustedVars, varTypes);
                localDeclared.add(val.name());
                if (effectiveType != null) {
                    varTypes.put(val.name(), effectiveType);
                } else {
                    varTypes.remove(val.name());
                }
                if (isValidatedType(val.explicitType()) || isValidatedExpr(val.initializer())) {
                    untrustedVars.remove(val.name());
                } else if (isUntrustedType(effectiveType) || initUntrusted) {
                    untrustedVars.add(val.name());
                } else {
                    untrustedVars.remove(val.name());
                }
                yield exited;
            }
            case LetStmt let -> {
                boolean exited = checkExpr(let.initializer(), untrustedVars, varTypes, inUntrustedPc);
                TypeExpr effectiveType = let.explicitType() != null
                    ? resolveAlias(let.explicitType())
                    : inferExprType(let.initializer(), varTypes);
                boolean initUntrusted = isExprUntrusted(let.initializer(), untrustedVars, varTypes);
                localDeclared.add(let.name());
                if (effectiveType != null) {
                    varTypes.put(let.name(), effectiveType);
                } else {
                    varTypes.remove(let.name());
                }
                if (isValidatedType(let.explicitType()) || isValidatedExpr(let.initializer())) {
                    untrustedVars.remove(let.name());
                } else if (isUntrustedType(effectiveType) || initUntrusted) {
                    untrustedVars.add(let.name());
                } else {
                    untrustedVars.remove(let.name());
                }
                yield exited;
            }
            case VarStmt varStmt -> {
                boolean exited = checkExpr(varStmt.initializer(), untrustedVars, varTypes, inUntrustedPc);
                TypeExpr effectiveType = varStmt.explicitType() != null
                    ? resolveAlias(varStmt.explicitType())
                    : inferExprType(varStmt.initializer(), varTypes);
                boolean initUntrusted = isExprUntrusted(varStmt.initializer(), untrustedVars, varTypes);
                localDeclared.add(varStmt.name());
                if (effectiveType != null) {
                    varTypes.put(varStmt.name(), effectiveType);
                } else {
                    varTypes.remove(varStmt.name());
                }
                if (isUntrustedType(effectiveType) || initUntrusted) {
                    untrustedVars.add(varStmt.name());
                } else {
                    untrustedVars.remove(varStmt.name());
                }
                yield exited;
            }
            case AssignStmt assign -> {
                boolean exited = checkExpr(assign.value(), untrustedVars, varTypes, inUntrustedPc);
                if (inUntrustedPc || exited || isExprUntrusted(assign.value(), untrustedVars, varTypes)) {
                    untrustedVars.add(assign.name());
                }
                yield exited;
            }
            case TupleDestructureStmt td -> {
                boolean exited = checkExpr(td.initializer(), untrustedVars, varTypes, inUntrustedPc);
                boolean curPc = inUntrustedPc || exited;
                TypeExpr initType = resolveAlias(inferExprType(td.initializer(), varTypes));
                TupleTypeExpr tupleType = initType instanceof TupleTypeExpr tt ? tt : null;
                TupleExpr te = td.initializer() instanceof TupleExpr t ? t : null;
                List<TypeExpr> inferredElems = new ArrayList<>(td.names().size());
                List<Boolean> untrustedFlags = new ArrayList<>(td.names().size());
                for (int i = 0; i < td.names().size(); i++) {
                    TypeExpr explicitType = i < td.explicitTypes().size() ? resolveAlias(td.explicitTypes().get(i)) : null;
                    TypeExpr inferredElem = explicitType != null
                        ? explicitType
                        : (te != null && i < te.elements().size()
                            ? inferExprType(te.elements().get(i), varTypes)
                            : (tupleType != null && i < tupleType.elements().size()
                                ? resolveAlias(tupleType.elements().get(i))
                                : null));
                    boolean compUntrusted = curPc
                        || isUntrustedType(inferredElem)
                        || (te != null && i < te.elements().size() && isExprUntrusted(te.elements().get(i), untrustedVars, varTypes))
                        || (te == null && isExprUntrusted(td.initializer(), untrustedVars, varTypes));
                    inferredElems.add(inferredElem);
                    untrustedFlags.add(compUntrusted);
                }
                for (int i = 0; i < td.names().size(); i++) {
                    String name = td.names().get(i);
                    localDeclared.add(name);
                    TypeExpr inferredElem = inferredElems.get(i);
                    if (inferredElem != null) {
                        varTypes.put(name, inferredElem);
                    } else {
                        varTypes.remove(name);
                    }
                    if (Boolean.TRUE.equals(untrustedFlags.get(i))) {
                        untrustedVars.add(name);
                    } else {
                        untrustedVars.remove(name);
                    }
                }
                yield exited;
            }
            case ReturnStmt ret -> {
                boolean valExited = ret.value() != null && checkExpr(ret.value(), untrustedVars, varTypes, inUntrustedPc);
                yield inUntrustedPc || valExited;
            }
            case ExprStmt es -> checkExpr(es.expr(), untrustedVars, varTypes, inUntrustedPc);
            default -> false;
        };
    }

    private boolean checkExpr(Expr expr, Set<String> untrustedVars, Map<String, TypeExpr> varTypes, boolean inUntrustedPc) {
        if (expr == null) return false;
        return switch (expr) {
            case IfExpr ifExpr -> {
                boolean condExited = checkExpr(ifExpr.condition(), untrustedVars, varTypes, inUntrustedPc);
                boolean pc = inUntrustedPc || condExited || isExprUntrusted(ifExpr.condition(), untrustedVars, varTypes);
                Set<String> thenUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> thenDeclared = new LinkedHashSet<>();
                boolean thenExited = checkBlock(ifExpr.thenBranch(), thenUntrusted, new LinkedHashMap<>(varTypes), pc, thenDeclared);
                thenUntrusted.removeAll(thenDeclared);

                Set<String> elseUntrusted = new LinkedHashSet<>();
                boolean elseExited = false;
                if (ifExpr.elseBranch() != null) {
                    Set<String> elseBranchUntrusted = new LinkedHashSet<>(untrustedVars);
                    Map<String, TypeExpr> elseVarTypes = new LinkedHashMap<>(varTypes);
                    if (ifExpr.elseBranch() instanceof BlockExpr eb) {
                        Set<String> elseDeclared = new LinkedHashSet<>();
                        elseExited = checkBlock(eb, elseBranchUntrusted, elseVarTypes, pc, elseDeclared);
                        elseBranchUntrusted.removeAll(elseDeclared);
                    } else {
                        elseExited = checkExpr(ifExpr.elseBranch(), elseBranchUntrusted, elseVarTypes, pc);
                    }
                    elseUntrusted.addAll(elseBranchUntrusted);
                }

                untrustedVars.addAll(thenUntrusted);
                untrustedVars.addAll(elseUntrusted);
                yield condExited || thenExited || elseExited;
            }
            case MatchExpr matchExpr -> {
                boolean scrutExited = checkExpr(matchExpr.scrutinee(), untrustedVars, varTypes, inUntrustedPc);
                boolean validatedScrutinee = isValidatedExpr(matchExpr.scrutinee());
                boolean scrutineeUntrusted = !validatedScrutinee && isExprUntrusted(matchExpr.scrutinee(), untrustedVars, varTypes);
                TypeExpr scrutineeType = resolveAlias(inferExprType(matchExpr.scrutinee(), varTypes));
                boolean pc = inUntrustedPc || scrutExited || scrutineeUntrusted;
                Set<String> mergedArms = new LinkedHashSet<>();
                boolean anyArmExited = false;
                for (var arm : matchExpr.arms()) {
                    Set<String> armUntrusted = new LinkedHashSet<>(untrustedVars);
                    Map<String, TypeExpr> armVarTypes = new LinkedHashMap<>(varTypes);
                    Set<String> armDeclared = new LinkedHashSet<>();
                    boolean validatedOk = validatedScrutinee && arm.pattern() instanceof ConstructorPattern cp
                        && (cp.typePath().equals("Ok") || cp.typePath().endsWith(".Ok"));
                    bindPatternTypes(arm.pattern(), scrutineeType, scrutineeUntrusted, validatedOk, armUntrusted, armVarTypes, armDeclared);
                    boolean armExited;
                    if (arm.body() instanceof BlockExpr eb) {
                        armExited = checkBlock(eb, armUntrusted, armVarTypes, pc, armDeclared);
                    } else {
                        armExited = checkExpr(arm.body(), armUntrusted, armVarTypes, pc);
                    }
                    if (armExited) {
                        anyArmExited = true;
                    }
                    armUntrusted.removeAll(armDeclared);
                    mergedArms.addAll(armUntrusted);
                }
                untrustedVars.addAll(mergedArms);
                yield scrutExited || anyArmExited;
            }
            case WhileExpr whileExpr -> {
                Set<String> loopUntrusted = new LinkedHashSet<>(untrustedVars);
                boolean loopPc = inUntrustedPc;
                boolean loopExited = false;
                int diagBaseline = diagnostics.size();
                while (true) {
                    while (diagnostics.size() > diagBaseline) {
                        diagnostics.remove(diagnostics.size() - 1);
                    }
                    Set<String> prev = new LinkedHashSet<>(loopUntrusted);
                    boolean prevLoopPc = loopPc;
                    boolean condExited = checkExpr(whileExpr.condition(), loopUntrusted, varTypes, loopPc);
                    boolean pc = loopPc || condExited || isExprUntrusted(whileExpr.condition(), loopUntrusted, varTypes);
                    Set<String> bodyUntrusted = new LinkedHashSet<>(loopUntrusted);
                    Set<String> bodyDeclared = new LinkedHashSet<>();
                    boolean bodyExited = checkBlock(whileExpr.body(), bodyUntrusted, new LinkedHashMap<>(varTypes), pc, bodyDeclared);
                    bodyUntrusted.removeAll(bodyDeclared);
                    loopUntrusted.addAll(bodyUntrusted);
                    if (condExited || bodyExited) {
                        loopExited = true;
                        loopPc = true;
                    }
                    if (loopUntrusted.equals(prev) && loopPc == prevLoopPc) {
                        break;
                    }
                }
                untrustedVars.addAll(loopUntrusted);
                yield loopExited;
            }
            case CallExpr call -> {
                boolean curPc = inUntrustedPc;
                boolean exited = false;
                if (checkExpr(call.callee(), untrustedVars, varTypes, curPc)) {
                    curPc = true;
                    exited = true;
                }
                for (var arg : call.args()) {
                    if (checkExpr(arg, untrustedVars, varTypes, curPc)) {
                        curPc = true;
                        exited = true;
                    }
                }
                if (curPc) {
                    checkPrivilegedCall(call, varTypes);
                }
                yield exited;
            }
            case BlockExpr b -> {
                Set<String> blockUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> blockDeclared = new LinkedHashSet<>();
                boolean exited = checkBlock(b, blockUntrusted, new LinkedHashMap<>(varTypes), inUntrustedPc, blockDeclared);
                blockUntrusted.removeAll(blockDeclared);
                untrustedVars.addAll(blockUntrusted);
                yield exited;
            }
            case ScopeExpr s -> {
                Set<String> scopeUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> scopeDeclared = new LinkedHashSet<>();
                boolean exited = checkBlock(s.body(), scopeUntrusted, new LinkedHashMap<>(varTypes), inUntrustedPc, scopeDeclared);
                scopeUntrusted.removeAll(scopeDeclared);
                untrustedVars.addAll(scopeUntrusted);
                yield exited;
            }
            case ForkExpr f -> {
                Set<String> forkUntrusted = new LinkedHashSet<>(untrustedVars);
                Set<String> forkDeclared = new LinkedHashSet<>();
                checkBlock(f.body(), forkUntrusted, new LinkedHashMap<>(varTypes), inUntrustedPc, forkDeclared);
                forkUntrusted.removeAll(forkDeclared);
                untrustedVars.addAll(forkUntrusted);
                yield false;
            }
            case ForExpr f -> {
                boolean iterExited = checkExpr(f.iterable(), untrustedVars, varTypes, inUntrustedPc);
                boolean iterUntrusted = isExprUntrusted(f.iterable(), untrustedVars, varTypes);
                TypeExpr iterType = resolveAlias(inferExprType(f.iterable(), varTypes));
                TypeExpr elemType = extractCollectionElementType(iterType);
                boolean elemUntrusted = iterUntrusted || isUntrustedType(elemType);
                boolean loopPc = inUntrustedPc || iterExited || iterUntrusted;
                boolean loopExited = iterExited;
                Set<String> loopUntrusted = new LinkedHashSet<>(untrustedVars);
                if (elemUntrusted) {
                    loopUntrusted.add(f.varName());
                } else {
                    loopUntrusted.remove(f.varName());
                }
                Map<String, TypeExpr> loopVarTypes = new LinkedHashMap<>(varTypes);
                if (elemType != null) {
                    loopVarTypes.put(f.varName(), elemType);
                } else {
                    loopVarTypes.remove(f.varName());
                }
                int diagBaseline = diagnostics.size();
                while (true) {
                    while (diagnostics.size() > diagBaseline) {
                        diagnostics.remove(diagnostics.size() - 1);
                    }
                    Set<String> prev = new LinkedHashSet<>(loopUntrusted);
                    boolean prevLoopPc = loopPc;
                    Set<String> bodyUntrusted = new LinkedHashSet<>(loopUntrusted);
                    Set<String> bodyDeclared = new LinkedHashSet<>();
                    bodyDeclared.add(f.varName());
                    boolean bodyExited = checkBlock(f.body(), bodyUntrusted, new LinkedHashMap<>(loopVarTypes), loopPc, bodyDeclared);
                    bodyUntrusted.removeAll(bodyDeclared);
                    loopUntrusted.addAll(bodyUntrusted);
                    if (bodyExited) {
                        loopExited = true;
                        loopPc = true;
                    }
                    if (loopUntrusted.equals(prev) && loopPc == prevLoopPc) {
                        break;
                    }
                }
                loopUntrusted.remove(f.varName());
                untrustedVars.addAll(loopUntrusted);
                yield loopExited;
            }
            case BinaryExpr b -> {
                boolean curPc = inUntrustedPc;
                boolean leftExited = checkExpr(b.left(), untrustedVars, varTypes, curPc);
                if (leftExited) {
                    curPc = true;
                }
                boolean rightPc = curPc;
                if (b.op() == BinaryExpr.BinaryOp.AND || b.op() == BinaryExpr.BinaryOp.OR) {
                    rightPc = curPc || isExprUntrusted(b.left(), untrustedVars, varTypes);
                }
                boolean rightExited = checkExpr(b.right(), untrustedVars, varTypes, rightPc);
                yield leftExited || rightExited;
            }
            case UnaryExpr u -> checkExpr(u.operand(), untrustedVars, varTypes, inUntrustedPc);
            case ParenExpr p -> checkExpr(p.inner(), untrustedVars, varTypes, inUntrustedPc);
            case PropagateExpr p -> {
                boolean innerExited = checkExpr(p.inner(), untrustedVars, varTypes, inUntrustedPc);
                boolean untrustedInner = !isValidatedExpr(p.inner()) && isExprUntrusted(p.inner(), untrustedVars, varTypes);
                yield inUntrustedPc || innerExited || untrustedInner;
            }
            case FieldAccessExpr fa -> checkExpr(fa.object(), untrustedVars, varTypes, inUntrustedPc);
            case ConsumeExpr c -> checkExpr(c.target(), untrustedVars, varTypes, inUntrustedPc);
            case TupleExpr t -> {
                boolean curPc = inUntrustedPc;
                boolean exited = false;
                for (var elem : t.elements()) {
                    if (checkExpr(elem, untrustedVars, varTypes, curPc)) {
                        curPc = true;
                        exited = true;
                    }
                }
                yield exited;
            }
            case ConstructorExpr c -> {
                boolean curPc = inUntrustedPc;
                boolean exited = false;
                for (var field : c.fields()) {
                    if (checkExpr(field.value(), untrustedVars, varTypes, curPc)) {
                        curPc = true;
                        exited = true;
                    }
                }
                yield exited;
            }
            case LambdaExpr l -> {
                Set<String> lambdaUntrusted = new LinkedHashSet<>(untrustedVars);
                Map<String, TypeExpr> lambdaVarTypes = new LinkedHashMap<>(varTypes);
                Set<String> lambdaDeclared = new LinkedHashSet<>();
                for (var p : l.params()) {
                    lambdaDeclared.add(p.name());
                    TypeExpr resolved = resolveAlias(p.type());
                    if (resolved != null) {
                        lambdaVarTypes.put(p.name(), resolved);
                    } else {
                        lambdaVarTypes.remove(p.name());
                    }
                    if (isUntrustedType(resolved)) {
                        lambdaUntrusted.add(p.name());
                    } else {
                        lambdaUntrusted.remove(p.name());
                    }
                }
                checkBlock(l.body(), lambdaUntrusted, lambdaVarTypes, inUntrustedPc, lambdaDeclared);
                yield false;
            }
            default -> false;
        };
    }

    private void bindPatternTypes(
        Pattern pattern,
        TypeExpr expectedType,
        boolean parentUntrusted,
        boolean validatedOk,
        Set<String> armUntrusted,
        Map<String, TypeExpr> armVarTypes,
        Set<String> armDeclared
    ) {
        if (pattern == null) return;
        TypeExpr resolvedExpected = resolveAlias(expectedType);
        switch (pattern) {
            case BindingPattern bp -> {
                armDeclared.add(bp.name());
                if (resolvedExpected != null) {
                    armVarTypes.put(bp.name(), resolvedExpected);
                } else {
                    armVarTypes.remove(bp.name());
                }
                if (validatedOk) {
                    armUntrusted.remove(bp.name());
                } else if (parentUntrusted || isUntrustedType(resolvedExpected)) {
                    armUntrusted.add(bp.name());
                } else {
                    armUntrusted.remove(bp.name());
                }
            }
            case ConstructorPattern cp -> {
                String shortCtor = cp.typePath().contains(".")
                    ? cp.typePath().substring(cp.typePath().lastIndexOf('.') + 1)
                    : cp.typePath();
                List<TypeExpr> fieldTypes = resolveConstructorPatternFieldTypes(shortCtor, resolvedExpected);
                for (int i = 0; i < cp.fields().size(); i++) {
                    var f = cp.fields().get(i);
                    TypeExpr fieldType = resolveConstructorPatternFieldType(shortCtor, resolvedExpected, f.name(), i, fieldTypes);
                    if (f.pattern() != null) {
                        bindPatternTypes(f.pattern(), fieldType, parentUntrusted, validatedOk, armUntrusted, armVarTypes, armDeclared);
                    } else if (f.name() != null) {
                        armDeclared.add(f.name());
                        if (fieldType != null) {
                            armVarTypes.put(f.name(), fieldType);
                        } else {
                            armVarTypes.remove(f.name());
                        }
                        if (validatedOk) {
                            armUntrusted.remove(f.name());
                        } else if (parentUntrusted || isUntrustedType(fieldType)) {
                            armUntrusted.add(f.name());
                        } else {
                            armUntrusted.remove(f.name());
                        }
                    }
                }
            }
            default -> {}
        }
    }

    private List<TypeExpr> resolveConstructorPatternFieldTypes(String ctorName, TypeExpr scrutineeType) {
        TypeExpr unwrapped = unwrapTypeWrappers(scrutineeType);
        if (ctorName.equals("Some") && unwrapped instanceof GenericTypeExpr g && g.name().equals("Option") && !g.typeArgs().isEmpty()) {
            return List.of(resolveAlias(g.typeArgs().get(0)));
        }
        if (ctorName.equals("Ok") && unwrapped instanceof GenericTypeExpr g && g.name().equals("Result") && !g.typeArgs().isEmpty()) {
            return List.of(resolveAlias(g.typeArgs().get(0)));
        }
        if (ctorName.equals("Err") && unwrapped instanceof GenericTypeExpr g && g.name().equals("Result") && g.typeArgs().size() >= 2) {
            return List.of(resolveAlias(g.typeArgs().get(1)));
        }
        TopLevelDecl decl = typeDecls.get(ctorName);
        if (decl instanceof RecordDecl r) {
            return r.params().stream().map(p -> resolveAlias(p.type())).toList();
        }
        if (decl instanceof DataDecl d) {
            return d.fields().stream().map(f -> resolveAlias(f.type())).toList();
        }
        // Look up enum variant
        for (var td : typeDecls.values()) {
            if (td instanceof EnumDecl e) {
                Map<String, TypeExpr> subst = buildEnumSubst(e, unwrapped);
                for (var v : e.variants()) {
                    if (v.name().equals(ctorName)) {
                        return v.fields().stream()
                            .map(f -> resolveAlias(substituteTypeParams(f.type(), subst)))
                            .toList();
                    }
                }
            }
        }
        return List.of();
    }

    private TypeExpr resolveConstructorPatternFieldType(
        String ctorName,
        TypeExpr scrutineeType,
        String fieldName,
        int index,
        List<TypeExpr> positionalTypes
    ) {
        if (fieldName != null) {
            TopLevelDecl decl = typeDecls.get(ctorName);
            if (decl instanceof RecordDecl r) {
                for (var p : r.params()) {
                    if (p.name().equals(fieldName)) return resolveAlias(p.type());
                }
            } else if (decl instanceof DataDecl d) {
                for (var f : d.fields()) {
                    if (f.name().equals(fieldName)) return resolveAlias(f.type());
                }
            }
            TypeExpr unwrapped = unwrapTypeWrappers(scrutineeType);
            for (var td : typeDecls.values()) {
                if (td instanceof EnumDecl e) {
                    Map<String, TypeExpr> subst = buildEnumSubst(e, unwrapped);
                    for (var v : e.variants()) {
                        if (v.name().equals(ctorName)) {
                            for (var f : v.fields()) {
                                if (f.name().equals(fieldName)) {
                                    return resolveAlias(substituteTypeParams(f.type(), subst));
                                }
                            }
                        }
                    }
                }
            }
        }
        return index < positionalTypes.size() ? positionalTypes.get(index) : null;
    }

    private Map<String, TypeExpr> buildEnumSubst(EnumDecl e, TypeExpr scrutineeType) {
        Map<String, TypeExpr> subst = new HashMap<>();
        if (scrutineeType instanceof GenericTypeExpr g && g.name().equals(e.name())) {
            for (int i = 0; i < Math.min(e.typeParams().size(), g.typeArgs().size()); i++) {
                subst.put(e.typeParams().get(i), g.typeArgs().get(i));
            }
        }
        return subst;
    }

    private TypeExpr unwrapTypeWrappers(TypeExpr type) {
        TypeExpr cur = resolveAlias(type);
        while (cur != null) {
            if (cur instanceof CapabilityTypeExpr c) {
                cur = resolveAlias(c.inner());
            } else if (cur instanceof RefinedTypeExpr r) {
                cur = resolveAlias(r.baseType());
            } else if (cur instanceof RangeTypeExpr r) {
                cur = resolveAlias(r.baseType());
            } else {
                break;
            }
        }
        return cur;
    }

    private TypeExpr extractCollectionElementType(TypeExpr iterType) {
        TypeExpr unwrapped = unwrapTypeWrappers(iterType);
        if (unwrapped instanceof GenericTypeExpr g && !g.typeArgs().isEmpty()
            && (g.name().equals("List") || g.name().equals("Seq") || g.name().equals("Array") || g.name().equals("MutableList") || g.name().equals("Set"))) {
            return resolveAlias(g.typeArgs().get(0));
        }
        return null;
    }

    private TypeExpr inferExprType(Expr expr, Map<String, TypeExpr> varTypes) {
        if (expr == null) return null;
        return switch (expr) {
            case IdentExpr id -> {
                if (varTypes.containsKey(id.name())) {
                    yield resolveAlias(varTypes.get(id.name()));
                }
                FnDecl fn = fnDecls.get(id.name());
                if (fn != null) {
                    yield new FunctionTypeExpr(
                        fn.params().stream().map(Param::type).toList(),
                        fn.returnType(),
                        fn.effects(),
                        fn.captureBounds(),
                        fn.span()
                    );
                }
                yield null;
            }
            case LambdaExpr l -> {
                Set<String> effs = new LinkedHashSet<>();
                Map<String, TypeExpr> lambdaTypes = new LinkedHashMap<>(varTypes);
                for (var p : l.params()) {
                    TypeExpr pt = resolveAlias(p.type());
                    if (pt != null) lambdaTypes.put(p.name(), pt);
                    else lambdaTypes.remove(p.name());
                }
                collectBlockEffects(l.body(), lambdaTypes, effs);
                yield new FunctionTypeExpr(
                    l.params().stream().map(Param::type).toList(),
                    inferBlockType(l.body(), lambdaTypes),
                    List.copyOf(effs),
                    List.of(),
                    l.span()
                );
            }
            case ParenExpr p -> inferExprType(p.inner(), varTypes);
            case ConsumeExpr c -> inferExprType(c.target(), varTypes);
            case PropagateExpr p -> {
                TypeExpr innerType = unwrapTypeWrappers(inferExprType(p.inner(), varTypes));
                if (innerType instanceof GenericTypeExpr g
                    && (g.name().equals("Result") || g.name().equals("Option"))
                    && !g.typeArgs().isEmpty()) {
                    yield resolveAlias(g.typeArgs().get(0));
                }
                yield innerType;
            }
            case FieldAccessExpr fa -> resolveFieldType(inferExprType(fa.object(), varTypes), fa.fieldName());
            case CallExpr c -> {
                if (c.callee() instanceof IdentExpr id) {
                    FnDecl fn = fnDecls.get(id.name());
                    if (fn != null) {
                        yield resolveAlias(fn.returnType());
                    }
                    if (typeDecls.containsKey(id.name())) {
                        yield c.typeArgs().isEmpty()
                            ? new SimpleTypeExpr(id.name(), c.span())
                            : new GenericTypeExpr(id.name(), c.typeArgs(), c.span());
                    }
                }
                TypeExpr calleeType = unwrapTypeWrappers(inferExprType(c.callee(), varTypes));
                if (calleeType instanceof FunctionTypeExpr fnType) {
                    yield resolveAlias(fnType.returnType());
                }
                yield null;
            }
            case ConstructorExpr c -> {
                String shortName = c.typePath().contains(".")
                    ? c.typePath().substring(c.typePath().lastIndexOf('.') + 1)
                    : c.typePath();
                yield new SimpleTypeExpr(shortName, c.span());
            }
            case TupleExpr t -> new TupleTypeExpr(
                t.elements().stream().map(e -> inferExprType(e, varTypes)).toList(),
                t.span()
            );
            case IfExpr i -> {
                TypeExpr thenT = inferExprType(i.thenBranch(), varTypes);
                TypeExpr elseT = inferExprType(i.elseBranch(), varTypes);
                if (thenT instanceof FunctionTypeExpr tf && elseT instanceof FunctionTypeExpr ef) {
                    Set<String> mergedEffs = new LinkedHashSet<>(tf.effects());
                    mergedEffs.addAll(ef.effects());
                    yield new FunctionTypeExpr(tf.paramTypes(), tf.returnType(), List.copyOf(mergedEffs), tf.captureBounds(), i.span());
                }
                yield thenT != null ? thenT : elseT;
            }
            case MatchExpr m -> {
                TypeExpr acc = null;
                for (var arm : m.arms()) {
                    TypeExpr at = inferExprType(arm.body(), varTypes);
                    if (acc instanceof FunctionTypeExpr af && at instanceof FunctionTypeExpr bf) {
                        Set<String> mergedEffs = new LinkedHashSet<>(af.effects());
                        mergedEffs.addAll(bf.effects());
                        acc = new FunctionTypeExpr(af.paramTypes(), af.returnType(), List.copyOf(mergedEffs), af.captureBounds(), m.span());
                    } else if (acc == null) {
                        acc = at;
                    }
                }
                yield acc;
            }
            case BlockExpr b -> inferBlockType(b, varTypes);
            default -> null;
        };
    }

    private void collectBlockEffects(BlockExpr block, Map<String, TypeExpr> varTypes, Set<String> out) {
        if (block == null) return;
        Map<String, TypeExpr> localTypes = new LinkedHashMap<>(varTypes);
        for (var st : block.statements()) {
            switch (st) {
                case ValStmt v -> {
                    collectExprEffects(v.initializer(), localTypes, out);
                    TypeExpr t = v.explicitType() != null ? resolveAlias(v.explicitType()) : inferExprType(v.initializer(), localTypes);
                    if (t != null) localTypes.put(v.name(), t);
                }
                case LetStmt l -> {
                    collectExprEffects(l.initializer(), localTypes, out);
                    TypeExpr t = l.explicitType() != null ? resolveAlias(l.explicitType()) : inferExprType(l.initializer(), localTypes);
                    if (t != null) localTypes.put(l.name(), t);
                }
                case VarStmt v -> {
                    collectExprEffects(v.initializer(), localTypes, out);
                    TypeExpr t = v.explicitType() != null ? resolveAlias(v.explicitType()) : inferExprType(v.initializer(), localTypes);
                    if (t != null) localTypes.put(v.name(), t);
                }
                case AssignStmt a -> collectExprEffects(a.value(), localTypes, out);
                case TupleDestructureStmt td -> collectExprEffects(td.initializer(), localTypes, out);
                case ReturnStmt r -> collectExprEffects(r.value(), localTypes, out);
                case ExprStmt es -> collectExprEffects(es.expr(), localTypes, out);
                default -> {}
            }
        }
        collectExprEffects(block.tailExpr(), localTypes, out);
    }

    private void collectExprEffects(Expr expr, Map<String, TypeExpr> varTypes, Set<String> out) {
        if (expr == null) return;
        switch (expr) {
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr id) {
                    if (id.name().equals("declassify")) out.add("Declassify");
                    FnDecl fn = fnDecls.get(id.name());
                    if (fn != null) out.addAll(fn.effects());
                } else if (call.callee() instanceof FieldAccessExpr fa) {
                    if (fa.object() instanceof IdentExpr objId) {
                        if (privilegedEffects.contains(objId.name())) out.add(objId.name());
                        collectProtocolTransitionEffects(protocolDecls.get(objId.name()), fa.fieldName(), out);
                    }
                    ProtocolDecl instProto = protocolForExpr(fa.object(), varTypes);
                    collectProtocolTransitionEffects(instProto, fa.fieldName(), out);
                }
                TypeExpr calleeType = unwrapTypeWrappers(inferExprType(call.callee(), varTypes));
                if (calleeType instanceof FunctionTypeExpr fnType) {
                    out.addAll(fnType.effects());
                }
                collectExprEffects(call.callee(), varTypes, out);
                for (var a : call.args()) collectExprEffects(a, varTypes, out);
            }
            case BlockExpr b -> collectBlockEffects(b, varTypes, out);
            case ScopeExpr s -> collectBlockEffects(s.body(), varTypes, out);
            case ForkExpr f -> collectBlockEffects(f.body(), varTypes, out);
            case IfExpr i -> {
                collectExprEffects(i.condition(), varTypes, out);
                collectBlockEffects(i.thenBranch(), varTypes, out);
                collectExprEffects(i.elseBranch(), varTypes, out);
            }
            case MatchExpr m -> {
                collectExprEffects(m.scrutinee(), varTypes, out);
                for (var arm : m.arms()) collectExprEffects(arm.body(), varTypes, out);
            }
            case WhileExpr w -> {
                collectExprEffects(w.condition(), varTypes, out);
                collectBlockEffects(w.body(), varTypes, out);
            }
            case ForExpr f -> {
                collectExprEffects(f.iterable(), varTypes, out);
                collectBlockEffects(f.body(), varTypes, out);
            }
            case BinaryExpr b -> {
                collectExprEffects(b.left(), varTypes, out);
                collectExprEffects(b.right(), varTypes, out);
            }
            case UnaryExpr u -> collectExprEffects(u.operand(), varTypes, out);
            case ParenExpr p -> collectExprEffects(p.inner(), varTypes, out);
            case PropagateExpr p -> collectExprEffects(p.inner(), varTypes, out);
            case ConsumeExpr c -> collectExprEffects(c.target(), varTypes, out);
            case FieldAccessExpr fa -> collectExprEffects(fa.object(), varTypes, out);
            case TupleExpr t -> { for (var e : t.elements()) collectExprEffects(e, varTypes, out); }
            case ConstructorExpr c -> { for (var f : c.fields()) collectExprEffects(f.value(), varTypes, out); }
            default -> {}
        }
    }

    private void collectProtocolTransitionEffects(ProtocolDecl proto, String action, Set<String> out) {
        if (proto == null || action == null) return;
        for (var tr : proto.transitions()) {
            if (tr.name().equals(action)) {
                out.addAll(tr.effects());
            }
        }
    }

    private ProtocolDecl protocolForExpr(Expr expr, Map<String, TypeExpr> varTypes) {
        TypeExpr t = unwrapTypeWrappers(inferExprType(expr, varTypes));
        String name = switch (t) {
            case SimpleTypeExpr s -> s.name();
            case GenericTypeExpr g -> g.name();
            case null, default -> null;
        };
        return name != null ? protocolDecls.get(name) : null;
    }

    private TypeExpr inferBlockType(BlockExpr block, Map<String, TypeExpr> varTypes) {
        if (block == null) return null;
        Map<String, TypeExpr> localTypes = new LinkedHashMap<>(varTypes);
        for (var st : block.statements()) {
            switch (st) {
                case ValStmt v -> {
                    TypeExpr t = v.explicitType() != null ? resolveAlias(v.explicitType()) : inferExprType(v.initializer(), localTypes);
                    if (t != null) localTypes.put(v.name(), t);
                }
                case LetStmt l -> {
                    TypeExpr t = l.explicitType() != null ? resolveAlias(l.explicitType()) : inferExprType(l.initializer(), localTypes);
                    if (t != null) localTypes.put(l.name(), t);
                }
                case VarStmt v -> {
                    TypeExpr t = v.explicitType() != null ? resolveAlias(v.explicitType()) : inferExprType(v.initializer(), localTypes);
                    if (t != null) localTypes.put(v.name(), t);
                }
                default -> {}
            }
        }
        return inferExprType(block.tailExpr(), localTypes);
    }

    private TypeExpr resolveFieldType(TypeExpr ownerType, String fieldName) {
        TypeExpr unwrapped = unwrapTypeWrappers(ownerType);
        if (unwrapped == null || fieldName == null) return null;
        if (unwrapped instanceof TupleTypeExpr tup) {
            try {
                int idx = Integer.parseInt(fieldName);
                if (idx >= 0 && idx < tup.elements().size()) {
                    return resolveAlias(tup.elements().get(idx));
                }
            } catch (NumberFormatException ignored) {}
            return null;
        }
        String ownerName = switch (unwrapped) {
            case SimpleTypeExpr s -> s.name();
            case GenericTypeExpr g -> g.name();
            default -> null;
        };
        if (ownerName == null) return null;
        TopLevelDecl decl = typeDecls.get(ownerName);
        if (decl instanceof RecordDecl r) {
            for (var p : r.params()) {
                if (p.name().equals(fieldName)) {
                    return resolveAlias(p.type());
                }
            }
        } else if (decl instanceof DataDecl d) {
            for (var f : d.fields()) {
                if (f.name().equals(fieldName)) {
                    return resolveAlias(f.type());
                }
            }
        } else if (decl instanceof EnumDecl e) {
            Map<String, TypeExpr> subst = buildEnumSubst(e, unwrapped);
            for (var v : e.variants()) {
                for (var f : v.fields()) {
                    if (f.name().equals(fieldName)) {
                        return resolveAlias(substituteTypeParams(f.type(), subst));
                    }
                }
            }
        }
        return null;
    }

    private void checkPrivilegedCall(CallExpr call, Map<String, TypeExpr> varTypes) {
        boolean reportedDirectFn = false;
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
                reportedDirectFn = true;
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
        } else if (call.callee() instanceof FieldAccessExpr fa) {
            if (fa.object() instanceof IdentExpr objId) {
                if (privilegedEffects.contains(objId.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-IFC-PC-001",
                        "Privileged effect '" + objId.name() + "' cannot be executed under an Untrusted control-flow guard",
                        call.span()
                    ));
                }
                ProtocolDecl proto = protocolDecls.get(objId.name());
                if (proto != null) {
                    reportProtocolTransitionIfPrivileged(proto, fa.fieldName(), call.span());
                }
            }
            ProtocolDecl instProto = protocolForExpr(fa.object(), varTypes);
            if (instProto != null) {
                reportProtocolTransitionIfPrivileged(instProto, fa.fieldName(), call.span());
            }
        }
        if (!reportedDirectFn) {
            TypeExpr calleeType = unwrapTypeWrappers(inferExprType(call.callee(), varTypes));
            if (calleeType instanceof FunctionTypeExpr fnType) {
                for (var eff : fnType.effects()) {
                    if (privilegedEffects.contains(eff)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-IFC-PC-001",
                            "Privileged effect '" + eff + "' in callback invocation cannot be executed under an Untrusted control-flow guard",
                            call.span()
                        ));
                    }
                }
            }
        }
    }

    private void reportProtocolTransitionIfPrivileged(ProtocolDecl proto, String action, SourceSpan span) {
        for (var tr : proto.transitions()) {
            if (tr.name().equals(action)) {
                for (var eff : tr.effects()) {
                    if (privilegedEffects.contains(eff)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-IFC-PC-001",
                            "Privileged effect '" + eff + "' in protocol transition '"
                                + proto.name() + "." + tr.name()
                                + "' cannot be executed under an Untrusted control-flow guard",
                            span
                        ));
                    }
                }
            }
        }
    }

    private TypeExpr resolveAlias(TypeExpr type) {
        return resolveAlias(type, new LinkedHashSet<>());
    }

    private TypeExpr resolveAlias(TypeExpr type, Set<String> seen) {
        if (type == null) return null;
        if (type instanceof SimpleTypeExpr s && typeAliases.containsKey(s.name()) && seen.add(s.name())) {
            TypeExpr resolved = resolveAlias(typeAliases.get(s.name()).targetType(), seen);
            seen.remove(s.name());
            return resolved;
        }
        if (type instanceof GenericTypeExpr g && typeAliases.containsKey(g.name()) && seen.add(g.name())) {
            TypeAliasDecl alias = typeAliases.get(g.name());
            Map<String, TypeExpr> subst = new HashMap<>();
            for (int i = 0; i < Math.min(alias.typeParams().size(), g.typeArgs().size()); i++) {
                subst.put(alias.typeParams().get(i), g.typeArgs().get(i));
            }
            TypeExpr substituted = substituteTypeParams(alias.targetType(), subst);
            TypeExpr resolved = resolveAlias(substituted, seen);
            seen.remove(g.name());
            return resolved;
        }
        return type;
    }

    private TypeExpr substituteTypeParams(TypeExpr type, Map<String, TypeExpr> subst) {
        if (type == null || subst.isEmpty()) return type;
        return switch (type) {
            case SimpleTypeExpr s -> subst.getOrDefault(s.name(), s);
            case GenericTypeExpr g -> new GenericTypeExpr(
                g.name(),
                g.typeArgs().stream().map(a -> substituteTypeParams(a, subst)).toList(),
                g.span()
            );
            case CapabilityTypeExpr c -> new CapabilityTypeExpr(c.mode(), substituteTypeParams(c.inner(), subst), c.span());
            case RefinedTypeExpr r -> new RefinedTypeExpr(r.binderName(), substituteTypeParams(r.baseType(), subst), r.predicate(), r.span());
            case RangeTypeExpr r -> new RangeTypeExpr(substituteTypeParams(r.baseType(), subst), r.lowerBound(), r.upperBound(), r.upperInclusive(), r.span());
            case TupleTypeExpr t -> new TupleTypeExpr(t.elements().stream().map(e -> substituteTypeParams(e, subst)).toList(), t.span());
            default -> type;
        };
    }

    private boolean isUntrustedType(TypeExpr type) {
        TypeExpr resolved = resolveAlias(type);
        if (resolved == null) return false;
        return switch (resolved) {
            case GenericTypeExpr g -> g.name().equals("Untrusted");
            case SimpleTypeExpr s -> s.name().equals("Untrusted");
            case CapabilityTypeExpr c -> isUntrustedType(c.inner());
            case RefinedTypeExpr r -> isUntrustedType(r.baseType());
            default -> false;
        };
    }

    private boolean isValidatedType(TypeExpr type) {
        TypeExpr resolved = resolveAlias(type);
        if (resolved == null) return false;
        return switch (resolved) {
            case GenericTypeExpr g -> g.name().equals("Validated")
                || (g.name().equals("Result") && !g.typeArgs().isEmpty() && isValidatedType(g.typeArgs().get(0)));
            case SimpleTypeExpr s -> s.name().equals("Validated");
            case CapabilityTypeExpr c -> isValidatedType(c.inner());
            case RefinedTypeExpr r -> isValidatedType(r.baseType());
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

    private boolean isExprUntrusted(Expr expr, Set<String> untrustedVars, Map<String, TypeExpr> varTypes) {
        if (expr == null || isValidatedExpr(expr)) return false;
        if (isUntrustedType(inferExprType(expr, varTypes))) return true;
        return switch (expr) {
            case IdentExpr id -> untrustedVars.contains(id.name());
            case FieldAccessExpr fa -> isExprUntrusted(fa.object(), untrustedVars, varTypes);
            case CallExpr c -> {
                if (c.callee() instanceof FieldAccessExpr fa && isExprUntrusted(fa.object(), untrustedVars, varTypes)) {
                    yield true;
                }
                for (var arg : c.args()) {
                    if (isExprUntrusted(arg, untrustedVars, varTypes)) yield true;
                }
                yield false;
            }
            case BinaryExpr b -> isExprUntrusted(b.left(), untrustedVars, varTypes) || isExprUntrusted(b.right(), untrustedVars, varTypes);
            case UnaryExpr u -> isExprUntrusted(u.operand(), untrustedVars, varTypes);
            case ParenExpr p -> isExprUntrusted(p.inner(), untrustedVars, varTypes);
            case PropagateExpr p -> isExprUntrusted(p.inner(), untrustedVars, varTypes);
            case IfExpr i -> isExprUntrusted(i.condition(), untrustedVars, varTypes)
                || isBlockUntrusted(i.thenBranch(), untrustedVars, varTypes)
                || (i.elseBranch() != null && isExprUntrusted(i.elseBranch(), untrustedVars, varTypes));
            case MatchExpr m -> {
                if (!isValidatedExpr(m.scrutinee()) && isExprUntrusted(m.scrutinee(), untrustedVars, varTypes)) {
                    yield true;
                }
                TypeExpr scrutType = resolveAlias(inferExprType(m.scrutinee(), varTypes));
                for (var arm : m.arms()) {
                    Set<String> armUntrusted = new LinkedHashSet<>(untrustedVars);
                    Map<String, TypeExpr> armVarTypes = new LinkedHashMap<>(varTypes);
                    bindPatternTypes(arm.pattern(), scrutType, false, false, armUntrusted, armVarTypes, new LinkedHashSet<>());
                    if (isExprUntrusted(arm.body(), armUntrusted, armVarTypes)) {
                        yield true;
                    }
                }
                yield false;
            }
            case BlockExpr b -> isBlockUntrusted(b, untrustedVars, varTypes);
            case ScopeExpr s -> isBlockUntrusted(s.body(), untrustedVars, varTypes);
            case ForkExpr f -> isBlockUntrusted(f.body(), untrustedVars, varTypes);
            case TupleExpr t -> {
                for (var elem : t.elements()) {
                    if (isExprUntrusted(elem, untrustedVars, varTypes)) yield true;
                }
                yield false;
            }
            case ConstructorExpr c -> {
                for (var field : c.fields()) {
                    if (isExprUntrusted(field.value(), untrustedVars, varTypes)) yield true;
                }
                yield false;
            }
            case ForExpr f -> {
                if (isExprUntrusted(f.iterable(), untrustedVars, varTypes)) yield true;
                yield isBlockUntrusted(f.body(), untrustedVars, varTypes);
            }
            case ConsumeExpr c -> isExprUntrusted(c.target(), untrustedVars, varTypes);
            default -> false;
        };
    }

    private boolean isBlockUntrusted(BlockExpr block, Set<String> untrustedVars, Map<String, TypeExpr> varTypes) {
        if (block == null) return false;
        Set<String> localUntrusted = new LinkedHashSet<>(untrustedVars);
        Map<String, TypeExpr> localTypes = new LinkedHashMap<>(varTypes);
        for (var stmt : block.statements()) {
            switch (stmt) {
                case ValStmt val -> {
                    TypeExpr t = val.explicitType() != null ? resolveAlias(val.explicitType()) : inferExprType(val.initializer(), localTypes);
                    if (t != null) localTypes.put(val.name(), t);
                    if (isValidatedType(val.explicitType()) || isValidatedExpr(val.initializer())) {
                        localUntrusted.remove(val.name());
                    } else if (isUntrustedType(t) || isExprUntrusted(val.initializer(), localUntrusted, localTypes)) {
                        localUntrusted.add(val.name());
                    } else {
                        localUntrusted.remove(val.name());
                    }
                }
                case LetStmt let -> {
                    TypeExpr t = let.explicitType() != null ? resolveAlias(let.explicitType()) : inferExprType(let.initializer(), localTypes);
                    if (t != null) localTypes.put(let.name(), t);
                    if (isValidatedType(let.explicitType()) || isValidatedExpr(let.initializer())) {
                        localUntrusted.remove(let.name());
                    } else if (isUntrustedType(t) || isExprUntrusted(let.initializer(), localUntrusted, localTypes)) {
                        localUntrusted.add(let.name());
                    } else {
                        localUntrusted.remove(let.name());
                    }
                }
                case VarStmt varStmt -> {
                    TypeExpr t = varStmt.explicitType() != null ? resolveAlias(varStmt.explicitType()) : inferExprType(varStmt.initializer(), localTypes);
                    if (t != null) localTypes.put(varStmt.name(), t);
                    if (isUntrustedType(t) || isExprUntrusted(varStmt.initializer(), localUntrusted, localTypes)) {
                        localUntrusted.add(varStmt.name());
                    } else {
                        localUntrusted.remove(varStmt.name());
                    }
                }
                case AssignStmt assign -> {
                    if (isExprUntrusted(assign.value(), localUntrusted, localTypes)) {
                        localUntrusted.add(assign.name());
                    }
                }
                case ReturnStmt ret -> {
                    if (ret.value() != null && isExprUntrusted(ret.value(), localUntrusted, localTypes)) {
                        return true;
                    }
                }
                case ExprStmt es -> {
                    if (es.expr() instanceof IfExpr || es.expr() instanceof MatchExpr || es.expr() instanceof BlockExpr) {
                        if (isExprUntrusted(es.expr(), localUntrusted, localTypes)) {
                            return true;
                        }
                    }
                }
                default -> {}
            }
        }
        return block.tailExpr() != null && isExprUntrusted(block.tailExpr(), localUntrusted, localTypes);
    }
}
