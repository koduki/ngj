package ngj.typechecker;

import ngj.ast.*;
import ngj.diagnostic.*;
import ngj.typechecker.refinement.EGraph;
import ngj.typechecker.refinement.IntervalDomain;
import ngj.typechecker.refinement.IntervalDomain.BitWidth;
import ngj.typechecker.refinement.IntervalDomain.Interval;

import java.math.BigInteger;
import java.util.*;

/**
 * Phase 2 Refinement & Range Type Checker (REF-01):
 * - TypeAliasDecl expansion with cycle detection (NGJ-TYPE-026)
 * - Predicate purity enforcement (NGJ-REF-002)
 * - Tier 1 deterministic solving via IntervalDomain + EGraph
 * - Guard narrowing (if/else, early-return, match) and ranger-style while monotonicity
 * - refine::<T>(expr) validation (NGJ-REF-003) and constructor/assignment refinement checks (NGJ-REF-001)
 */
public final class RefinementChecker {
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, TypeAliasDecl> typeAliases = new HashMap<>();
    private final Map<String, FnDecl> fnDecls = new HashMap<>();
    private final Map<String, RecordDecl> recordDecls = new HashMap<>();
    private final Map<String, DataDecl> dataDecls = new HashMap<>();
    private final Map<String, EnumDecl> enumDecls = new HashMap<>();
    private final Set<String> secretVars = new HashSet<>();
    private int obligationCounter = 0;

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public Map<String, TypeAliasDecl> getTypeAliases() {
        return typeAliases;
    }

