package ngj.typechecker;

import ngj.ast.*;
import ngj.diagnostic.*;
import ngj.verifier.*;

import java.util.*;

/**
 * Phase 4 Proof, Contract & Tier 2 SMT Checker (PROOF-01):
 * - Checks spec fn, spec predicate, requires, ensures, invariant, proof { ... }, lemma, assume(...)
 * - Handles ?proof_hole (NGJ-PROOF-001 with Certainty.INCOMPLETE_PROOF; rejected in proof-required profile)
 * - Enforces spec/ghost isolation (NGJ-PROOF-002)
 * - Runs Tier 2 SMT verification (--verify=smt) via PureJvmSmtSolver / ExternalZ3Solver (NGJ-VERIFY-001)
 */
public final class ProofChecker {
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, SpecFnDecl> specFns = new LinkedHashMap<>();
    private final Map<String, LemmaDecl> lemmas = new LinkedHashMap<>();
    private final Map<String, FnDecl> fnDecls = new LinkedHashMap<>();
    private final Map<String, TypeAliasDecl> typeAliases = new HashMap<>();
    private final Set<String> secretVars = new LinkedHashSet<>();

    private final String profile;
    private final String verifyMode;
    private final String smtBackend;

    public ProofChecker() {
        this("standard", "tier1", "jvm");
    }

