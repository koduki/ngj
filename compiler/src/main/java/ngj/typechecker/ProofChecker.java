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
        secretVars.clear();

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case SpecFnDecl sf -> specFns.put(sf.name(), sf);
                    case LemmaDecl lm -> lemmas.put(lm.name(), lm);
                    case FnDecl fn -> fnDecls.put(fn.name(), fn);
                    default -> {}
                }
            }
        }

        VcGenerator vcGen = new VcGenerator(specFns);

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
            if (SecretRedactor.isSecretVariableName(p.name()) || isSecretType(p.type())) {
                secretVars.add(p.name());
            }
            checkNoMathIntInType(p.type(), p.span() != null ? p.span() : fn.span());
        }
        checkNoMathIntInType(fn.returnType(), fn.span());
        for (var c : fn.contracts()) {
            checkExprPurityAndHoles(c.condition(), true, Set.of());
        }
        if (fn.body() != null) {
            checkBlock(fn.body(), false);
        }
        if ("smt".equals(verifyMode)) {
            verifyFnWithSmt(fn, vcGen);
        }
    }

    private void checkNoMathIntInType(TypeExpr type, SourceSpan span) {
        if (type == null) return;
        switch (type) {
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

    private void checkBlock(BlockExpr block, boolean inProofContext) {
        if (block == null) return;
        for (var stmt : block.statements()) {
            switch (stmt) {
                case ValStmt v -> {
                    if (SecretRedactor.isSecretVariableName(v.name()) || isSecretType(v.explicitType())) {
                        secretVars.add(v.name());
                    }
                    if (!inProofContext) checkNoMathIntInType(v.explicitType(), v.span());
                    checkRuntimeOrProofExpr(v.initializer(), inProofContext);
                }
                case LetStmt l -> {
                    if (!inProofContext) checkNoMathIntInType(l.explicitType(), l.span());
                    checkRuntimeOrProofExpr(l.initializer(), inProofContext);
                }
                case VarStmt v -> {
                    if (!inProofContext) checkNoMathIntInType(v.explicitType(), v.span());
                    checkRuntimeOrProofExpr(v.initializer(), inProofContext);
                }
                case AssignStmt a -> checkRuntimeOrProofExpr(a.value(), inProofContext);
                case ReturnStmt r -> {
                    if (r.value() != null) checkRuntimeOrProofExpr(r.value(), inProofContext);
                }
                case ExprStmt es -> checkRuntimeOrProofExpr(es.expr(), inProofContext);
                case TupleDestructureStmt td -> {
                    if (!inProofContext) {
                        for (var t : td.explicitTypes()) checkNoMathIntInType(t, td.span());
                    }
                    checkRuntimeOrProofExpr(td.initializer(), inProofContext);
                }
                case InvariantStmt inv -> checkExprPurityAndHoles(inv.condition(), true, Set.of());
                case ProofStmt proof -> checkBlockPurityAndHoles(proof.body(), true, Set.of());
                case AssumeStmt assume -> checkExprPurityAndHoles(assume.condition(), true, Set.of());
            }
        }
        if (block.tailExpr() != null) {
            checkRuntimeOrProofExpr(block.tailExpr(), inProofContext);
        }
    }

    private void checkRuntimeOrProofExpr(Expr expr, boolean inProofContext) {
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
                checkRuntimeOrProofExpr(call.callee(), inProofContext);
                for (var arg : call.args()) checkRuntimeOrProofExpr(arg, inProofContext);
            }
            case BlockExpr b -> checkBlock(b, inProofContext);
            case IfExpr i -> {
                checkRuntimeOrProofExpr(i.condition(), inProofContext);
                checkBlock(i.thenBranch(), inProofContext);
                if (i.elseBranch() != null) checkRuntimeOrProofExpr(i.elseBranch(), inProofContext);
            }
            case MatchExpr m -> {
                checkRuntimeOrProofExpr(m.scrutinee(), inProofContext);
                for (var arm : m.arms()) checkRuntimeOrProofExpr(arm.body(), inProofContext);
            }
            case WhileExpr w -> {
                checkRuntimeOrProofExpr(w.condition(), inProofContext);
                checkBlock(w.body(), inProofContext);
            }
            case ForExpr f -> {
                checkRuntimeOrProofExpr(f.iterable(), inProofContext);
                checkBlock(f.body(), inProofContext);
            }
            case ScopeExpr s -> checkBlock(s.body(), inProofContext);
            case ForkExpr f -> checkBlock(f.body(), inProofContext);
            case BinaryExpr b -> {
                checkRuntimeOrProofExpr(b.left(), inProofContext);
                checkRuntimeOrProofExpr(b.right(), inProofContext);
            }
            case UnaryExpr u -> checkRuntimeOrProofExpr(u.operand(), inProofContext);
            case ParenExpr p -> checkRuntimeOrProofExpr(p.inner(), inProofContext);
            case PropagateExpr p -> checkRuntimeOrProofExpr(p.inner(), inProofContext);
            case ConsumeExpr c -> checkRuntimeOrProofExpr(c.target(), inProofContext);
            case FieldAccessExpr fa -> checkRuntimeOrProofExpr(fa.object(), inProofContext);
            case TupleExpr t -> {
                for (var e : t.elements()) checkRuntimeOrProofExpr(e, inProofContext);
            }
            default -> {}
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

    private void runSmtAndReport(SmtExpr vc, String symbolName, SourceSpan span) {
        SmtOutcome outcome = solve(vc);
        switch (outcome.status()) {
            case PROVED -> {}
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
        if (t instanceof GenericTypeExpr g) return g.name().equals("Secret");
        if (t instanceof SimpleTypeExpr s) return s.name().equals("Secret");
        return false;
    }
}