    public void checkAll(List<CompilationUnit> units) {
        typeAliases.clear();
        fnDecls.clear();
        recordDecls.clear();
        dataDecls.clear();
        enumDecls.clear();
        secretVars.clear();
        obligationCounter = 0;

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case TypeAliasDecl alias -> typeAliases.put(alias.name(), alias);
                    case FnDecl fn -> fnDecls.put(fn.name(), fn);
                    case RecordDecl rec -> recordDecls.put(rec.name(), rec);
                    case DataDecl data -> dataDecls.put(data.name(), data);
                    case EnumDecl en -> enumDecls.put(en.name(), en);
                    default -> {}
                }
            }
        }

        // Check type alias cycles and predicate purity
        for (var alias : typeAliases.values()) {
            expandType(new SimpleTypeExpr(alias.name(), alias.span()), new LinkedHashSet<>(), alias.span());
            checkTypeRefinements(alias.targetType(), Set.of());
        }

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case RecordDecl rec -> checkRecordDecl(rec);
                    case DataDecl data -> {
                        for (var f : data.fields()) {
                            checkTypeRefinements(f.type(), Set.of());
                        }
                    }
                    case EnumDecl en -> {
                        for (var v : en.variants()) {
                            for (var f : v.fields()) {
                                checkTypeRefinements(f.type(), Set.of());
                            }
                        }
                    }
                    case FnDecl fn -> checkFnDecl(fn);
                    default -> {}
                }
            }
        }
    }

    /**
     * Expands type aliases with cycle detection (NGJ-TYPE-026).
     */
    public TypeExpr expandType(TypeExpr type, Set<String> visiting, SourceSpan reportSpan) {
        if (type == null) return null;
        return switch (type) {
            case SimpleTypeExpr simple -> {
                var alias = typeAliases.get(simple.name());
                if (alias != null) {
                    if (!visiting.add(simple.name())) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-026",
                            "Cyclic type alias detected: " + simple.name(),
                            reportSpan != null ? reportSpan : simple.span()
                        ));
                        yield type;
                    }
                    TypeExpr expanded = expandType(alias.targetType(), visiting, reportSpan);
                    visiting.remove(simple.name());
                    yield expanded;
                }
                yield simple;
            }
            case GenericTypeExpr generic -> {
                var alias = typeAliases.get(generic.name());
                if (alias != null) {
                    if (!visiting.add(generic.name())) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-TYPE-026",
                            "Cyclic type alias detected: " + generic.name(),
                            reportSpan != null ? reportSpan : generic.span()
                        ));
                        yield type;
                    }
                    Map<String, TypeExpr> subst = new HashMap<>();
                    for (int i = 0; i < Math.min(alias.typeParams().size(), generic.typeArgs().size()); i++) {
                        subst.put(alias.typeParams().get(i), generic.typeArgs().get(i));
                    }
                    TypeExpr substituted = substituteTypeParams(alias.targetType(), subst);
                    TypeExpr expanded = expandType(substituted, visiting, reportSpan);
                    visiting.remove(generic.name());
                    yield expanded;
                }
                List<TypeExpr> expandedArgs = new ArrayList<>();
                for (var arg : generic.typeArgs()) {
                    expandedArgs.add(expandType(arg, visiting, reportSpan));
                }
                yield new GenericTypeExpr(generic.name(), expandedArgs, generic.span());
            }
            case CapabilityTypeExpr cap -> new CapabilityTypeExpr(
                cap.mode(), expandType(cap.inner(), visiting, reportSpan), cap.span());
            case RefinedTypeExpr ref -> new RefinedTypeExpr(
                ref.binderName(), expandType(ref.baseType(), visiting, reportSpan), ref.predicate(), ref.span());
            case RangeTypeExpr range -> new RangeTypeExpr(
                expandType(range.baseType(), visiting, reportSpan),
                range.lowerBound(), range.upperBound(), range.upperInclusive(), range.span());
            default -> type;
        };
    }

    private TypeExpr substituteTypeParams(TypeExpr type, Map<String, TypeExpr> subst) {
        if (type == null || subst.isEmpty()) return type;
        return switch (type) {
            case SimpleTypeExpr s -> subst.getOrDefault(s.name(), s);
            case GenericTypeExpr g -> {
                List<TypeExpr> args = new ArrayList<>();
                for (var a : g.typeArgs()) args.add(substituteTypeParams(a, subst));
                yield new GenericTypeExpr(g.name(), args, g.span());
            }
            case RefinedTypeExpr r -> new RefinedTypeExpr(
                r.binderName(), substituteTypeParams(r.baseType(), subst), r.predicate(), r.span());
            case RangeTypeExpr r -> new RangeTypeExpr(
                substituteTypeParams(r.baseType(), subst), r.lowerBound(), r.upperBound(), r.upperInclusive(), r.span());
            case CapabilityTypeExpr c -> new CapabilityTypeExpr(
                c.mode(), substituteTypeParams(c.inner(), subst), c.span());
            default -> type;
        };
    }

    private void checkRecordDecl(RecordDecl rec) {
        Set<String> mutVars = Set.of();
        for (var param : rec.params()) {
            checkTypeRefinements(param.type(), mutVars);
        }
    }

    private void checkFnDecl(FnDecl fn) {
        RefinementEnv env = new RefinementEnv();
        for (var param : fn.params()) {
            checkTypeRefinements(param.type(), env.mutableVars);
            TypeExpr expanded = expandType(param.type(), new LinkedHashSet<>(), param.span());
            recordSecretIfApplicable(param.name(), expanded);
            env.bind(param.name(), expanded, intervalForType(expanded, env), false);
        }
        checkTypeRefinements(fn.returnType(), env.mutableVars);
        TypeExpr retExpanded = expandType(fn.returnType(), new LinkedHashSet<>(), fn.returnType().span());
        if (fn.body() != null) {
            checkBlock(fn.body(), env, retExpanded);
        }
    }

    private void checkTypeRefinements(TypeExpr type, Set<String> mutableVars) {
        if (type == null) return;
        switch (type) {
            case RefinedTypeExpr ref -> {
                checkTypeRefinements(ref.baseType(), mutableVars);
                if (ref.predicate() != null) {
                    checkPredicatePurity(ref.predicate(), mutableVars, ref.span());
                }
            }
            case RangeTypeExpr range -> {
                checkTypeRefinements(range.baseType(), mutableVars);
                String baseName = baseTypeName(range.baseType());
                if (!baseName.equals("Int") && !baseName.equals("Long")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-REF-002",
                        "Range refinement base type must be Int or Long, got: " + baseName,
                        range.span()
                    ));
                }
                if (range.lowerBound() != null) {
                    checkPredicatePurity(range.lowerBound(), mutableVars, range.span());
                }
                if (range.upperBound() != null) {
                    checkPredicatePurity(range.upperBound(), mutableVars, range.span());
                }
            }
            case GenericTypeExpr gen -> {
                for (var arg : gen.typeArgs()) {
                    checkTypeRefinements(arg, mutableVars);
                }
            }
            case CapabilityTypeExpr cap -> checkTypeRefinements(cap.inner(), mutableVars);
            default -> {}
        }
    }

    private void checkPredicatePurity(Expr expr, Set<String> mutableVars, SourceSpan contextSpan) {
        if (expr == null) return;
        switch (expr) {
            case IdentExpr id -> {
                if (mutableVars.contains(id.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-REF-002",
                        "Refinement predicate cannot reference mutable 'var' '" + id.name() + "'",
                        id.span() != null ? id.span() : contextSpan
                    ));
                }
            }
            case BinaryExpr bin -> {
                checkPredicatePurity(bin.left(), mutableVars, contextSpan);
                checkPredicatePurity(bin.right(), mutableVars, contextSpan);
            }
            case UnaryExpr un -> checkPredicatePurity(un.operand(), mutableVars, contextSpan);
            case ParenExpr par -> checkPredicatePurity(par.inner(), mutableVars, contextSpan);
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr id) {
                    var fn = fnDecls.get(id.name());
                    if (fn != null && (!fn.effects().isEmpty() || !fn.captureBounds().isEmpty())) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-REF-002",
                            "Refinement predicate cannot call effectful or capturing function '" + id.name() + "'",
                            call.span() != null ? call.span() : contextSpan
                        ));
                    }
                } else if (call.callee() instanceof FieldAccessExpr fa) {
                    String m = fa.fieldName();
                    if (!m.equals("len") && !m.equals("length") && !m.equals("isEmpty")) {
                        // Allow pure length/size queries; check object purity
                    }
                    checkPredicatePurity(fa.object(), mutableVars, contextSpan);
                }
                for (var arg : call.args()) {
                    checkPredicatePurity(arg, mutableVars, contextSpan);
                }
            }
            case FieldAccessExpr fa -> checkPredicatePurity(fa.object(), mutableVars, contextSpan);
            case ScopeExpr _, ForkExpr _, ConsumeExpr _ -> diagnostics.add(Diagnostic.error(
                "NGJ-REF-002",
                "Refinement predicate must be pure and cannot use scope/fork/consume",
                expr.span() != null ? expr.span() : contextSpan
            ));
            case BlockExpr block -> {
                for (var st : block.statements()) {
                    if (st instanceof VarStmt || st instanceof AssignStmt) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-REF-002",
                            "Refinement predicate cannot contain mutable 'var' or assignments",
                            st.span()
                        ));
                    }
                }
                if (block.tailExpr() != null) {
                    checkPredicatePurity(block.tailExpr(), mutableVars, contextSpan);
                }
            }
            default -> {}
        }
    }

    private void checkBlock(BlockExpr block, RefinementEnv env, TypeExpr expectedReturnType) {
        if (block == null) return;
        for (var stmt : block.statements()) {
            checkStmt(stmt, env, expectedReturnType);
        }
        if (block.tailExpr() != null) {
            checkExpr(block.tailExpr(), env);
        }
    }

    private void checkStmt(Statement stmt, RefinementEnv env, TypeExpr expectedReturnType) {
        switch (stmt) {
            case ValStmt val -> {
                checkExpr(val.initializer(), env);
                TypeExpr target = val.explicitType() != null
                    ? expandType(val.explicitType(), new LinkedHashSet<>(), val.span())
                    : null;
                if (target != null) {
                    checkTypeRefinements(target, env.mutableVars);
                    verifyRefinementAssignment(target, val.initializer(), env, val.span());
                }
                Interval initInterval = evalInterval(val.initializer(), env);
                if (target != null) {
                    initInterval = initInterval.meet(intervalForType(target, env));
                }
                recordSecretIfApplicable(val.name(), target);
                env.bind(val.name(), target, initInterval, false);
                env.equate(val.name(), val.initializer());
            }
            case LetStmt let -> {
                checkExpr(let.initializer(), env);
                TypeExpr target = let.explicitType() != null
                    ? expandType(let.explicitType(), new LinkedHashSet<>(), let.span())
                    : null;
                if (target != null) {
                    checkTypeRefinements(target, env.mutableVars);
                    verifyRefinementAssignment(target, let.initializer(), env, let.span());
                }
                Interval initInterval = evalInterval(let.initializer(), env);
                recordSecretIfApplicable(let.name(), target);
                env.bind(let.name(), target, initInterval, false);
                env.equate(let.name(), let.initializer());
            }
            case VarStmt varStmt -> {
                checkExpr(varStmt.initializer(), env);
                TypeExpr target = varStmt.explicitType() != null
                    ? expandType(varStmt.explicitType(), new LinkedHashSet<>(), varStmt.span())
                    : null;
                if (target != null) {
                    checkTypeRefinements(target, env.mutableVars);
                    verifyRefinementAssignment(target, varStmt.initializer(), env, varStmt.span());
                }
                Interval initInterval = evalInterval(varStmt.initializer(), env);
                recordSecretIfApplicable(varStmt.name(), target);
                env.bind(varStmt.name(), target, initInterval, true);
            }
            case AssignStmt assign -> {
                checkExpr(assign.value(), env);
                TypeExpr targetType = env.types.get(assign.name());
                if (targetType != null) {
                    verifyRefinementAssignment(targetType, assign.value(), env, assign.span());
                }
                Interval valInterval = evalInterval(assign.value(), env);
                if (targetType != null) {
                    valInterval = valInterval.meet(intervalForType(targetType, env));
                }
                env.intervals.put(assign.name(), valInterval);
                env.diff.assignInterval(assign.name(), valInterval);
            }
            case ReturnStmt ret -> {
                if (ret.value() != null) {
                    checkExpr(ret.value(), env);
                    if (expectedReturnType != null) {
                        verifyRefinementAssignment(expectedReturnType, ret.value(), env, ret.span());
                    }
                }
            }
            case ExprStmt es -> checkExpr(es.expr(), env, expectedReturnType);
            case TupleDestructureStmt td -> checkExpr(td.initializer(), env);
            case InvariantStmt inv -> checkExpr(inv.condition(), env);
            case ProofStmt proof -> checkBlock(proof.body(), env.copy(), null);
            case AssumeStmt assume -> {
                checkExpr(assume.condition(), env);
                env.narrow(assume.condition(), true);
            }
        }
    }

    private void checkExpr(Expr expr, RefinementEnv env) {
        checkExpr(expr, env, null);
    }

    private void checkExpr(Expr expr, RefinementEnv env, TypeExpr expectedReturnType) {
        if (expr == null) return;
        switch (expr) {
            case IfExpr ifExpr -> {
                checkExpr(ifExpr.condition(), env);
                RefinementEnv thenEnv = env.copy();
                thenEnv.narrow(ifExpr.condition(), true);
                checkBlock(ifExpr.thenBranch(), thenEnv, expectedReturnType);

                RefinementEnv elseEnv = env.copy();
                elseEnv.narrow(ifExpr.condition(), false);
                if (ifExpr.elseBranch() != null) {
                    if (ifExpr.elseBranch() instanceof BlockExpr eb) {
                        checkBlock(eb, elseEnv, expectedReturnType);
                    } else {
                        checkExpr(ifExpr.elseBranch(), elseEnv);
                    }
                    boolean thenReturns = blockAlwaysReturns(ifExpr.thenBranch());
                    boolean elseReturns = exprAlwaysReturns(ifExpr.elseBranch());
                    if (thenReturns && !elseReturns) {
                        env.copyFrom(elseEnv);
                    } else if (!thenReturns && elseReturns) {
                        env.copyFrom(thenEnv);
                    } else if (!thenReturns && !elseReturns) {
                        env.joinFrom(thenEnv, elseEnv);
                    }
                } else if (blockAlwaysReturns(ifExpr.thenBranch())) {
                    env.narrow(ifExpr.condition(), false);
                }
            }
            case MatchExpr matchExpr -> {
                checkExpr(matchExpr.scrutinee(), env);
                String scrutineeVar = matchExpr.scrutinee() instanceof IdentExpr id ? id.name() : null;
                TypeExpr scrutineeType = scrutineeVar != null ? env.types.get(scrutineeVar) : null;
                for (var arm : matchExpr.arms()) {
                    RefinementEnv armEnv = bindMatchArm(arm.pattern(), scrutineeVar, scrutineeType, env);
                    checkExpr(arm.body(), armEnv);
                }
            }
            case WhileExpr whileExpr -> {
                // Ranger-style monotonicity analysis
                Map<String, Monotonicity> mono = analyzeLoopMonotonicity(whileExpr.body());
                Map<String, Interval> preLoop = new LinkedHashMap<>(env.intervals);
                for (var entry : mono.entrySet()) {
                    String varName = entry.getKey();
                    Interval pre = env.intervals.getOrDefault(varName, Interval.top());
                    BitWidth width = bitWidthOf(env.types.get(varName));
                    Interval widthMax = Interval.forWidth(width);
                    Interval widened = switch (entry.getValue()) {
                        case NON_DECREASING -> Interval.closed(pre.lower(), widthMax.upper());
                        case NON_INCREASING -> Interval.closed(widthMax.lower(), pre.upper());
                        case ARBITRARY -> widthMax;
                    };
                    env.intervals.put(varName, widened);
                }
                RefinementEnv bodyEnv = env.copy();
                bodyEnv.narrow(whileExpr.condition(), true);
                checkExpr(whileExpr.condition(), bodyEnv);
                checkBlock(whileExpr.body(), bodyEnv, null);
                env.narrow(whileExpr.condition(), false);
                // Preserve upper bound on NON_DECREASING loop index bounded by `i < limit`
                if (whileExpr.condition() instanceof BinaryExpr b && b.op() == BinaryExpr.BinaryOp.LT
                    && b.left() instanceof IdentExpr id
                    && mono.get(id.name()) == Monotonicity.NON_DECREASING) {
                    Interval limitInterval = evalInterval(b.right(), env);
                    Interval pre = preLoop.getOrDefault(id.name(), Interval.top());
                    if (limitInterval.upper() != null && pre.lower() != null
                        && pre.lower().compareTo(limitInterval.upper()) <= 0) {
                        Interval cur = env.intervals.getOrDefault(id.name(), Interval.top());
                        env.intervals.put(id.name(), Interval.closed(pre.lower(), limitInterval.upper()).meet(cur));
                    }
                }
            }
            case CallExpr call -> checkCallRefinement(call, env);
            case ConstructorExpr ctor -> {
                for (var f : ctor.fields()) {
                    checkExpr(f.value(), env);
                }
                checkConstructorRefinements(ctor.typePath(),
                    ctor.fields().stream().map(ConstructorExpr.FieldInit::value).toList(),
                    env, ctor.span());
            }
            case BlockExpr b -> checkBlock(b, env.copy(), null);
            case ScopeExpr s -> checkBlock(s.body(), env.copy(), null);
            case ForkExpr f -> checkBlock(f.body(), env.copy(), null);
            case BinaryExpr bin -> {
                checkExpr(bin.left(), env);
                checkExpr(bin.right(), env);
            }
            case UnaryExpr un -> checkExpr(un.operand(), env);
            case ParenExpr par -> checkExpr(par.inner(), env);
            case PropagateExpr prop -> checkExpr(prop.inner(), env);
            case ConsumeExpr c -> checkExpr(c.target(), env);
            case TupleExpr t -> {
                for (var e : t.elements()) checkExpr(e, env);
            }
            case FieldAccessExpr fa -> checkExpr(fa.object(), env);
            case ForExpr fe -> {
                checkExpr(fe.iterable(), env);
                checkBlock(fe.body(), env.copy(), null);
            }
            case LambdaExpr le -> checkBlock(le.body(), env.copy(), null);
            default -> {}
        }
    }

    private void checkCallRefinement(CallExpr call, RefinementEnv env) {
        for (var arg : call.args()) {
            checkExpr(arg, env);
        }
        if (call.callee() instanceof IdentExpr id) {
            String name = id.name();
            if (name.equals("refine")) {
                if (call.typeArgs().size() != 1 || call.args().size() != 1) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-REF-003",
                        "refine::<T>(expr) requires exactly 1 type argument and 1 value argument",
                        call.span()
                    ));
                    return;
                }
                TypeExpr targetType = expandType(call.typeArgs().get(0), new LinkedHashSet<>(), call.span());
                String targetBase = baseTypeName(targetType);
                String argBase = inferBaseTypeName(call.args().get(0), env);
                if (argBase != null && !targetBase.equals(argBase) && !targetBase.equals("_")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-REF-003",
                        "refine::<" + targetType + "> base type mismatch: expected " + targetBase + ", got " + argBase,
                        call.span(),
                        new ExpectedActual(targetBase),
                        new ExpectedActual(argBase)
                    ));
                }
                return;
            }

            var fn = fnDecls.get(name);
            if (fn != null && call.args().size() == fn.params().size()) {
                Map<String, Expr> paramSubst = new LinkedHashMap<>();
                for (int i = 0; i < fn.params().size(); i++) {
                    Param p = fn.params().get(i);
                    Expr arg = call.args().get(i);
                    TypeExpr paramType = substituteExprParams(
                        expandType(p.type(), new LinkedHashSet<>(), p.span()), paramSubst);
                    verifyRefinementAssignment(paramType, arg, env, arg.span());
                    paramSubst.put(p.name(), arg);
                }
                return;
            }

            checkConstructorRefinements(name, call.args(), env, call.span());
        }
    }

    private void checkConstructorRefinements(String typeName, List<Expr> args, RefinementEnv env, SourceSpan span) {
        var rec = recordDecls.get(typeName);
        if (rec != null && args.size() == rec.params().size()) {
            Map<String, Expr> paramSubst = new LinkedHashMap<>();
            for (int i = 0; i < rec.params().size(); i++) {
                Param p = rec.params().get(i);
                Expr arg = args.get(i);
                TypeExpr paramType = substituteExprParams(
                    expandType(p.type(), new LinkedHashSet<>(), p.span()), paramSubst);
                verifyRefinementAssignment(paramType, arg, env, arg.span() != null ? arg.span() : span);
                paramSubst.put(p.name(), arg);
            }
            return;
        }
        var data = dataDecls.get(typeName);
        if (data != null && args.size() == data.fields().size()) {
            for (int i = 0; i < data.fields().size(); i++) {
                var f = data.fields().get(i);
                Expr arg = args.get(i);
                TypeExpr fType = expandType(f.type(), new LinkedHashSet<>(), f.span());
                verifyRefinementAssignment(fType, arg, env, arg.span() != null ? arg.span() : span);
            }
        }
    }

    private void verifyRefinementAssignment(TypeExpr targetType, Expr valueExpr, RefinementEnv env, SourceSpan span) {
        if (targetType == null || valueExpr == null || valueExpr instanceof HoleExpr) {
            return;
        }
        TypeExpr expanded = expandType(targetType, new LinkedHashSet<>(), span);
        if (expanded instanceof RangeTypeExpr range) {
            Interval actual = evalInterval(valueExpr, env);
            Interval expectedOuter = intervalForRange(range, env);
            if (!expectedOuter.isEmpty() && !actual.isEmpty() && !actual.isSubsetOf(expectedOuter)) {
                emitRefinementFailure(expanded, valueExpr, actual, expectedOuter, env, span);
                return;
            }
            if (!expectedOuter.isEmpty() && actual.isUnbounded()) {
                emitRefinementFailure(expanded, valueExpr, actual, expectedOuter, env, span);
                return;
            }
            if (range.lowerBound() != null && !isLowerBoundProved(valueExpr, range.lowerBound(), env)) {
                Interval loI = evalInterval(range.lowerBound(), env);
                BigInteger guaranteedLo = loI.upper();
                if (guaranteedLo == null || actual.lower() == null || actual.lower().compareTo(guaranteedLo) < 0) {
                    emitRefinementFailure(expanded, valueExpr, actual, strictIntervalForRange(range, env), env, span);
                    return;
                }
            }
            if (range.upperBound() != null && !isUpperBoundProved(valueExpr, range.upperBound(), range.upperInclusive(), env)) {
                Interval hiI = evalInterval(range.upperBound(), env);
                BigInteger guaranteedHi = hiI.lower();
                if (guaranteedHi != null && !range.upperInclusive()) {
                    guaranteedHi = guaranteedHi.subtract(BigInteger.ONE);
                }
                if (guaranteedHi == null || actual.upper() == null || actual.upper().compareTo(guaranteedHi) > 0) {
                    emitRefinementFailure(expanded, valueExpr, actual, strictIntervalForRange(range, env), env, span);
                    return;
                }
            }
        } else if (expanded instanceof RefinedTypeExpr ref) {
            Interval actual = evalInterval(valueExpr, env);
            Interval expected = intervalForRefined(ref, env);
            if (!expected.isEmpty() && (actual.isUnbounded() || !actual.isSubsetOf(expected))) {
                emitRefinementFailure(expanded, valueExpr, actual, expected, env, span);
                return;
            }
            if (ref.predicate() != null && !isIntervalRepresentable(ref.binderName(), ref.predicate())) {
                if (hasMatchingRefinedType(valueExpr, ref, env)) {
                    return;
                }
                Boolean holds = evalPredicateHolds(ref.binderName(), valueExpr, ref.predicate(), env);
                if (!Boolean.TRUE.equals(holds)) {
                    emitRefinementFailure(expanded, valueExpr, actual, expected, env, span);
                }
            }
        }
    }

    private boolean isLowerBoundProved(Expr actualExpr, Expr boundExpr, RefinementEnv env) {
        if (actualExpr instanceof IdentExpr ax && boundExpr instanceof IdentExpr bx) {
            var diffBound = env.diff.getDifferenceBound(bx.name(), ax.name());
            if (diffBound.isPresent() && diffBound.get().signum() <= 0) {
                return true;
            }
        }
        Integer ac = env.toEClass(actualExpr);
        Integer bc = env.toEClass(boundExpr);
        return ac != null && bc != null && env.egraph.areEqual(ac, bc);
    }

    private boolean isUpperBoundProved(Expr actualExpr, Expr boundExpr, boolean inclusive, RefinementEnv env) {
        if (actualExpr instanceof IdentExpr ax && boundExpr instanceof IdentExpr bx) {
            var diffBound = env.diff.getDifferenceBound(ax.name(), bx.name());
            if (diffBound.isPresent()) {
                int limit = inclusive ? 0 : -1;
                if (diffBound.get().compareTo(BigInteger.valueOf(limit)) <= 0) {
                    return true;
                }
            }
        }
        if (inclusive) {
            Integer ac = env.toEClass(actualExpr);
            Integer bc = env.toEClass(boundExpr);
            if (ac != null && bc != null && env.egraph.areEqual(ac, bc)) {
                return true;
            }
        }
        return false;
    }

    private Interval strictIntervalForRange(RangeTypeExpr range, RefinementEnv env) {
        BigInteger lo = null;
        if (range.lowerBound() != null) {
            Interval loI = evalInterval(range.lowerBound(), env);
            lo = loI.upper();
        }
        BigInteger hi = null;
        if (range.upperBound() != null) {
            Interval hiI = evalInterval(range.upperBound(), env);
            if (hiI.lower() != null) {
                hi = range.upperInclusive() ? hiI.lower() : hiI.lower().subtract(BigInteger.ONE);
            }
        }
        BitWidth width = bitWidthOf(range.baseType());
        return Interval.closed(lo, hi).clamp(width);
    }

    private void emitRefinementFailure(
        TypeExpr targetType,
        Expr valueExpr,
        Interval actual,
        Interval expected,
        RefinementEnv env,
        SourceSpan span
    ) {
        obligationCounter++;
        String obId = "ob-ref-" + obligationCounter;
        String snippet = valueExpr.toString();
        Map<String, String> ceState = new LinkedHashMap<>();
        if (valueExpr instanceof IdentExpr id) {
            ceState.put(id.name(), actual.toString());
        } else {
            ceState.put("value", actual.toString());
        }
        for (var e : env.intervals.entrySet()) {
            if (!e.getValue().isUnbounded()) {
                ceState.putIfAbsent(e.getKey(), e.getValue().toString());
            }
        }
        Map<String, String> redactedCe = SecretRedactor.redactState(ceState, secretVars);
        var diagnosis = new SolverDiagnosis("counterexample_found", span, redactedCe);
        var trace = List.of(
            new DerivationStep(1, span, snippet,
                "Evaluated interval " + actual + " is not contained in required " + expected)
        );
        var diag = new Diagnostic(
            "NGJ-REF-001",
            Severity.ERROR,
            Certainty.DEFINITE,
            "Refinement check failed: value " + snippet + " with interval " + actual
                + " may violate constraint " + targetType,
            span,
            null,
            new ExpectedActual(targetType.toString()),
            new ExpectedActual(actual.toString()),
            List.of(),
            List.of(new Suggestion("add_refine_or_guard",
                "Add a runtime guard or use refine::<" + targetType + ">(" + snippet + ")",
                Suggestion.REQUIRES_REVIEW)),
            snippet,
            trace,
            obId,
            diagnosis
        );
        diagnostics.add(SecretRedactor.redactDiagnostic(diag, secretVars));
    }

    private Interval intervalForType(TypeExpr type, RefinementEnv env) {
        if (type == null) return Interval.top();
        return switch (type) {
            case SimpleTypeExpr s -> switch (s.name()) {
                case "Int" -> Interval.int32();
                case "Long" -> Interval.long64();
                default -> Interval.top();
            };
            case RangeTypeExpr r -> intervalForRange(r, env);
            case RefinedTypeExpr ref -> intervalForRefined(ref, env);
            case CapabilityTypeExpr cap -> intervalForType(cap.inner(), env);
            default -> Interval.top();
        };
    }

    private Interval intervalForRange(RangeTypeExpr range, RefinementEnv env) {
        Interval loI = range.lowerBound() != null ? evalInterval(range.lowerBound(), env) : Interval.top();
        BigInteger lo = loI.lower();
        BigInteger hi = null;
        if (range.upperBound() != null) {
            Interval hiI = evalInterval(range.upperBound(), env);
            if (hiI.upper() != null) {
                hi = range.upperInclusive() ? hiI.upper() : hiI.upper().subtract(BigInteger.ONE);
            }
        }
        BitWidth width = bitWidthOf(range.baseType());
        Interval bounded = Interval.closed(lo, hi);
        return bounded.clamp(width);
    }

    private Interval intervalForRefined(RefinedTypeExpr ref, RefinementEnv env) {
        Interval base = intervalForType(ref.baseType(), env);
        if (ref.predicate() == null || ref.binderName() == null) {
            return base;
        }
        RefinementEnv tmp = env.copy();
        tmp.intervals.put(ref.binderName(), base);
        tmp.narrow(ref.predicate(), true);
        return tmp.intervals.getOrDefault(ref.binderName(), base);
    }

    private Interval evalInterval(Expr expr, RefinementEnv env) {
        if (expr == null) return Interval.top();
        return switch (expr) {
            case LiteralExpr lit -> {
                if (lit.kind() == LiteralExpr.LiteralKind.INT || lit.kind() == LiteralExpr.LiteralKind.LONG) {
                    long v = ((Number) lit.value()).longValue();
                    yield Interval.singleton(v);
                }
                yield Interval.top();
            }
            case IdentExpr id -> env.intervals.getOrDefault(id.name(), Interval.top());
            case ParenExpr p -> evalInterval(p.inner(), env);
            case UnaryExpr u -> {
                Interval op = evalInterval(u.operand(), env);
                yield u.op() == UnaryExpr.UnaryOp.NEG ? op.negate() : Interval.top();
            }
            case BinaryExpr b -> {
                Interval l = evalInterval(b.left(), env);
                Interval r = evalInterval(b.right(), env);
                yield switch (b.op()) {
                    case ADD -> l.add(r);
                    case SUB -> l.sub(r);
                    case MUL -> l.mul(r);
                    case DIV -> l.div(r);
                    case MOD -> l.rem(r);
                    default -> Interval.top();
                };
            }
            case CallExpr c -> {
                if (c.callee() instanceof FieldAccessExpr fa && fa.fieldName().equals("len")) {
                    yield Interval.atLeast(0L);
                }
                yield Interval.top();
            }
            case BlockExpr b -> {
                if (b.tailExpr() != null) {
                    yield evalInterval(b.tailExpr(), env);
                }
                yield Interval.top();
            }
            case IfExpr i -> {
                RefinementEnv thenEnv = env.copy();
                thenEnv.narrow(i.condition(), true);
                Interval thenI = evalInterval(i.thenBranch(), thenEnv);
                if (i.elseBranch() != null) {
                    RefinementEnv elseEnv = env.copy();
                    elseEnv.narrow(i.condition(), false);
                    Interval elseI = evalInterval(i.elseBranch(), elseEnv);
                    yield thenI.join(elseI);
                }
                yield thenI;
            }
            case MatchExpr m -> {
                String scrutineeVar = m.scrutinee() instanceof IdentExpr id ? id.name() : null;
                TypeExpr scrutineeType = scrutineeVar != null ? env.types.get(scrutineeVar) : null;
                Interval joined = Interval.empty();
                for (var arm : m.arms()) {
                    RefinementEnv armEnv = bindMatchArm(arm.pattern(), scrutineeVar, scrutineeType, env);
                    joined = joined.join(evalInterval(arm.body(), armEnv));
                }
                yield m.arms().isEmpty() ? Interval.top() : joined;
            }
            default -> Interval.top();
        };
    }

    private RefinementEnv bindMatchArm(Pattern pattern, String scrutineeVar, TypeExpr scrutineeType, RefinementEnv env) {
        RefinementEnv armEnv = env.copy();
        if (scrutineeVar != null && pattern instanceof LiteralPattern lit) {
            Object val = lit.literal().value();
            if (val instanceof Integer i) {
                armEnv.intervals.put(scrutineeVar, Interval.singleton(i));
            } else if (val instanceof Long l) {
                armEnv.intervals.put(scrutineeVar, Interval.singleton(l));
            }
        } else if (scrutineeVar != null && pattern instanceof BindingPattern bp) {
            armEnv.bind(bp.name(), scrutineeType,
                env.intervals.getOrDefault(scrutineeVar, Interval.top()), false);
        } else if (pattern instanceof ConstructorPattern cp) {
            String path = cp.typePath();
            String shortName = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            String enumName = scrutineeType != null ? baseTypeName(scrutineeType) : null;
            EnumDecl enumDecl = enumName != null ? enumDecls.get(enumName) : null;
            if (enumDecl != null) {
                for (var v : enumDecl.variants()) {
                    if (v.name().equals(shortName)) {
                        for (int i = 0; i < Math.min(cp.fields().size(), v.fields().size()); i++) {
                            var pf = cp.fields().get(i);
                            var vf = v.fields().get(i);
                            String bindName = pf.pattern() instanceof BindingPattern bp ? bp.name() : pf.name();
                            if (bindName != null && !bindName.isEmpty()) {
                                TypeExpr fType = expandType(vf.type(), new LinkedHashSet<>(), vf.span());
                                armEnv.bind(bindName, fType, intervalForType(fType, armEnv), false);
                            }
                        }
                        break;
                    }
                }
            }
        }
        return armEnv;
    }

    private boolean isIntervalRepresentable(String binder, Expr pred) {
        if (pred == null) return true;
        return switch (pred) {
            case ParenExpr p -> isIntervalRepresentable(binder, p.inner());
            case BinaryExpr b -> switch (b.op()) {
                case AND -> isIntervalRepresentable(binder, b.left()) && isIntervalRepresentable(binder, b.right());
                case LT, LE, GT, GE, EQ -> {
                    if (b.left() instanceof IdentExpr id && id.name().equals(binder)) {
                        yield isConstantNumberExpr(b.right());
                    }
                    if (b.right() instanceof IdentExpr id && id.name().equals(binder)) {
                        yield isConstantNumberExpr(b.left());
                    }
                    yield false;
                }
                default -> false;
            };
            default -> false;
        };
    }

    private boolean isConstantNumberExpr(Expr expr) {
        return switch (expr) {
            case LiteralExpr lit -> lit.kind() == LiteralExpr.LiteralKind.INT
                || lit.kind() == LiteralExpr.LiteralKind.LONG;
            case UnaryExpr u when u.op() == UnaryExpr.UnaryOp.NEG -> isConstantNumberExpr(u.operand());
            case ParenExpr p -> isConstantNumberExpr(p.inner());
            default -> false;
        };
    }

    private boolean hasMatchingRefinedType(Expr valueExpr, RefinedTypeExpr targetRef, RefinementEnv env) {
        if (!(valueExpr instanceof IdentExpr id)) return false;
        TypeExpr actualType = env.types.get(id.name());
        if (actualType == null) return false;
        TypeExpr expandedActual = expandType(actualType, new LinkedHashSet<>(), valueExpr.span());
        if (!(expandedActual instanceof RefinedTypeExpr actualRef)) return false;
        if (actualRef.predicate() == null || targetRef.predicate() == null) return false;
        Expr actualNorm = substituteExpr(actualRef.predicate(),
            Map.of(actualRef.binderName(), new IdentExpr(targetRef.binderName(), null)));
        return actualNorm.toString().equals(targetRef.predicate().toString());
    }

    private Boolean evalPredicateHolds(String binder, Expr valueExpr, Expr pred, RefinementEnv env) {
        if (pred == null) return Boolean.TRUE;
        return switch (pred) {
            case ParenExpr p -> evalPredicateHolds(binder, valueExpr, p.inner(), env);
            case UnaryExpr u when u.op() == UnaryExpr.UnaryOp.NOT -> {
                Boolean inner = evalPredicateHolds(binder, valueExpr, u.operand(), env);
                yield inner != null ? !inner : null;
            }
            case BinaryExpr b -> switch (b.op()) {
                case AND -> {
                    Boolean l = evalPredicateHolds(binder, valueExpr, b.left(), env);
                    Boolean r = evalPredicateHolds(binder, valueExpr, b.right(), env);
                    if (Boolean.FALSE.equals(l) || Boolean.FALSE.equals(r)) yield Boolean.FALSE;
                    if (Boolean.TRUE.equals(l) && Boolean.TRUE.equals(r)) yield Boolean.TRUE;
                    yield null;
                }
                case OR -> {
                    Boolean l = evalPredicateHolds(binder, valueExpr, b.left(), env);
                    Boolean r = evalPredicateHolds(binder, valueExpr, b.right(), env);
                    if (Boolean.TRUE.equals(l) || Boolean.TRUE.equals(r)) yield Boolean.TRUE;
                    if (Boolean.FALSE.equals(l) && Boolean.FALSE.equals(r)) yield Boolean.FALSE;
                    yield null;
                }
                case EQ, NE, LT, LE, GT, GE -> {
                    Expr lhsExpr = substituteExpr(b.left(), Map.of(binder, valueExpr));
                    Expr rhsExpr = substituteExpr(b.right(), Map.of(binder, valueExpr));
                    if (b.op() == BinaryExpr.BinaryOp.EQ) {
                        Integer lc = env.toEClass(lhsExpr);
                        Integer rc = env.toEClass(rhsExpr);
                        if (lc != null && rc != null && env.egraph.areEqual(lc, rc)) {
                            yield Boolean.TRUE;
                        }
                    }
                    Interval lv = evalInterval(lhsExpr, env);
                    Interval rv = evalInterval(rhsExpr, env);
                    if (lv.isEmpty() || rv.isEmpty()) yield null;
                    yield switch (b.op()) {
                        case EQ -> {
                            if (lv.isSingleton() && rv.isSingleton()) {
                                yield lv.lower().equals(rv.lower());
                            }
                            yield !lv.intersects(rv) ? Boolean.FALSE : null;
                        }
                        case NE -> {
                            if (!lv.intersects(rv)) yield Boolean.TRUE;
                            if (lv.isSingleton() && rv.isSingleton() && lv.lower().equals(rv.lower())) {
                                yield Boolean.FALSE;
                            }
                            yield null;
                        }
                        case LT -> {
                            if (lv.upper() != null && rv.lower() != null && lv.upper().compareTo(rv.lower()) < 0) {
                                yield Boolean.TRUE;
                            }
                            if (lv.lower() != null && rv.upper() != null && lv.lower().compareTo(rv.upper()) >= 0) {
                                yield Boolean.FALSE;
                            }
                            yield null;
                        }
                        case LE -> {
                            if (lv.upper() != null && rv.lower() != null && lv.upper().compareTo(rv.lower()) <= 0) {
                                yield Boolean.TRUE;
                            }
                            if (lv.lower() != null && rv.upper() != null && lv.lower().compareTo(rv.upper()) > 0) {
                                yield Boolean.FALSE;
                            }
                            yield null;
                        }
                        case GT -> {
                            if (lv.lower() != null && rv.upper() != null && lv.lower().compareTo(rv.upper()) > 0) {
                                yield Boolean.TRUE;
                            }
                            if (lv.upper() != null && rv.lower() != null && lv.upper().compareTo(rv.lower()) <= 0) {
                                yield Boolean.FALSE;
                            }
                            yield null;
                        }
                        case GE -> {
                            if (lv.lower() != null && rv.upper() != null && lv.lower().compareTo(rv.upper()) >= 0) {
                                yield Boolean.TRUE;
                            }
                            if (lv.upper() != null && rv.lower() != null && lv.upper().compareTo(rv.lower()) < 0) {
                                yield Boolean.FALSE;
                            }
                            yield null;
                        }
                        default -> null;
                    };
                }
                default -> null;
            };
            default -> null;
        };
    }

    private TypeExpr substituteExprParams(TypeExpr type, Map<String, Expr> subst) {
        if (type == null || subst.isEmpty()) return type;
        return switch (type) {
            case RangeTypeExpr r -> new RangeTypeExpr(
                r.baseType(),
                substituteExpr(r.lowerBound(), subst),
                substituteExpr(r.upperBound(), subst),
                r.upperInclusive(),
                r.span()
            );
            case RefinedTypeExpr r -> new RefinedTypeExpr(
                r.binderName(), r.baseType(), substituteExpr(r.predicate(), subst), r.span());
            default -> type;
        };
    }

    private Expr substituteExpr(Expr expr, Map<String, Expr> subst) {
        if (expr == null) return null;
        return switch (expr) {
            case IdentExpr id -> subst.getOrDefault(id.name(), id);
            case BinaryExpr b -> new BinaryExpr(
                substituteExpr(b.left(), subst), b.op(), substituteExpr(b.right(), subst), b.span());
            case UnaryExpr u -> new UnaryExpr(u.op(), substituteExpr(u.operand(), subst), u.span());
            case ParenExpr p -> new ParenExpr(substituteExpr(p.inner(), subst), p.span());
            case CallExpr c -> new CallExpr(
                substituteExpr(c.callee(), subst),
                c.typeArgs(),
                c.args().stream().map(a -> substituteExpr(a, subst)).toList(),
                c.span());
            case FieldAccessExpr fa -> new FieldAccessExpr(
                substituteExpr(fa.object(), subst), fa.fieldName(), fa.span());
            default -> expr;
        };
    }

    private enum Monotonicity { NON_DECREASING, NON_INCREASING, ARBITRARY }

    private Map<String, Monotonicity> analyzeLoopMonotonicity(BlockExpr body) {
        Map<String, Monotonicity> result = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        if (body == null) return result;
        collectLoopWrites(body, result, aliases);
        return result;
    }

    private void collectLoopWrites(BlockExpr block, Map<String, Monotonicity> result, Map<String, String> aliases) {
        for (var stmt : block.statements()) {
            switch (stmt) {
                case ValStmt v -> {
                    if (v.initializer() instanceof IdentExpr id) {
                        aliases.put(v.name(), aliases.getOrDefault(id.name(), id.name()));
                    }
                }
                case LetStmt l -> {
                    if (l.initializer() instanceof IdentExpr id) {
                        aliases.put(l.name(), aliases.getOrDefault(id.name(), id.name()));
                    }
                }
                case AssignStmt assign -> {
                    Monotonicity m = classifyUpdate(assign.name(), assign.value(), aliases);
                    result.merge(assign.name(), m, (a, b) -> a == b ? a : Monotonicity.ARBITRARY);
                }
                case ExprStmt es -> collectExprWrites(es.expr(), result, aliases);
                default -> {}
            }
        }
        if (block.tailExpr() != null) {
            collectExprWrites(block.tailExpr(), result, aliases);
        }
    }

    private void collectExprWrites(Expr expr, Map<String, Monotonicity> result, Map<String, String> aliases) {
        if (expr instanceof IfExpr ifE) {
            collectLoopWrites(ifE.thenBranch(), result, aliases);
            if (ifE.elseBranch() instanceof BlockExpr eb) collectLoopWrites(eb, result, aliases);
            else if (ifE.elseBranch() != null) collectExprWrites(ifE.elseBranch(), result, aliases);
        } else if (expr instanceof BlockExpr b) {
            collectLoopWrites(b, result, aliases);
        }
    }

    private Monotonicity classifyUpdate(String varName, Expr rhs, Map<String, String> aliases) {
        if (rhs instanceof BinaryExpr b && b.left() instanceof IdentExpr id) {
            String lhsRoot = aliases.getOrDefault(id.name(), id.name());
            if (lhsRoot.equals(varName) && b.right() instanceof LiteralExpr lit && lit.value() instanceof Number n) {
                long delta = n.longValue();
                if (b.op() == BinaryExpr.BinaryOp.ADD && delta >= 0) return Monotonicity.NON_DECREASING;
                if (b.op() == BinaryExpr.BinaryOp.SUB && delta >= 0) return Monotonicity.NON_INCREASING;
            }
        }
        return Monotonicity.ARBITRARY;
    }

    private String baseTypeName(TypeExpr t) {
        if (t == null) return "";
        return switch (t) {
            case SimpleTypeExpr s -> s.name();
            case GenericTypeExpr g -> g.name();
            case RangeTypeExpr r -> baseTypeName(r.baseType());
            case RefinedTypeExpr r -> baseTypeName(r.baseType());
            case CapabilityTypeExpr c -> baseTypeName(c.inner());
            case UnitTypeExpr _ -> "Unit";
            default -> t.toString();
        };
    }

    private String inferBaseTypeName(Expr expr, RefinementEnv env) {
        return switch (expr) {
            case LiteralExpr lit -> switch (lit.kind()) {
                case INT -> "Int";
                case LONG -> "Long";
                case BOOL -> "Bool";
                case STRING -> "String";
                case UNIT -> "Unit";
            };
            case IdentExpr id -> env.types.containsKey(id.name()) ? baseTypeName(env.types.get(id.name())) : null;
            case ParenExpr p -> inferBaseTypeName(p.inner(), env);
            case BinaryExpr b -> switch (b.op()) {
                case EQ, NE, LT, LE, GT, GE, AND, OR -> "Bool";
                default -> inferBaseTypeName(b.left(), env);
            };
            default -> null;
        };
    }

    private BitWidth bitWidthOf(TypeExpr t) {
        String base = baseTypeName(t);
        if (base.equals("Int")) return BitWidth.INT32;
        if (base.equals("Long")) return BitWidth.LONG64;
        return BitWidth.UNBOUNDED;
    }

    private void recordSecretIfApplicable(String name, TypeExpr t) {
        if (SecretRedactor.isSecretVariableName(name)) {
            secretVars.add(name);
        }
        if (t != null && baseTypeName(t).equals("Secret")) {
            secretVars.add(name);
        }
    }

    private boolean blockAlwaysReturns(BlockExpr block) {
        if (block == null) return false;
        for (var st : block.statements()) {
            if (st instanceof ReturnStmt) return true;
        }
        return block.tailExpr() != null && exprAlwaysReturns(block.tailExpr());
    }

    private boolean exprAlwaysReturns(Expr expr) {
        return switch (expr) {
            case BlockExpr b -> blockAlwaysReturns(b);
            case IfExpr i -> i.elseBranch() != null
                && blockAlwaysReturns(i.thenBranch())
                && exprAlwaysReturns(i.elseBranch());
            default -> false;
        };
    }

    private final class RefinementEnv {
        final Map<String, TypeExpr> types = new LinkedHashMap<>();
        final Map<String, Interval> intervals = new LinkedHashMap<>();
        final Set<String> mutableVars = new LinkedHashSet<>();
        EGraph egraph = new EGraph();
        final Map<String, Integer> varClasses = new LinkedHashMap<>();
        IntervalDomain diff = new IntervalDomain();

        RefinementEnv copy() {
            RefinementEnv c = new RefinementEnv();
            c.types.putAll(this.types);
            c.intervals.putAll(this.intervals);
            c.mutableVars.addAll(this.mutableVars);
            c.egraph = this.egraph.copy();
            c.varClasses.putAll(this.varClasses);
            c.diff = this.diff.copy();
            return c;
        }

        void copyFrom(RefinementEnv other) {
            this.types.clear();
            this.types.putAll(other.types);
            this.intervals.clear();
            this.intervals.putAll(other.intervals);
            this.mutableVars.clear();
            this.mutableVars.addAll(other.mutableVars);
            this.egraph = other.egraph.copy();
            this.varClasses.clear();
            this.varClasses.putAll(other.varClasses);
            this.diff = other.diff.copy();
        }

        void joinFrom(RefinementEnv a, RefinementEnv b) {
            for (var key : new LinkedHashSet<>(this.intervals.keySet())) {
                Interval ia = a.intervals.getOrDefault(key, Interval.top());
                Interval ib = b.intervals.getOrDefault(key, Interval.top());
                this.intervals.put(key, ia.join(ib));
            }
            this.diff = a.diff.join(b.diff);
        }

        void bind(String name, TypeExpr type, Interval interval, boolean isMutable) {
            if (type != null) types.put(name, type);
            Interval effective = interval != null ? interval : Interval.top();
            intervals.put(name, effective);
            if (isMutable) mutableVars.add(name);
            varClasses.put(name, egraph.addVar(name));
            diff.assignInterval(name, effective);
            if (type instanceof RangeTypeExpr range) {
                if (range.lowerBound() instanceof IdentExpr loId) {
                    diff.addSymbolicLowerBound(name, loId.name(), BigInteger.ZERO);
                }
                if (range.upperBound() instanceof IdentExpr hiId) {
                    diff.addSymbolicUpperBound(name, hiId.name(),
                        range.upperInclusive() ? BigInteger.ZERO : BigInteger.ONE.negate());
                }
            }
        }

        void equate(String varName, Expr expr) {
            Integer lhs = varClasses.get(varName);
            Integer rhs = toEClass(expr);
            if (lhs != null && rhs != null) {
                egraph.union(lhs, rhs);
            }
            if (expr instanceof IdentExpr rhsId) {
                diff.addDifferenceBound(varName, rhsId.name(), BigInteger.ZERO);
                diff.addDifferenceBound(rhsId.name(), varName, BigInteger.ZERO);
            }
        }

        private Integer toEClass(Expr expr) {
            if (expr == null) return null;
            return switch (expr) {
                case IdentExpr id -> varClasses.computeIfAbsent(id.name(), egraph::addVar);
                case LiteralExpr lit -> {
                    if (lit.value() instanceof Number n) yield egraph.addConst(n.longValue());
                    if (lit.value() instanceof Boolean b) yield egraph.addConst(b);
                    yield null;
                }
                case ParenExpr p -> toEClass(p.inner());
                case BinaryExpr b -> {
                    Integer l = toEClass(b.left());
                    Integer r = toEClass(b.right());
                    if (l != null && r != null) {
                        if (b.op() == BinaryExpr.BinaryOp.ADD || b.op() == BinaryExpr.BinaryOp.MUL) {
                            yield egraph.addCommutativeApp(b.op().name(), l, r);
                        }
                        yield egraph.addApp(b.op().name(), l, r);
                    }
                    yield null;
                }
                case CallExpr c -> {
                    if (c.callee() instanceof IdentExpr id) {
                        int[] argClasses = new int[c.args().size()];
                        for (int i = 0; i < c.args().size(); i++) {
                            Integer ac = toEClass(c.args().get(i));
                            if (ac == null) yield null;
                            argClasses[i] = ac;
                        }
                        yield egraph.addApp(id.name(), argClasses);
                    }
                    yield null;
                }
                default -> null;
            };
        }

        void narrow(Expr cond, boolean truth) {
            if (cond == null) return;
            switch (cond) {
                case ParenExpr p -> narrow(p.inner(), truth);
                case UnaryExpr u when u.op() == UnaryExpr.UnaryOp.NOT -> narrow(u.operand(), !truth);
                case BinaryExpr b -> {
                    if (truth && b.op() == BinaryExpr.BinaryOp.AND) {
                        narrow(b.left(), true);
                        narrow(b.right(), true);
                        return;
                    }
                    if (!truth && b.op() == BinaryExpr.BinaryOp.OR) {
                        narrow(b.left(), false);
                        narrow(b.right(), false);
                        return;
                    }
                    BinaryExpr.BinaryOp effOp = truth ? b.op() : negateOp(b.op());
                    if (effOp == null) return;
                    if (effOp == BinaryExpr.BinaryOp.EQ) {
                        Integer lc = toEClass(b.left());
                        Integer rc = toEClass(b.right());
                        if (lc != null && rc != null) {
                            egraph.union(lc, rc);
                        }
                    }
                    if (b.left() instanceof IdentExpr lx && b.right() instanceof IdentExpr rx) {
                        switch (effOp) {
                            case LT -> diff.addDifferenceBound(lx.name(), rx.name(), BigInteger.ONE.negate());
                            case LE -> diff.addDifferenceBound(lx.name(), rx.name(), BigInteger.ZERO);
                            case GT -> diff.addDifferenceBound(rx.name(), lx.name(), BigInteger.ONE.negate());
                            case GE -> diff.addDifferenceBound(rx.name(), lx.name(), BigInteger.ZERO);
                            case EQ -> {
                                diff.addDifferenceBound(lx.name(), rx.name(), BigInteger.ZERO);
                                diff.addDifferenceBound(rx.name(), lx.name(), BigInteger.ZERO);
                            }
                            default -> {}
                        }
                    }
                    if (b.left() instanceof IdentExpr id) {
                        Interval rhs = evalInterval(b.right(), this);
                        Interval cur = intervals.getOrDefault(id.name(), Interval.top());
                        intervals.put(id.name(), cur.meet(constraintInterval(effOp, rhs)));
                    } else if (b.right() instanceof IdentExpr id) {
                        Interval lhs = evalInterval(b.left(), this);
                        Interval cur = intervals.getOrDefault(id.name(), Interval.top());
                        BinaryExpr.BinaryOp flipped = flipOp(effOp);
                        if (flipped != null) {
                            intervals.put(id.name(), cur.meet(constraintInterval(flipped, lhs)));
                        }
                    }
                }
                default -> {}
            }
        }

        private BinaryExpr.BinaryOp negateOp(BinaryExpr.BinaryOp op) {
            return switch (op) {
                case EQ -> BinaryExpr.BinaryOp.NE;
                case NE -> BinaryExpr.BinaryOp.EQ;
                case LT -> BinaryExpr.BinaryOp.GE;
                case LE -> BinaryExpr.BinaryOp.GT;
                case GT -> BinaryExpr.BinaryOp.LE;
                case GE -> BinaryExpr.BinaryOp.LT;
                default -> null;
            };
        }

        private BinaryExpr.BinaryOp flipOp(BinaryExpr.BinaryOp op) {
            return switch (op) {
                case LT -> BinaryExpr.BinaryOp.GT;
                case LE -> BinaryExpr.BinaryOp.GE;
                case GT -> BinaryExpr.BinaryOp.LT;
                case GE -> BinaryExpr.BinaryOp.LE;
                case EQ -> BinaryExpr.BinaryOp.EQ;
                case NE -> BinaryExpr.BinaryOp.NE;
                default -> null;
            };
        }

        private Interval constraintInterval(BinaryExpr.BinaryOp op, Interval rhs) {
            if (rhs.isUnbounded()) return Interval.top();
            return switch (op) {
                case EQ -> rhs;
                case LT -> rhs.upper() != null ? Interval.lessThan(rhs.upper()) : Interval.top();
                case LE -> rhs.upper() != null ? Interval.atMost(rhs.upper()) : Interval.top();
                case GT -> rhs.lower() != null ? Interval.atLeast(rhs.lower().add(BigInteger.ONE)) : Interval.top();
                case GE -> rhs.lower() != null ? Interval.atLeast(rhs.lower()) : Interval.top();
                case NE -> {
                    if (rhs.isSingleton()) {
                        // Cannot represent hole in single interval unless at boundary
                        yield Interval.top();
                    }
                    yield Interval.top();
                }
                default -> Interval.top();
            };
        }
    }
}