    public ProofChecker(String profile, String verifyMode, String smtBackend) {
        this.profile = profile != null ? profile : "standard";
        this.verifyMode = verifyMode != null ? verifyMode : "tier1";
        this.smtBackend = smtBackend != null ? smtBackend : "jvm";
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public void checkAll(List<CompilationUnit> units) {
        specFns.clear();
        lemmas.clear();
        fnDecls.clear();
        typeAliases.clear();
        secretVars.clear();

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case SpecFnDecl sf -> specFns.put(sf.name(), sf);
                    case LemmaDecl lm -> lemmas.put(lm.name(), lm);
                    case FnDecl fn -> fnDecls.put(fn.name(), fn);
                    case TypeAliasDecl ta -> typeAliases.put(ta.name(), ta);
                    default -> {}
                }
            }
        }

        VcGenerator vcGen = new VcGenerator(specFns, typeAliases, fnDecls);

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case SpecFnDecl sf -> checkSpecFn(sf);
                    case LemmaDecl lm -> checkLemma(lm, vcGen);
                    case FnDecl fn -> checkFn(fn, vcGen);
                    default -> {}
                }
            }
        }
    }

    private TypeExpr resolveTypeAlias(TypeExpr type) {
        return resolveTypeAlias(type, new LinkedHashSet<>());
    }

    private TypeExpr resolveTypeAlias(TypeExpr type, Set<String> seen) {
        if (type == null) return null;
        if (type instanceof SimpleTypeExpr s && typeAliases.containsKey(s.name()) && seen.add(s.name())) {
            TypeExpr resolved = resolveTypeAlias(typeAliases.get(s.name()).targetType(), seen);
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
            TypeExpr resolved = resolveTypeAlias(substituted, seen);
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

    private void checkSpecFn(SpecFnDecl sf) {
        if (sf.body() != null) {
            checkBlockPurityAndHoles(sf.body(), true, Set.of());
        }
    }

    private void checkLemma(LemmaDecl lm, VcGenerator vcGen) {
        for (var c : lm.contracts()) {
            checkExprPurityAndHoles(c.condition(), true, Set.of());
        }
        if (lm.body() != null) {
            checkBlockPurityAndHoles(lm.body(), true, Set.of());
        }
        if ("smt".equals(verifyMode)) {
            verifyLemmaWithSmt(lm, vcGen);
        }
    }

    private void checkFn(FnDecl fn, VcGenerator vcGen) {
        for (var p : fn.params()) {
            if (SecretRedactor.isSecretVariableName(p.name()) || isSecretType(resolveTypeAlias(p.type()))) {
                secretVars.add(p.name());
            }
            checkNoMathIntInType(p.type(), p.span() != null ? p.span() : fn.span());
        }
        checkNoMathIntInType(fn.returnType(), fn.span());
        for (var c : fn.contracts()) {
            checkExprPurityAndHoles(c.condition(), true, Set.of());
        }
        if (fn.body() != null) {
            CallSiteCtx ctx = CallSiteCtx.forFn(fn, vcGen);
            checkBlock(fn.body(), false, ctx);
        }
        if ("smt".equals(verifyMode)) {
            verifyFnWithSmt(fn, vcGen);
        }
    }

    private void checkNoMathIntInType(TypeExpr type, SourceSpan span) {
        TypeExpr resolved = resolveTypeAlias(type);
        if (resolved == null) return;
        switch (resolved) {
            case SimpleTypeExpr s -> {
                if (s.name().equals("MathInt")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-PROOF-002",
                        "Ghost type 'MathInt' cannot be used in executable code signatures or bindings",
                        s.span() != null ? s.span() : span
                    ));
                }
            }
            case GenericTypeExpr g -> {
                if (g.name().equals("MathInt")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-PROOF-002",
                        "Ghost type 'MathInt' cannot be used in executable code signatures or bindings",
                        g.span() != null ? g.span() : span
                    ));
                }
                for (var arg : g.typeArgs()) {
                    checkNoMathIntInType(arg, span);
                }
            }
            case CapabilityTypeExpr c -> checkNoMathIntInType(c.inner(), span);
            case RefinedTypeExpr r -> checkNoMathIntInType(r.baseType(), span);
            case RangeTypeExpr r -> checkNoMathIntInType(r.baseType(), span);
            case FunctionTypeExpr f -> {
                for (var p : f.paramTypes()) checkNoMathIntInType(p, span);
                checkNoMathIntInType(f.returnType(), span);
            }
            case TupleTypeExpr t -> {
                for (var e : t.elements()) checkNoMathIntInType(e, span);
            }
            default -> {}
        }
    }

    private void checkBlock(BlockExpr block, boolean inProofContext, CallSiteCtx ctx) {
        if (block == null) return;
        for (var stmt : block.statements()) {
            switch (stmt) {
                case ValStmt v -> {
                    if (SecretRedactor.isSecretVariableName(v.name()) || isSecretType(v.explicitType())) {
                        secretVars.add(v.name());
                    }
                    if (!inProofContext) checkNoMathIntInType(v.explicitType(), v.span());
                    checkRuntimeOrProofExpr(v.initializer(), inProofContext, ctx);
                    if (!inProofContext && ctx != null) {
                        ctx.bindLocal(v.name(), v.explicitType(), v.initializer());
                    }
                }
                case LetStmt l -> {
                    if (!inProofContext) checkNoMathIntInType(l.explicitType(), l.span());
                    checkRuntimeOrProofExpr(l.initializer(), inProofContext, ctx);
                    if (!inProofContext && ctx != null) {
                        ctx.bindLocal(l.name(), l.explicitType(), l.initializer());
                    }
                }
                case VarStmt v -> {
                    if (!inProofContext) checkNoMathIntInType(v.explicitType(), v.span());
                    checkRuntimeOrProofExpr(v.initializer(), inProofContext, ctx);
                    if (!inProofContext && ctx != null) {
                        ctx.bindVar(v.name(), v.explicitType(), v.initializer());
                    }
                }
                case AssignStmt a -> {
                    checkRuntimeOrProofExpr(a.value(), inProofContext, ctx);
                    if (!inProofContext && ctx != null) {
                        ctx.assignVar(a.name(), a.value());
                    }
                }
                case ReturnStmt r -> {
                    if (r.value() != null) checkRuntimeOrProofExpr(r.value(), inProofContext, ctx);
                }
                case ExprStmt es -> checkRuntimeOrProofExpr(es.expr(), inProofContext, ctx);
                case TupleDestructureStmt td -> {
                    if (!inProofContext) {
                        for (var t : td.explicitTypes()) checkNoMathIntInType(t, td.span());
                    }
                    checkRuntimeOrProofExpr(td.initializer(), inProofContext, ctx);
                    if (!inProofContext && ctx != null) {
                        ctx.bindTupleDestructure(td);
                    }
                }
                case InvariantStmt inv -> {
                    checkExprPurityAndHoles(inv.condition(), true, Set.of());
                    if (!inProofContext && ctx != null) {
                        SmtExpr invExpr = ctx.vcGen.translateExpr(inv.condition(), ctx.sorts, ctx.ssaEnv);
                        SmtExpr vc;
                        if (ctx.premises.isEmpty()) {
                            vc = invExpr;
                        } else {
                            SmtExpr conj = ctx.premises.size() == 1 ? ctx.premises.get(0) : new SmtExpr.And(ctx.premises);
                            vc = new SmtExpr.Implies(conj, invExpr);
                        }
                        if (runSmtAndReport(vc, "<invariant>", inv.span())) {
                            ctx.addAssumption(inv.condition());
                        }
                    }
                }
                case ProofStmt proof -> checkBlockPurityAndHoles(proof.body(), true, Set.of());
                case AssumeStmt assume -> {
                    checkExprPurityAndHoles(assume.condition(), true, Set.of());
                    if (!inProofContext && ctx != null) {
                        ctx.addAssumption(assume.condition());
                    }
                }
            }
        }
        if (block.tailExpr() != null) {
            checkRuntimeOrProofExpr(block.tailExpr(), inProofContext, ctx);
        }
    }

    private void checkRuntimeOrProofExpr(Expr expr, boolean inProofContext, CallSiteCtx ctx) {
        if (expr == null) return;
        switch (expr) {
            case HoleExpr hole -> {
                if (hole.isProofHole()) {
                    emitProofHole(hole);
                }
            }
            case IdentExpr id -> {
                if (!inProofContext && id.name().equals("MathInt")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-PROOF-002",
                        "Ghost type 'MathInt' cannot be referenced in executable runtime code",
                        id.span()
                    ));
                }
            }
            case CallExpr call -> {
                if (!inProofContext && call.callee() instanceof IdentExpr id) {
                    if (specFns.containsKey(id.name()) || lemmas.containsKey(id.name())) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-PROOF-002",
                            "Ghost/spec declaration '" + id.name() + "' cannot be called from executable runtime code",
                            call.span()
                        ));
                    }
                }
                checkRuntimeOrProofExpr(call.callee(), inProofContext, ctx);
                for (var arg : call.args()) checkRuntimeOrProofExpr(arg, inProofContext, ctx);
                if (!inProofContext && ctx != null && call.callee() instanceof IdentExpr id) {
                    verifyCalleePreconditions(call, id.name(), ctx);
                }
            }
            case BlockExpr b -> {
                if (!inProofContext && ctx != null) {
                    int origPremisesSize = ctx.premises.size();
                    CallSiteCtx inner = ctx.fork(null);
                    checkBlock(b, false, inner);
                    ctx.mergeBlockExit(inner, b, origPremisesSize);
                } else {
                    checkBlock(b, inProofContext, null);
                }
            }
            case IfExpr i -> {
                checkRuntimeOrProofExpr(i.condition(), inProofContext, ctx);
                if (!inProofContext && ctx != null) {
                    SmtExpr cond = ctx.vcGen.translateExecutableExpr(i.condition(), ctx.sorts, ctx.ssaEnv, ctx.longVars);
                    SmtExpr thenFact = !VcGenerator.containsUnsupported(cond) ? cond : null;
                    SmtExpr elseFact = thenFact != null ? new SmtExpr.Not(cond) : null;
                    checkBlock(i.thenBranch(), false, ctx.fork(thenFact));
                    if (i.elseBranch() != null) {
                        CallSiteCtx elseCtx = ctx.fork(elseFact);
                        if (i.elseBranch() instanceof BlockExpr eb) {
                            checkBlock(eb, false, elseCtx);
                        } else {
                            checkRuntimeOrProofExpr(i.elseBranch(), false, elseCtx);
                        }
                    }
                    Set<String> mutated = new LinkedHashSet<>();
                    collectAssignedVars(i.thenBranch(), mutated);
                    collectAssignedVars(i.elseBranch(), mutated);
                    ctx.havocVars(mutated);
                } else {
                    checkBlock(i.thenBranch(), inProofContext, null);
                    if (i.elseBranch() != null) checkRuntimeOrProofExpr(i.elseBranch(), inProofContext, null);
                }
            }
            case MatchExpr m -> {
                checkRuntimeOrProofExpr(m.scrutinee(), inProofContext, ctx);
                Set<String> mutated = new LinkedHashSet<>();
                for (var arm : m.arms()) {
                    CallSiteCtx armCtx = (!inProofContext && ctx != null) ? ctx.fork(null) : null;
                    if (armCtx != null) {
                        bindPatternVars(arm.pattern(), armCtx);
                    }
                    checkRuntimeOrProofExpr(arm.body(), inProofContext, armCtx);
                    if (!inProofContext && ctx != null) {
                        collectAssignedVars(arm.body(), mutated);
                    }
                }
                if (!inProofContext && ctx != null) {
                    ctx.havocVars(mutated);
                }
            }
            case WhileExpr w -> {
                if (!inProofContext && ctx != null) {
                    Set<String> mutated = new LinkedHashSet<>();
                    collectAssignedVars(w.condition(), mutated);
                    collectAssignedVars(w.body(), mutated);
                    CallSiteCtx headerCtx = ctx.fork(null);
                    headerCtx.havocVars(mutated);
                    checkRuntimeOrProofExpr(w.condition(), false, headerCtx);
                    SmtExpr cond = headerCtx.vcGen.translateExecutableExpr(w.condition(), headerCtx.sorts, headerCtx.ssaEnv, headerCtx.longVars);
                    if (!VcGenerator.containsUnsupported(cond)) {
                        headerCtx.premises.add(cond);
                    }
                    checkBlock(w.body(), false, headerCtx);
                    ctx.havocVars(mutated);
                } else {
                    checkRuntimeOrProofExpr(w.condition(), inProofContext, null);
                    checkBlock(w.body(), inProofContext, null);
                }
            }
            case ForExpr f -> {
                checkRuntimeOrProofExpr(f.iterable(), inProofContext, ctx);
                if (!inProofContext && ctx != null) {
                    Set<String> mutated = new LinkedHashSet<>();
                    collectAssignedVars(f.body(), mutated);
                    CallSiteCtx bodyCtx = ctx.fork(null);
                    bodyCtx.havocVars(mutated);
                    bodyCtx.freshSsa(f.varName(), SmtExpr.Sort.INT);
                    checkBlock(f.body(), false, bodyCtx);
                    ctx.havocVars(mutated);
                } else {
                    checkBlock(f.body(), inProofContext, null);
                }
            }
            case ScopeExpr s -> {
                if (!inProofContext && ctx != null) {
                    int origPremisesSize = ctx.premises.size();
                    CallSiteCtx inner = ctx.fork(null);
                    checkBlock(s.body(), false, inner);
                    ctx.mergeBlockExit(inner, s.body(), origPremisesSize);
                } else {
                    checkBlock(s.body(), inProofContext, null);
                }
            }
            case ForkExpr f -> {
                if (!inProofContext && ctx != null) {
                    Set<String> mutated = new LinkedHashSet<>();
                    collectAssignedVars(f.body(), mutated);
                    CallSiteCtx forkCtx = ctx.fork(null);
                    forkCtx.havocVars(mutated);
                    checkBlock(f.body(), false, forkCtx);
                    ctx.havocVars(mutated);
                } else {
                    checkBlock(f.body(), inProofContext, null);
                }
            }
            case BinaryExpr b -> {
                checkRuntimeOrProofExpr(b.left(), inProofContext, ctx);
                checkRuntimeOrProofExpr(b.right(), inProofContext, ctx);
            }
            case UnaryExpr u -> checkRuntimeOrProofExpr(u.operand(), inProofContext, ctx);
            case ParenExpr p -> checkRuntimeOrProofExpr(p.inner(), inProofContext, ctx);
            case PropagateExpr p -> checkRuntimeOrProofExpr(p.inner(), inProofContext, ctx);
            case ConsumeExpr c -> checkRuntimeOrProofExpr(c.target(), inProofContext, ctx);
            case FieldAccessExpr fa -> checkRuntimeOrProofExpr(fa.object(), inProofContext, ctx);
            case TupleExpr t -> {
                for (var e : t.elements()) checkRuntimeOrProofExpr(e, inProofContext, ctx);
            }
            case ConstructorExpr c -> {
                for (var field : c.fields()) checkRuntimeOrProofExpr(field.value(), inProofContext, ctx);
            }
            case LambdaExpr l -> {
                CallSiteCtx lambdaCtx = (!inProofContext && ctx != null) ? ctx.fork(null) : null;
                if (lambdaCtx != null) {
                    for (var p : l.params()) {
                        SmtExpr.Sort pSort = VcGenerator.sortOfType(p.type());
                        String lhs = lambdaCtx.freshSsa(p.name(), pSort);
                        if (VcGenerator.isLongType(p.type())) {
                            lambdaCtx.longVars.add(lhs);
                            lambdaCtx.longVars.add(p.name());
                        }
                    }
                }
                checkBlock(l.body(), inProofContext, lambdaCtx);
            }
            default -> {}
        }
    }

    private static void bindPatternVars(Pattern pattern, CallSiteCtx ctx) {
        if (pattern == null) return;
        switch (pattern) {
            case BindingPattern bp -> ctx.freshSsa(bp.name(), SmtExpr.Sort.INT);
            case ConstructorPattern cp -> {
                for (var f : cp.fields()) {
                    if (f.pattern() != null) {
                        bindPatternVars(f.pattern(), ctx);
                    } else if (f.name() != null) {
                        ctx.freshSsa(f.name(), SmtExpr.Sort.INT);
                    }
                }
            }
            default -> {}
        }
    }

    private void verifyCalleePreconditions(CallExpr call, String calleeName, CallSiteCtx ctx) {
        FnDecl callee = fnDecls.get(calleeName);
        if (callee == null || callee.params().size() != call.args().size()) {
            return;
        }
        List<ContractClause> calleeRequires = callee.contracts().stream()
            .filter(c -> c.kind() == ContractClause.Kind.REQUIRES)
            .toList();
        if (calleeRequires.isEmpty()) {
            return;
        }

        int callId = ctx.nextCallId();
        Map<String, SmtExpr.Sort> callSorts = new LinkedHashMap<>(ctx.sorts);
        List<SmtExpr> callPremises = new ArrayList<>(ctx.premises);
        Map<String, String> calleeSsaEnv = new LinkedHashMap<>();

        for (int i = 0; i < callee.params().size(); i++) {
            Param p = callee.params().get(i);
            SmtExpr.Sort pSort = ctx.vcGen.sortOf(p.type());
            String callParamName = "__callee$" + callId + "$" + p.name();
            callSorts.put(callParamName, pSort);
            calleeSsaEnv.put(p.name(), callParamName);

            SmtExpr argExpr = ctx.vcGen.translateExecutableExpr(call.args().get(i), ctx.sorts, ctx.ssaEnv, ctx.longVars);
            if (!VcGenerator.containsUnsupported(argExpr)) {
                callPremises.add(new SmtExpr.Eq(new SmtExpr.Var(callParamName, pSort), argExpr));
            }
        }

        for (var req : calleeRequires) {
            SmtExpr reqExpr = ctx.vcGen.translateExpr(req.condition(), callSorts, calleeSsaEnv);
            SmtExpr vc;
            if (callPremises.isEmpty()) {
                vc = reqExpr;
            } else {
                SmtExpr conj = callPremises.size() == 1 ? callPremises.get(0) : new SmtExpr.And(callPremises);
                vc = new SmtExpr.Implies(conj, reqExpr);
            }
            runSmtAndReport(vc, callee.name(), call.span());
        }
    }

    private static void collectAssignedVars(Expr expr, Set<String> out) {
        collectAssignedVars(expr, out, Set.of());
    }

    private static void collectAssignedVars(Expr expr, Set<String> out, Set<String> shadowed) {
        if (expr == null) return;
        switch (expr) {
            case BlockExpr b -> {
                Set<String> innerShadowed = new LinkedHashSet<>(shadowed);
                for (var st : b.statements()) {
                    switch (st) {
                        case ValStmt v -> {
                            collectAssignedVars(v.initializer(), out, innerShadowed);
                            innerShadowed.add(v.name());
                        }
                        case LetStmt l -> {
                            collectAssignedVars(l.initializer(), out, innerShadowed);
                            innerShadowed.add(l.name());
                        }
                        case VarStmt v -> {
                            collectAssignedVars(v.initializer(), out, innerShadowed);
                            innerShadowed.add(v.name());
                        }
                        case TupleDestructureStmt td -> {
                            collectAssignedVars(td.initializer(), out, innerShadowed);
                            innerShadowed.addAll(td.names());
                        }
                        case AssignStmt a -> {
                            collectAssignedVars(a.value(), out, innerShadowed);
                            if (!innerShadowed.contains(a.name())) {
                                out.add(a.name());
                            }
                        }
                        case ReturnStmt r -> collectAssignedVars(r.value(), out, innerShadowed);
                        case ExprStmt es -> collectAssignedVars(es.expr(), out, innerShadowed);
                        default -> {}
                    }
                }
                collectAssignedVars(b.tailExpr(), out, innerShadowed);
            }
            case IfExpr i -> {
                collectAssignedVars(i.condition(), out, shadowed);
                collectAssignedVars(i.thenBranch(), out, shadowed);
                collectAssignedVars(i.elseBranch(), out, shadowed);
            }
            case WhileExpr w -> {
                collectAssignedVars(w.condition(), out, shadowed);
                collectAssignedVars(w.body(), out, shadowed);
            }
            case ForExpr f -> {
                collectAssignedVars(f.iterable(), out, shadowed);
                Set<String> inner = new LinkedHashSet<>(shadowed);
                inner.add(f.varName());
                collectAssignedVars(f.body(), out, inner);
            }
            case MatchExpr m -> {
                collectAssignedVars(m.scrutinee(), out, shadowed);
                for (var arm : m.arms()) collectAssignedVars(arm.body(), out, shadowed);
            }
            case ScopeExpr s -> collectAssignedVars(s.body(), out, shadowed);
            case ForkExpr f -> collectAssignedVars(f.body(), out, shadowed);
            case BinaryExpr b -> {
                collectAssignedVars(b.left(), out, shadowed);
                collectAssignedVars(b.right(), out, shadowed);
            }
            case UnaryExpr u -> collectAssignedVars(u.operand(), out, shadowed);
            case ParenExpr p -> collectAssignedVars(p.inner(), out, shadowed);
            case PropagateExpr p -> collectAssignedVars(p.inner(), out, shadowed);
            case ConsumeExpr c -> collectAssignedVars(c.target(), out, shadowed);
            case FieldAccessExpr fa -> collectAssignedVars(fa.object(), out, shadowed);
            case CallExpr c -> {
                collectAssignedVars(c.callee(), out, shadowed);
                for (var a : c.args()) collectAssignedVars(a, out, shadowed);
            }
            case TupleExpr t -> {
                for (var e : t.elements()) collectAssignedVars(e, out, shadowed);
            }
            case ConstructorExpr c -> {
                for (var f : c.fields()) collectAssignedVars(f.value(), out, shadowed);
            }
            default -> {}
        }
    }

    private static final class CallSiteCtx {
        final VcGenerator vcGen;
        final Map<String, SmtExpr.Sort> sorts;
        final Map<String, String> ssaEnv;
        final Map<String, Integer> ssaCounter;
        final Set<String> longVars;
        final List<SmtExpr> premises;
        final int[] callCounter;

        private CallSiteCtx(
            VcGenerator vcGen,
            Map<String, SmtExpr.Sort> sorts,
            Map<String, String> ssaEnv,
            Map<String, Integer> ssaCounter,
            Set<String> longVars,
            List<SmtExpr> premises,
            int[] callCounter
        ) {
            this.vcGen = vcGen;
            this.sorts = sorts;
            this.ssaEnv = ssaEnv;
            this.ssaCounter = ssaCounter;
            this.longVars = longVars;
            this.premises = premises;
            this.callCounter = callCounter;
        }

        static CallSiteCtx forFn(FnDecl fn, VcGenerator vcGen) {
            Map<String, SmtExpr.Sort> sorts = vcGen.collectSorts(fn.params(), fn.returnType());
            Set<String> longVars = vcGen.collectLongVarsFor(fn.params());
            List<SmtExpr> premises = new ArrayList<>();
            for (var c : fn.contracts()) {
                if (c.kind() == ContractClause.Kind.REQUIRES) {
                    SmtExpr req = vcGen.translateExpr(c.condition(), sorts);
                    if (!VcGenerator.containsUnsupported(req)) {
                        premises.add(req);
                    }
                }
            }
            return new CallSiteCtx(
                vcGen,
                sorts,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                longVars,
                premises,
                new int[]{0}
            );
        }

        CallSiteCtx fork(SmtExpr extraPremise) {
            List<SmtExpr> nextPremises = new ArrayList<>(premises);
            if (extraPremise != null && !VcGenerator.containsUnsupported(extraPremise)) {
                nextPremises.add(extraPremise);
            }
            return new CallSiteCtx(
                vcGen,
                sorts,
                new LinkedHashMap<>(ssaEnv),
                ssaCounter,
                new LinkedHashSet<>(longVars),
                nextPremises,
                callCounter
            );
        }

        int nextCallId() {
            return ++callCounter[0];
        }

        String freshSsa(String name, SmtExpr.Sort sort) {
            int n = ssaCounter.merge(name, 1, Integer::sum);
            String ssaName = name + "$ssa" + n;
            sorts.put(ssaName, sort);
            ssaEnv.put(name, ssaName);
            return ssaName;
        }

        void bindLocal(String name, TypeExpr explicitType, Expr initializer) {
            SmtExpr init = vcGen.translateExecutableExpr(initializer, sorts, ssaEnv, longVars);
            boolean longInit = vcGen.isLong(explicitType) || vcGen.isLongExpr(initializer, longVars, ssaEnv);
            SmtExpr.Sort sort = explicitType != null ? vcGen.sortOf(explicitType) : VcGenerator.inferSort(init);
            String lhs = (ssaEnv.containsKey(name) || sorts.containsKey(name))
                ? freshSsa(name, sort)
                : name;
            sorts.put(lhs, sort);
            ssaEnv.put(name, lhs);
            if (longInit) {
                longVars.add(lhs);
            }
            if (!VcGenerator.containsUnsupported(init)) {
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), init));
            }
        }

        void bindVar(String name, TypeExpr explicitType, Expr initializer) {
            SmtExpr init = vcGen.translateExecutableExpr(initializer, sorts, ssaEnv, longVars);
            boolean longInit = vcGen.isLong(explicitType) || vcGen.isLongExpr(initializer, longVars, ssaEnv);
            SmtExpr.Sort sort = explicitType != null ? vcGen.sortOf(explicitType) : VcGenerator.inferSort(init);
            String lhs = freshSsa(name, sort);
            if (longInit) {
                longVars.add(lhs);
            }
            if (!VcGenerator.containsUnsupported(init)) {
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), init));
            }
        }

        void bindTupleDestructure(TupleDestructureStmt td) {
            Expr init = td.initializer();
            while (init instanceof ParenExpr p) init = p.inner();
            TupleExpr te = init instanceof TupleExpr t ? t : null;
            List<SmtExpr> rhsList = new ArrayList<>(td.names().size());
            List<Boolean> longFlags = new ArrayList<>(td.names().size());
            for (int i = 0; i < td.names().size(); i++) {
                if (te != null && i < te.elements().size()) {
                    Expr elem = te.elements().get(i);
                    rhsList.add(vcGen.translateExecutableExpr(elem, sorts, ssaEnv, longVars));
                    longFlags.add(vcGen.isLongExpr(elem, longVars, ssaEnv));
                } else {
                    rhsList.add(null);
                    longFlags.add(false);
                }
            }
            for (int i = 0; i < td.names().size(); i++) {
                String name = td.names().get(i);
                TypeExpr explicitType = i < td.explicitTypes().size() ? td.explicitTypes().get(i) : null;
                SmtExpr rhs = rhsList.get(i);
                SmtExpr.Sort sort = explicitType != null
                    ? vcGen.sortOf(explicitType)
                    : (rhs != null ? VcGenerator.inferSort(rhs) : SmtExpr.Sort.INT);
                String lhs = freshSsa(name, sort);
                if (vcGen.isLong(explicitType) || Boolean.TRUE.equals(longFlags.get(i))) {
                    longVars.add(lhs);
                }
                if (rhs != null && !VcGenerator.containsUnsupported(rhs)) {
                    premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), rhs));
                }
            }
        }

        void assignVar(String name, Expr value) {
            SmtExpr rhs = vcGen.translateExecutableExpr(value, sorts, ssaEnv, longVars);
            String prevName = ssaEnv.getOrDefault(name, name);
            boolean longRhs = longVars.contains(prevName) || vcGen.isLongExpr(value, longVars, ssaEnv);
            SmtExpr.Sort sort = sorts.getOrDefault(prevName, VcGenerator.inferSort(rhs));
            String lhs = freshSsa(name, sort);
            if (longRhs) {
                longVars.add(lhs);
            }
            if (!VcGenerator.containsUnsupported(rhs)) {
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), rhs));
            }
        }

        void addAssumption(Expr condExpr) {
            SmtExpr cond = vcGen.translateExpr(condExpr, sorts, ssaEnv);
            if (!VcGenerator.containsUnsupported(cond)) {
                premises.add(cond);
            }
        }

        void havocVars(Set<String> vars) {
            for (String v : vars) {
                String prevName = ssaEnv.getOrDefault(v, v);
                SmtExpr.Sort sort = sorts.getOrDefault(prevName, SmtExpr.Sort.INT);
                String lhs = freshSsa(v, sort);
                if (longVars.contains(prevName) || longVars.contains(v)) {
                    longVars.add(lhs);
                }
            }
        }

        void mergeBlockExit(CallSiteCtx inner, BlockExpr block, int origPremisesSize) {
            Set<String> blockDeclared = new LinkedHashSet<>();
            for (var st : block.statements()) {
                switch (st) {
                    case ValStmt v -> blockDeclared.add(v.name());
                    case LetStmt l -> blockDeclared.add(l.name());
                    case VarStmt v -> blockDeclared.add(v.name());
                    case TupleDestructureStmt td -> blockDeclared.addAll(td.names());
                    default -> {}
                }
            }
            for (int i = origPremisesSize; i < inner.premises.size(); i++) {
                premises.add(inner.premises.get(i));
            }
            for (var entry : inner.ssaEnv.entrySet()) {
                String varName = entry.getKey();
                if (!blockDeclared.contains(varName) && (ssaEnv.containsKey(varName) || sorts.containsKey(varName))) {
                    ssaEnv.put(varName, entry.getValue());
                    if (inner.longVars.contains(entry.getValue())) {
                        longVars.add(entry.getValue());
                    }
                }
            }
        }
    }

    private void checkBlockPurityAndHoles(BlockExpr block, boolean inProofContext, Set<String> mutableVars) {
        if (block == null) return;
        for (var stmt : block.statements()) {
            switch (stmt) {
                case VarStmt v -> diagnostics.add(Diagnostic.error(
                    "NGJ-PROOF-002",
                    "Proof/spec blocks cannot declare mutable 'var' '" + v.name() + "'",
                    v.span()
                ));
                case AssignStmt a -> diagnostics.add(Diagnostic.error(
                    "NGJ-PROOF-002",
                    "Proof/spec blocks cannot mutate variables",
                    a.span()
                ));
                case ValStmt v -> checkExprPurityAndHoles(v.initializer(), inProofContext, mutableVars);
                case LetStmt l -> checkExprPurityAndHoles(l.initializer(), inProofContext, mutableVars);
                case ExprStmt es -> checkExprPurityAndHoles(es.expr(), inProofContext, mutableVars);
                case ReturnStmt r -> {
                    if (r.value() != null) checkExprPurityAndHoles(r.value(), inProofContext, mutableVars);
                }
                case InvariantStmt inv -> checkExprPurityAndHoles(inv.condition(), inProofContext, mutableVars);
                case AssumeStmt assume -> checkExprPurityAndHoles(assume.condition(), inProofContext, mutableVars);
                case ProofStmt p -> checkBlockPurityAndHoles(p.body(), true, mutableVars);
                default -> {}
            }
        }
        if (block.tailExpr() != null) {
            checkExprPurityAndHoles(block.tailExpr(), inProofContext, mutableVars);
        }
    }

    private void checkExprPurityAndHoles(Expr expr, boolean inProofContext, Set<String> mutableVars) {
        if (expr == null) return;
        switch (expr) {
            case HoleExpr hole -> {
                if (hole.isProofHole() || inProofContext) {
                    emitProofHole(hole);
                }
            }
            case ScopeExpr _, ForkExpr _, ConsumeExpr _ -> diagnostics.add(Diagnostic.error(
                "NGJ-PROOF-002",
                "Proof/spec expressions must be pure and cannot use scope/fork/consume",
                expr.span()
            ));
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr id) {
                    var fn = fnDecls.get(id.name());
                    if (fn != null && !fn.effects().isEmpty()) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-PROOF-002",
                            "Proof/spec expression cannot call effectful function '" + id.name() + "'",
                            call.span()
                        ));
                    }
                }
                for (var arg : call.args()) checkExprPurityAndHoles(arg, inProofContext, mutableVars);
            }
            case BinaryExpr b -> {
                checkExprPurityAndHoles(b.left(), inProofContext, mutableVars);
                checkExprPurityAndHoles(b.right(), inProofContext, mutableVars);
            }
            case UnaryExpr u -> checkExprPurityAndHoles(u.operand(), inProofContext, mutableVars);
            case ParenExpr p -> checkExprPurityAndHoles(p.inner(), inProofContext, mutableVars);
            case BlockExpr b -> checkBlockPurityAndHoles(b, inProofContext, mutableVars);
            case IfExpr i -> {
                checkExprPurityAndHoles(i.condition(), inProofContext, mutableVars);
                checkBlockPurityAndHoles(i.thenBranch(), inProofContext, mutableVars);
                if (i.elseBranch() != null) checkExprPurityAndHoles(i.elseBranch(), inProofContext, mutableVars);
            }
            default -> {}
        }
    }

    private void emitProofHole(HoleExpr hole) {
        diagnostics.add(Diagnostic.incompleteProof(
            "NGJ-PROOF-001",
            "proof-required".equals(profile)
                ? "Unresolved proof hole ?" + hole.name() + " is rejected under --profile=proof-required"
                : "Unresolved proof hole: ?" + hole.name(),
            hole.span()
        ));
    }

    private void verifyFnWithSmt(FnDecl fn, VcGenerator vcGen) {
        Map<String, SmtExpr.Sort> sorts = vcGen.collectSorts(fn.params(), fn.returnType());
        List<SmtExpr> requires = new ArrayList<>();
        List<ContractClause> ensuresList = new ArrayList<>();
        for (var c : fn.contracts()) {
            if (c.kind() == ContractClause.Kind.REQUIRES) {
                requires.add(vcGen.translateExpr(c.condition(), sorts));
            } else {
                ensuresList.add(c);
            }
        }
        for (var ens : ensuresList) {
            SmtExpr ensExpr = vcGen.translateExpr(ens.condition(), sorts);
            SmtExpr vc = vcGen.buildFnPostconditionVc(fn, requires, ensExpr);
            runSmtAndReport(vc, fn.name(), ens.span());
        }
    }

    private void verifyLemmaWithSmt(LemmaDecl lm, VcGenerator vcGen) {
        Map<String, SmtExpr.Sort> sorts = vcGen.collectSorts(lm.params(), null);
        List<SmtExpr> requires = new ArrayList<>();
        List<ContractClause> ensuresList = new ArrayList<>();
        for (var c : lm.contracts()) {
            if (c.kind() == ContractClause.Kind.REQUIRES) {
                requires.add(vcGen.translateExpr(c.condition(), sorts));
            } else {
                ensuresList.add(c);
            }
        }
        for (var ens : ensuresList) {
            SmtExpr ensExpr = vcGen.translateExpr(ens.condition(), sorts);
            SmtExpr vc = vcGen.buildLemmaVc(lm, requires, ensExpr);
            runSmtAndReport(vc, lm.name(), ens.span());
        }
    }

    private boolean runSmtAndReport(SmtExpr vc, String symbolName, SourceSpan span) {
        SmtOutcome outcome = solve(vc);
        switch (outcome.status()) {
            case PROVED -> { return true; }
            case COUNTEREXAMPLE -> {
                Map<String, String> redacted = SecretRedactor.redactState(outcome.model(), secretVars);
                var diagnosis = new SolverDiagnosis("counterexample_found", span, redacted);
                diagnostics.add(Diagnostic.error(
                    "NGJ-VERIFY-001",
                    "SMT verification failed for '" + symbolName + "': counterexample found (" + outcome.detail() + ")",
                    span
                ).withSolverDiagnosis(diagnosis));
            }
            case TIMEOUT -> diagnostics.add(Diagnostic.error(
                "NGJ-VERIFY-001",
                "SMT verification timed out for '" + symbolName + "': " + outcome.detail(),
                span
            ).withSolverDiagnosis(new SolverDiagnosis("timeout", span, Map.of())));
            case UNPROVEN, UNSUPPORTED -> diagnostics.add(Diagnostic.error(
                "NGJ-VERIFY-001",
                "SMT verification " + outcome.status().name().toLowerCase(Locale.ROOT)
                    + " for '" + symbolName + "': " + outcome.detail(),
                span
            ).withSolverDiagnosis(new SolverDiagnosis("axiomatization_gap", span, Map.of())));
        }
        return false;
    }

    private SmtOutcome solve(SmtExpr vc) {
        if ("z3".equals(smtBackend)) {
            return new ExternalZ3Solver().prove(vc);
        }
        SmtOutcome jvmOutcome = new PureJvmSmtSolver().prove(vc);
        if ("auto".equals(smtBackend)
            && (jvmOutcome.status() == SmtOutcome.Status.UNSUPPORTED
                || jvmOutcome.status() == SmtOutcome.Status.UNPROVEN
                || jvmOutcome.status() == SmtOutcome.Status.TIMEOUT)) {
            ExternalZ3Solver z3 = new ExternalZ3Solver();
            if (z3.isAvailable()) {
                return z3.prove(vc);
            }
        }
        return jvmOutcome;
    }

    private boolean isSecretType(TypeExpr t) {
        TypeExpr resolved = resolveTypeAlias(t);
        if (resolved instanceof GenericTypeExpr g) return g.name().equals("Secret");
        if (resolved instanceof SimpleTypeExpr s) return s.name().equals("Secret");
        if (resolved instanceof CapabilityTypeExpr c) return isSecretType(c.inner());
        if (resolved instanceof RefinedTypeExpr r) return isSecretType(r.baseType());
        return false;
    }
}
