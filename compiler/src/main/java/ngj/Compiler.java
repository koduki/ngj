package ngj;

import ngj.HoleContext.ContextObligation;
import ngj.HoleContext.RelevantSymbol;
import ngj.ast.*;
import ngj.codegen.*;
import ngj.diagnostic.*;
import ngj.gate.ObligationCollector;
import ngj.ir.TypedIR;
import ngj.parser.*;
import ngj.typechecker.*;
import ngj.typechecker.TypeChecker.CheckOptions;
import ngj.typechecker.protocol.ProtocolAutomaton;
import ngj.verifier.ExternalZ3Solver;
import ngj.verifier.Post2ExeVerifier;
import ngj.verifier.Post2ExeVerifier.Post2ExeReport;
import ngj.verifier.PureJvmSmtSolver;
import ngj.verifier.SmtExpr;
import ngj.verifier.SmtOutcome;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Main compiler facade for NGJ.
 */
public final class Compiler {

    private final Map<Path, CompilationUnit> parsedUnits = new LinkedHashMap<>();
    private final Map<String, byte[]> sourceUtf8ByFile = new LinkedHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private TypedIR lastTypedIR = TypedIR.empty();

    public CheckResult check(List<Path> files) {
        return check(files, CheckOptions.defaults());
    }

    public CheckResult check(List<Path> files, CheckOptions options) {
        Map<Path, String> sources = new LinkedHashMap<>();
        List<Diagnostic> ioErrors = new ArrayList<>();

        for (Path file : files) {
            try {
                sources.put(file, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                ioErrors.add(Diagnostic.error(
                    "NGJ-IO-001",
                    "Cannot read file: " + e.getMessage(),
                    new SourceSpan(file.toString(), 0, 0)
                ));
            }
        }

        if (!ioErrors.isEmpty()) {
            diagnostics.clear();
            diagnostics.addAll(ioErrors);
            return new CheckResult(CheckStatus.INVALID, List.copyOf(diagnostics), List.of());
        }

        return checkVirtual(sources, options);
    }

    public CheckResult checkVirtual(Map<Path, String> virtualFiles, CheckOptions options) {
        CheckOptions opts = options != null ? options : CheckOptions.defaults();
        diagnostics.clear();
        parsedUnits.clear();
        sourceUtf8ByFile.clear();
        lastTypedIR = TypedIR.empty();

        List<CompilationUnit> units = new ArrayList<>();

        // Phase 1: Parse all files
        for (var entry : virtualFiles.entrySet()) {
            Path file = entry.getKey();
            String source = entry.getValue();
            sourceUtf8ByFile.put(file.toString(), source.getBytes(StandardCharsets.UTF_8));
            try {
                var lexer = new Lexer(source, file.toString());
                var parser = new Parser(lexer, file.toString());
                var unit = parser.parse();
                parsedUnits.put(file, unit);
                units.add(unit);
                diagnostics.addAll(parser.getDiagnostics());
            } catch (ParseException e) {
                diagnostics.addAll(e.getDiagnostics());
            }
        }

        if (hasDefiniteErrors()) {
            enrichAndRedactDiagnostics();
            return new CheckResult(CheckStatus.INVALID, List.copyOf(diagnostics), units);
        }

        // Phase 2: Name resolution
        var resolver = new NameResolver();
        resolver.resolveAll(units);
        diagnostics.addAll(resolver.getDiagnostics());

        if (hasDefiniteErrors()) {
            enrichAndRedactDiagnostics();
            return new CheckResult(CheckStatus.INVALID, List.copyOf(diagnostics), units);
        }

        // Phase 3: Type checking + Phase 2-4 checkers
        var typeChecker = new TypeChecker();
        typeChecker.checkAll(units, opts);
        diagnostics.addAll(typeChecker.getDiagnostics());
        lastTypedIR = typeChecker.getTypedIR();

        enrichAndRedactDiagnostics();

        // Determine status: INVALID > INCOMPLETE > INCOMPLETE_PROOF > VALID
        if (hasDefiniteErrors()) {
            return new CheckResult(CheckStatus.INVALID, List.copyOf(diagnostics), units);
        } else if (hasCodeHoles(units) || hasCertainty(Certainty.INCOMPLETE)) {
            return new CheckResult(CheckStatus.INCOMPLETE, List.copyOf(diagnostics), units);
        } else if (hasProofHoles(units) || hasCertainty(Certainty.INCOMPLETE_PROOF)) {
            return new CheckResult(CheckStatus.INCOMPLETE_PROOF, List.copyOf(diagnostics), units);
        } else {
            return new CheckResult(CheckStatus.VALID, List.copyOf(diagnostics), units);
        }
    }

    public ContextResult context(String holeId, int maxBytes) {
        return context(
            holeId,
            false,
            maxBytes,
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            ngj.cli.Main.TARGET_PROFILE
        );
    }

    public ContextResult context(
        String holeId,
        boolean proofHoleMode,
        int maxBytes,
        String sourceRevision,
        String policyDigest,
        String targetProfile
    ) {
        for (var entry : parsedUnits.entrySet()) {
            var unit = entry.getValue();
            var hole = findHole(unit, holeId);
            if (hole != null && (!proofHoleMode || hole.isProofHole())) {
                return buildContext(hole, proofHoleMode, maxBytes, sourceRevision, policyDigest, targetProfile);
            }
        }
        return new ContextResult(null, (proofHoleMode ? "Proof hole" : "Hole") + " not found: " + holeId);
    }

    public InspectResult inspect(String symbolId) {
        for (var entry : parsedUnits.entrySet()) {
            var unit = entry.getValue();
            var symbol = findSymbol(unit, symbolId);
            if (symbol != null) {
                return new InspectResult(symbol);
            }
        }
        return new InspectResult(null, "Symbol not found: " + symbolId);
    }

    public InspectResult inspectProtocol(String protocolId) {
        var automata = lastTypedIR.protocolAutomata();
        if (automata.containsKey(protocolId)) {
            return InspectResult.ofProtocol(protocolId, automata.get(protocolId));
        }
        for (var entry : automata.entrySet()) {
            if (entry.getKey().endsWith("." + protocolId) || entry.getKey().equals(protocolId)) {
                return InspectResult.ofProtocol(entry.getKey(), entry.getValue());
            }
        }
        return new InspectResult(null, null, null, "Protocol not found: " + protocolId);
    }

    public ObligationsResult obligations() {
        var collector = new ObligationCollector();
        var units = new ArrayList<>(parsedUnits.values());
        return new ObligationsResult(collector.collect(units, diagnostics));
    }

    public CertifySpecResult certifySpec(String symbolId, String smtBackend) {
        return certifySpec(symbolId, smtBackend, null);
    }

    public CertifySpecResult certifySpec(String symbolId, String smtBackend, CheckResult initialCheck) {
        FnDecl targetFn = null;
        String qualifiedId = null;
        for (var unit : parsedUnits.values()) {
            String mod = unit.modulePath();
            for (var decl : unit.declarations()) {
                if (decl instanceof FnDecl fn) {
                    String fullId = mod + "." + fn.name();
                    if (fullId.equals(symbolId) || fn.name().equals(symbolId)) {
                        targetFn = fn;
                        qualifiedId = fullId;
                        break;
                    }
                }
            }
            if (targetFn != null) break;
        }

        boolean initialCheckFailed = hasDefiniteErrors()
            || (initialCheck != null && initialCheck.status() == CheckStatus.INVALID);
        String effectiveBackend = smtBackend != null ? smtBackend : "jvm";
        Map<String, SpecFnDecl> specFns = lastTypedIR.specFunctions();

        if (initialCheckFailed) {
            List<String> surfaceBFindings = new ArrayList<>();
            List<Diagnostic> allDiags = new ArrayList<>(diagnostics);
            if (targetFn != null && containsAssumeInFn(targetFn, specFns)) {
                surfaceBFindings.add("NGJ-CERT-ASSUME-PRESENT");
                allDiags.add(Diagnostic.error(
                    "NGJ-CERT-ASSUME-PRESENT",
                    "Certified symbol '" + qualifiedId + "' contains forbidden assume(...)",
                    targetFn.span()
                ));
            }
            return new CertifySpecResult(
                qualifiedId != null ? qualifiedId : symbolId,
                false,
                false,
                "unproven",
                effectiveBackend,
                "Initial compiler check failed",
                false,
                List.copyOf(surfaceBFindings),
                null,
                List.copyOf(allDiags),
                null
            );
        }

        if (targetFn == null) {
            return CertifySpecResult.badInput("Symbol not found: " + symbolId);
        }

        List<TopLevelDecl> holeCheckDecls = new ArrayList<>();
        holeCheckDecls.add(targetFn);
        holeCheckDecls.addAll(specFns.values());
        if (new HoleFinder().hasHoles(new CompilationUnit(
            new ModuleDecl("tmp", null), List.of(), holeCheckDecls, null))) {
            return new CertifySpecResult(
                qualifiedId, false, true, "unproven", effectiveBackend, "Holes present in symbol",
                false, List.of(), null, List.copyOf(diagnostics), null
            );
        }

        PureJvmSmtSolver jvmSolver = new PureJvmSmtSolver();
        Map<String, TypeAliasDecl> typeAliases = collectTypeAliases();
        VcGenerator vcGen = new VcGenerator(specFns, typeAliases);

        // Surface A: SMT verification of requires => ensures
        List<Expr> reqExprs = targetFn.contracts().stream()
            .filter(c -> c.kind() == ContractClause.Kind.REQUIRES)
            .map(ContractClause::condition)
            .toList();
        List<Expr> ensExprs = targetFn.contracts().stream()
            .filter(c -> c.kind() == ContractClause.Kind.ENSURES)
            .map(ContractClause::condition)
            .toList();

        Map<String, SmtExpr.Sort> sorts = vcGen.collectSorts(targetFn.params(), targetFn.returnType());
        List<SmtExpr> reqSmt = reqExprs.stream().map(e -> vcGen.translateExpr(e, sorts)).toList();
        List<SmtExpr> ensSmt = ensExprs.stream().map(e -> vcGen.translateExpr(e, sorts)).toList();

        String surfaceAStatus = "unproven";
        String surfaceADetail = "No ensures clause";
        String usedBackend = "jvm";

        if (!ensSmt.isEmpty()) {
            SmtExpr ensConj = ensSmt.size() == 1 ? ensSmt.get(0) : new SmtExpr.And(ensSmt);
            SmtExpr vc = vcGen.buildFnPostconditionVc(targetFn, reqSmt, ensConj);
            ExternalZ3Solver z3Solver = new ExternalZ3Solver(jvmSolver.timeoutMs());
            SmtOutcome outcome = proveWithBackend(vc, smtBackend, jvmSolver, z3Solver);
            usedBackend = "z3".equals(smtBackend) && z3Solver.isAvailable() ? "z3" : "jvm";
            surfaceAStatus = switch (outcome.status()) {
                case PROVED -> "proved";
                case COUNTEREXAMPLE -> "counterexample";
                case TIMEOUT -> "timeout";
                case UNSUPPORTED -> "unsupported";
                case UNPROVEN -> "unproven";
            };
            surfaceADetail = outcome.detail();
        }

        // Surface B: vacuous pre, weak ensures, assume present
        List<String> surfaceBFindings = new ArrayList<>();
        List<Diagnostic> certDiags = new ArrayList<>(diagnostics);

        if (containsAssumeInFn(targetFn, specFns)) {
            surfaceBFindings.add("NGJ-CERT-ASSUME-PRESENT");
            certDiags.add(Diagnostic.error(
                "NGJ-CERT-ASSUME-PRESENT",
                "Certified symbol '" + qualifiedId + "' contains forbidden assume(...)",
                targetFn.span()
            ));
        }

        if (!reqSmt.isEmpty()) {
            SmtExpr reqConj = reqSmt.size() == 1 ? reqSmt.get(0) : new SmtExpr.And(reqSmt);
            SmtOutcome reqSat = jvmSolver.checkSat(reqConj);
            if (reqSat.status() == SmtOutcome.Status.PROVED) {
                surfaceBFindings.add("NGJ-CERT-VACUOUS-PRE");
                certDiags.add(Diagnostic.error(
                    "NGJ-CERT-VACUOUS-PRE",
                    "Precondition (requires) of '" + qualifiedId + "' is unsatisfiable (vacuous)",
                    targetFn.span()
                ));
            }
        }

        boolean weakEnsures = ensExprs.isEmpty() || isTrivialTrueEnsures(ensExprs, ensSmt, jvmSolver);
        if (weakEnsures) {
            surfaceBFindings.add("NGJ-CERT-WEAK-ENSURES");
            certDiags.add(Diagnostic.error(
                "NGJ-CERT-WEAK-ENSURES",
                "Postcondition (ensures) of '" + qualifiedId + "' is trivially weak or tautological",
                targetFn.span()
            ));
        }

        boolean surfaceBPassed = surfaceBFindings.isEmpty();

        // Surface C: Post2Exe + 2-call uniqueness + mutation PBT
        Post2ExeVerifier post2Exe = new Post2ExeVerifier(specFns, jvmSolver, typeAliases);
        Post2ExeReport surfaceCReport = post2Exe.verify(targetFn);
        boolean surfaceCPassed = surfaceCReport.passed() && surfaceCReport.uniquenessProved();

        if (!surfaceCPassed) {
            certDiags.add(Diagnostic.error(
                "NGJ-CERT-WEAK-ENSURES",
                "Surface C failed: strength=" + surfaceCReport.strengthClassification()
                    + ", uniqueness=" + surfaceCReport.uniquenessProved(),
                targetFn.span()
            ));
        }

        boolean certified = "proved".equals(surfaceAStatus) && surfaceBPassed && surfaceCPassed;

        SecretRedactionContext secretCtx = collectSecretRedactionContext();
        List<Diagnostic> redactedCertDiags = certDiags.stream()
            .map(d -> SecretRedactor.redactDiagnostic(
                d, secretCtx.allSecretKeys(), secretCtx.secretTypeNames(), secretCtx.secretLiterals()))
            .toList();

        return new CertifySpecResult(
            qualifiedId,
            certified,
            false,
            surfaceAStatus,
            usedBackend,
            surfaceADetail,
            surfaceBPassed,
            List.copyOf(surfaceBFindings),
            surfaceCReport,
            redactedCertDiags,
            null
        );
    }

    public BuildResult build(List<Path> files, String target) {
        return build(files, target, CheckOptions.defaults());
    }

    public BuildResult build(List<Path> files, String target, CheckOptions options) {
        var checkResult = check(files, options);

        if (checkResult.status() == CheckStatus.INVALID) {
            return new BuildResult(false, List.copyOf(diagnostics), null, "Compilation errors");
        }

        if (checkResult.status() == CheckStatus.INCOMPLETE
            || checkResult.status() == CheckStatus.INCOMPLETE_PROOF) {
            diagnostics.add(Diagnostic.error(
                "NGJ-BUILD-001",
                "Cannot build: source contains unresolved holes or proof holes",
                null
            ));
            return new BuildResult(false, List.copyOf(diagnostics), null, "Holes present");
        }

        if (!target.equals("valhalla") && !target.equals("valhalla-compat") && !target.equals("java25")) {
            diagnostics.add(Diagnostic.error(
                "NGJ-BUILD-002",
                "Unsupported target: " + target + ". Supported targets: valhalla, valhalla-compat, java25",
                null
            ));
            return new BuildResult(false, List.copyOf(diagnostics), null, "Unsupported target");
        }

        try {
            var generator = new Java25CodeGenerator();
            var outputFiles = new ArrayList<Path>();

            for (var unit : checkResult.units()) {
                var generatedFiles = generator.generate(unit);
                outputFiles.addAll(generatedFiles);
            }

            return new BuildResult(true, List.copyOf(diagnostics), outputFiles, null);
        } catch (CodeGenException e) {
            diagnostics.add(Diagnostic.error(
                "NGJ-CODEGEN-001",
                "Code generation failed: " + e.getMessage(),
                null
            ));
            return new BuildResult(false, List.copyOf(diagnostics), null, e.getMessage());
        }
    }

    private SmtOutcome proveWithBackend(SmtExpr vc, String smtBackend, PureJvmSmtSolver jvmSolver, ExternalZ3Solver z3Solver) {
        if ("z3".equals(smtBackend) || ("auto".equals(smtBackend) && z3Solver.isAvailable())) {
            SmtOutcome z3Res = z3Solver.prove(vc);
            if (z3Res.status() != SmtOutcome.Status.UNSUPPORTED) {
                return z3Res;
            }
        }
        return jvmSolver.prove(vc);
    }

    private boolean isTrivialTrueEnsures(List<Expr> ensExprs, List<SmtExpr> ensSmt, PureJvmSmtSolver solver) {
        for (Expr e : ensExprs) {
            if (e instanceof LiteralExpr lit && lit.kind() == LiteralExpr.LiteralKind.BOOL && Boolean.TRUE.equals(lit.value())) {
                return true;
            }
        }
        if (!ensSmt.isEmpty()) {
            SmtExpr conj = ensSmt.size() == 1 ? ensSmt.get(0) : new SmtExpr.And(ensSmt);
            SmtOutcome taut = solver.prove(conj);
            return taut.status() == SmtOutcome.Status.PROVED;
        }
        return false;
    }

    private boolean containsAssumeInFn(FnDecl fn, Map<String, SpecFnDecl> specFns) {
        if (containsAssume(fn.body())) {
            return true;
        }
        for (var c : fn.contracts()) {
            if (containsAssume(c.condition())) {
                return true;
            }
        }
        if (specFns != null && !specFns.isEmpty()) {
            Set<String> referenced = new LinkedHashSet<>();
            collectReferencedNames(fn.body(), referenced);
            for (var c : fn.contracts()) {
                collectReferencedNames(c.condition(), referenced);
            }
            Set<String> visitedSpecs = new LinkedHashSet<>();
            Deque<String> work = new ArrayDeque<>(referenced);
            while (!work.isEmpty()) {
                String name = work.poll();
                SpecFnDecl sf = specFns.get(name);
                if (sf != null && visitedSpecs.add(name)) {
                    if (containsAssume(sf.body())) {
                        return true;
                    }
                    Set<String> nested = new LinkedHashSet<>();
                    collectReferencedNames(sf.body(), nested);
                    work.addAll(nested);
                }
            }
        }
        return false;
    }

    private void collectReferencedNames(Expr expr, Set<String> names) {
        if (expr == null) return;
        switch (expr) {
            case IdentExpr id -> names.add(id.name());
            case LiteralExpr _, HoleExpr _ -> {}
            case BinaryExpr b -> {
                collectReferencedNames(b.left(), names);
                collectReferencedNames(b.right(), names);
            }
            case UnaryExpr u -> collectReferencedNames(u.operand(), names);
            case CallExpr c -> {
                collectReferencedNames(c.callee(), names);
                for (var a : c.args()) collectReferencedNames(a, names);
            }
            case FieldAccessExpr fa -> collectReferencedNames(fa.object(), names);
            case ConstructorExpr c -> {
                for (var f : c.fields()) collectReferencedNames(f.value(), names);
            }
            case IfExpr i -> {
                collectReferencedNames(i.condition(), names);
                collectReferencedNames(i.thenBranch(), names);
                collectReferencedNames(i.elseBranch(), names);
            }
            case MatchExpr m -> {
                collectReferencedNames(m.scrutinee(), names);
                for (var arm : m.arms()) collectReferencedNames(arm.body(), names);
            }
            case BlockExpr b -> {
                for (var s : b.statements()) collectReferencedNamesStmt(s, names);
                collectReferencedNames(b.tailExpr(), names);
            }
            case PropagateExpr p -> collectReferencedNames(p.inner(), names);
            case ParenExpr p -> collectReferencedNames(p.inner(), names);
            case ForExpr f -> {
                collectReferencedNames(f.iterable(), names);
                collectReferencedNames(f.body(), names);
            }
            case WhileExpr w -> {
                collectReferencedNames(w.condition(), names);
                collectReferencedNames(w.invariant(), names);
                collectReferencedNames(w.body(), names);
            }
            case LambdaExpr l -> collectReferencedNames(l.body(), names);
            case ScopeExpr s -> collectReferencedNames(s.body(), names);
            case ForkExpr f -> collectReferencedNames(f.body(), names);
            case ConsumeExpr c -> collectReferencedNames(c.target(), names);
            case TupleExpr t -> {
                for (var e : t.elements()) collectReferencedNames(e, names);
            }
        }
    }

    private void collectReferencedNamesStmt(Statement stmt, Set<String> names) {
        switch (stmt) {
            case LetStmt l -> collectReferencedNames(l.initializer(), names);
            case ValStmt v -> collectReferencedNames(v.initializer(), names);
            case VarStmt v -> collectReferencedNames(v.initializer(), names);
            case AssignStmt a -> collectReferencedNames(a.value(), names);
            case ExprStmt es -> collectReferencedNames(es.expr(), names);
            case ReturnStmt r -> collectReferencedNames(r.value(), names);
            case TupleDestructureStmt td -> collectReferencedNames(td.initializer(), names);
            case InvariantStmt inv -> collectReferencedNames(inv.condition(), names);
            case ProofStmt p -> collectReferencedNames(p.body(), names);
            case AssumeStmt a -> collectReferencedNames(a.condition(), names);
        }
    }

    private boolean containsAssume(Expr expr) {
        if (expr == null) return false;
        return switch (expr) {
            case LiteralExpr _, IdentExpr _, HoleExpr _ -> false;
            case BinaryExpr b -> containsAssume(b.left()) || containsAssume(b.right());
            case UnaryExpr u -> containsAssume(u.operand());
            case CallExpr c -> {
                if (containsAssume(c.callee())) yield true;
                for (var a : c.args()) {
                    if (containsAssume(a)) yield true;
                }
                yield false;
            }
            case FieldAccessExpr fa -> containsAssume(fa.object());
            case ConstructorExpr c -> {
                for (var f : c.fields()) {
                    if (containsAssume(f.value())) yield true;
                }
                yield false;
            }
            case IfExpr i -> containsAssume(i.condition()) || containsAssume(i.thenBranch()) || containsAssume(i.elseBranch());
            case MatchExpr m -> {
                if (containsAssume(m.scrutinee())) yield true;
                for (var arm : m.arms()) {
                    if (containsAssume(arm.body())) yield true;
                }
                yield false;
            }
            case BlockExpr b -> {
                for (var s : b.statements()) {
                    if (containsAssumeStmt(s)) yield true;
                }
                yield containsAssume(b.tailExpr());
            }
            case PropagateExpr p -> containsAssume(p.inner());
            case ParenExpr p -> containsAssume(p.inner());
            case ForExpr f -> containsAssume(f.iterable()) || containsAssume(f.body());
            case WhileExpr w -> containsAssume(w.condition()) || containsAssume(w.invariant()) || containsAssume(w.body());
            case LambdaExpr l -> containsAssume(l.body());
            case ScopeExpr s -> containsAssume(s.body());
            case ForkExpr f -> containsAssume(f.body());
            case ConsumeExpr c -> containsAssume(c.target());
            case TupleExpr t -> {
                for (var e : t.elements()) {
                    if (containsAssume(e)) yield true;
                }
                yield false;
            }
        };
    }

    private boolean containsAssumeStmt(Statement stmt) {
        return switch (stmt) {
            case AssumeStmt _ -> true;
            case ProofStmt p -> containsAssume(p.body());
            case ExprStmt es -> containsAssume(es.expr());
            case ValStmt v -> containsAssume(v.initializer());
            case LetStmt l -> containsAssume(l.initializer());
            case VarStmt v -> containsAssume(v.initializer());
            case AssignStmt a -> containsAssume(a.value());
            case ReturnStmt r -> containsAssume(r.value());
            case TupleDestructureStmt td -> containsAssume(td.initializer());
            case InvariantStmt inv -> containsAssume(inv.condition());
        };
    }

    private void enrichAndRedactDiagnostics() {
        SecretRedactionContext secretCtx = collectSecretRedactionContext();
        List<Diagnostic> enriched = new ArrayList<>(diagnostics.size());
        for (Diagnostic d : diagnostics) {
            Diagnostic cur = d;
            if (cur.exprSnippet() == null || cur.exprSnippet().isBlank()) {
                String snippet = extractSnippet(cur.primarySpan());
                if (snippet != null && !snippet.isBlank()) {
                    cur = cur.withExprSnippet(maskIfSecretSpan(snippet, cur.primarySpan(), secretCtx));
                }
            } else if (secretCtx.isSpanInsideSecretValue(cur.primarySpan())) {
                cur = cur.withExprSnippet(maskIfSecretSpan(cur.exprSnippet(), cur.primarySpan(), secretCtx));
            }
            if (cur.derivationTrace().isEmpty() && cur.primarySpan() != null) {
                String snippet = cur.exprSnippet() != null ? cur.exprSnippet() : "";
                cur = cur.withDerivationTrace(List.of(
                    new DerivationStep(1, cur.primarySpan(), snippet, cur.message())
                ));
            } else if (!cur.derivationTrace().isEmpty()) {
                List<DerivationStep> maskedSteps = cur.derivationTrace().stream()
                    .map(step -> new DerivationStep(
                        step.step(),
                        step.span(),
                        maskIfSecretSpan(step.exprSnippet(), step.span(), secretCtx),
                        step.reason()
                    ))
                    .toList();
                cur = cur.withDerivationTrace(maskedSteps);
            }
            if (cur.obligationId() == null) {
                String sym = cur.symbolId() != null ? cur.symbolId() : "module";
                cur = cur.withObligationId(cur.id().toLowerCase(Locale.ROOT) + ":" + sym);
            }
            if ("NGJ-EFFECT-001".equals(cur.id()) && cur.suggestions().isEmpty()) {
                cur = cur.withSuggestions(List.of(new Suggestion(
                    "review_call",
                    "Use an operation within the existing authority boundary or review declared effects",
                    Suggestion.REQUIRES_REVIEW
                )));
            }
            Set<String> fnSecretKeys = findSecretParamsForDiagnostic(cur, secretCtx);
            if (cur.solverDiagnosis() != null && !fnSecretKeys.isEmpty()) {
                Map<String, String> state = new LinkedHashMap<>(cur.solverDiagnosis().counterexampleState());
                for (String sk : fnSecretKeys) {
                    state.put(sk, SecretRedactor.MASK);
                }
                cur = cur.withSolverDiagnosis(new SolverDiagnosis(
                    cur.solverDiagnosis().failureClass(),
                    cur.solverDiagnosis().suggestedHintAnchor(),
                    state
                ));
            }
            Set<String> redactionKeys = new LinkedHashSet<>(fnSecretKeys);
            redactionKeys.addAll(secretCtx.allSecretKeys());
            enriched.add(SecretRedactor.redactDiagnostic(
                cur,
                redactionKeys,
                secretCtx.secretTypeNames(),
                secretCtx.secretLiterals()
            ));
        }
        diagnostics.clear();
        diagnostics.addAll(enriched);
    }

    private String maskIfSecretSpan(String snippet, SourceSpan span, SecretRedactionContext secretCtx) {
        if (snippet == null || !secretCtx.isSpanInsideSecretValue(span)) {
            return snippet;
        }
        String trimmed = snippet.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return "\"" + SecretRedactor.MASK + "\"";
        }
        return SecretRedactor.MASK;
    }

    private Map<String, TypeAliasDecl> collectTypeAliases() {
        Map<String, TypeAliasDecl> aliases = new LinkedHashMap<>();
        for (var unit : parsedUnits.values()) {
            for (var decl : unit.declarations()) {
                if (decl instanceof TypeAliasDecl ta) {
                    aliases.put(ta.name(), ta);
                }
            }
        }
        return aliases;
    }

    private record SecretRedactionContext(
        Map<String, TypeAliasDecl> typeAliases,
        Set<String> secretTypeNames,
        Set<String> allSecretKeys,
        Set<String> secretLiterals,
        List<SourceSpan> secretValueSpans
    ) {
        boolean isSpanInsideSecretValue(SourceSpan span) {
            if (span == null || span.file() == null) return false;
            for (SourceSpan sv : secretValueSpans) {
                if (sv != null && Objects.equals(span.file(), sv.file())
                    && span.startByte() >= sv.startByte()
                    && span.endByte() <= sv.endByte()) {
                    return true;
                }
            }
            return false;
        }
    }

    private SecretRedactionContext collectSecretRedactionContext() {
        Map<String, TypeAliasDecl> aliases = collectTypeAliases();
        Map<String, FnDecl> fnDecls = new LinkedHashMap<>();
        Map<String, TopLevelDecl> typeDecls = new LinkedHashMap<>();
        for (var unit : parsedUnits.values()) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case FnDecl fn -> fnDecls.put(fn.name(), fn);
                    case RecordDecl r -> typeDecls.put(r.name(), r);
                    case DataDecl d -> typeDecls.put(d.name(), d);
                    case EnumDecl e -> typeDecls.put(e.name(), e);
                    default -> {}
                }
            }
        }

        Set<String> secretTypeNames = new LinkedHashSet<>();
        secretTypeNames.add("Secret");
        for (var alias : aliases.values()) {
            if (isSecretTypeExpr(alias.targetType(), aliases, new LinkedHashSet<>())) {
                secretTypeNames.add(alias.name());
            }
        }

        Set<String> allSecretKeys = new LinkedHashSet<>();
        Set<String> secretLiterals = new LinkedHashSet<>();
        List<SourceSpan> secretValueSpans = new ArrayList<>();

        for (var decl : typeDecls.values()) {
            if (decl instanceof RecordDecl r) {
                for (var p : r.params()) {
                    if (isSecretTypeExpr(p.type(), aliases, new LinkedHashSet<>())) {
                        allSecretKeys.add(p.name());
                    }
                }
            } else if (decl instanceof DataDecl d) {
                for (var f : d.fields()) {
                    if (isSecretTypeExpr(f.type(), aliases, new LinkedHashSet<>())) {
                        allSecretKeys.add(f.name());
                    }
                }
            } else if (decl instanceof EnumDecl e) {
                for (var v : e.variants()) {
                    for (var f : v.fields()) {
                        if (isSecretTypeExpr(f.type(), aliases, new LinkedHashSet<>())) {
                            allSecretKeys.add(f.name());
                        }
                    }
                }
            }
        }

        for (var unit : parsedUnits.values()) {
            for (var decl : unit.declarations()) {
                if (decl instanceof FnDecl fn) {
                    Set<String> fnSecrets = new LinkedHashSet<>();
                    for (var p : fn.params()) {
                        if (SecretRedactor.isSecretVariableName(p.name())
                            || isSecretTypeExpr(p.type(), aliases, new LinkedHashSet<>())) {
                            fnSecrets.add(p.name());
                            allSecretKeys.add(p.name());
                        }
                    }
                    boolean secretReturn = isSecretTypeExpr(fn.returnType(), aliases, new LinkedHashSet<>());
                    if (fn.body() != null) {
                        collectSecretsInBlock(
                            fn.body(), aliases, fnDecls, typeDecls,
                            fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans
                        );
                    }
                }
            }
        }

        return new SecretRedactionContext(aliases, secretTypeNames, allSecretKeys, secretLiterals, secretValueSpans);
    }

    private void collectSecretsInBlock(
        BlockExpr block,
        Map<String, TypeAliasDecl> aliases,
        Map<String, FnDecl> fnDecls,
        Map<String, TopLevelDecl> typeDecls,
        Set<String> fnSecrets,
        boolean secretReturn,
        Set<String> allSecretKeys,
        Set<String> secretLiterals,
        List<SourceSpan> secretValueSpans
    ) {
        if (block == null) return;
        for (var stmt : block.statements()) {
            switch (stmt) {
                case ValStmt v -> {
                    boolean sec = SecretRedactor.isSecretVariableName(v.name())
                        || isSecretTypeExpr(v.explicitType(), aliases, new LinkedHashSet<>());
                    if (sec) {
                        fnSecrets.add(v.name());
                        allSecretKeys.add(v.name());
                        recordSecretExprLiterals(v.initializer(), secretLiterals, secretValueSpans);
                    }
                    collectSecretsInExpr(v.initializer(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                }
                case LetStmt l -> {
                    boolean sec = SecretRedactor.isSecretVariableName(l.name())
                        || isSecretTypeExpr(l.explicitType(), aliases, new LinkedHashSet<>());
                    if (sec) {
                        fnSecrets.add(l.name());
                        allSecretKeys.add(l.name());
                        recordSecretExprLiterals(l.initializer(), secretLiterals, secretValueSpans);
                    }
                    collectSecretsInExpr(l.initializer(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                }
                case VarStmt v -> {
                    boolean sec = SecretRedactor.isSecretVariableName(v.name())
                        || isSecretTypeExpr(v.explicitType(), aliases, new LinkedHashSet<>());
                    if (sec) {
                        fnSecrets.add(v.name());
                        allSecretKeys.add(v.name());
                        recordSecretExprLiterals(v.initializer(), secretLiterals, secretValueSpans);
                    }
                    collectSecretsInExpr(v.initializer(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                }
                case AssignStmt a -> {
                    if (fnSecrets.contains(a.name()) || SecretRedactor.isSecretVariableName(a.name())) {
                        recordSecretExprLiterals(a.value(), secretLiterals, secretValueSpans);
                    }
                    collectSecretsInExpr(a.value(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                }
                case TupleDestructureStmt td -> {
                    TupleExpr te = td.initializer() instanceof TupleExpr t ? t : null;
                    for (int i = 0; i < td.names().size(); i++) {
                        String n = td.names().get(i);
                        TypeExpr explicitT = i < td.explicitTypes().size() ? td.explicitTypes().get(i) : null;
                        if (SecretRedactor.isSecretVariableName(n) || isSecretTypeExpr(explicitT, aliases, new LinkedHashSet<>())) {
                            fnSecrets.add(n);
                            allSecretKeys.add(n);
                            if (te != null && i < te.elements().size()) {
                                recordSecretExprLiterals(te.elements().get(i), secretLiterals, secretValueSpans);
                            }
                        }
                    }
                    collectSecretsInExpr(td.initializer(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                }
                case ReturnStmt r -> {
                    if (r.value() != null) {
                        if (secretReturn) {
                            recordSecretExprLiterals(r.value(), secretLiterals, secretValueSpans);
                        }
                        collectSecretsInExpr(r.value(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                    }
                }
                case ExprStmt es -> collectSecretsInExpr(es.expr(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                default -> {}
            }
        }
        if (block.tailExpr() != null) {
            if (secretReturn) {
                recordSecretExprLiterals(block.tailExpr(), secretLiterals, secretValueSpans);
            }
            collectSecretsInExpr(block.tailExpr(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
        }
    }

    private void collectSecretsInExpr(
        Expr expr,
        Map<String, TypeAliasDecl> aliases,
        Map<String, FnDecl> fnDecls,
        Map<String, TopLevelDecl> typeDecls,
        Set<String> fnSecrets,
        boolean secretReturn,
        Set<String> allSecretKeys,
        Set<String> secretLiterals,
        List<SourceSpan> secretValueSpans
    ) {
        if (expr == null) return;
        switch (expr) {
            case CallExpr c -> {
                if (c.callee() instanceof FieldAccessExpr fa
                    && fa.object() instanceof IdentExpr obj
                    && obj.name().equals("Secret")
                    && fa.fieldName().equals("of")) {
                    for (var arg : c.args()) {
                        recordSecretExprLiterals(arg, secretLiterals, secretValueSpans);
                    }
                } else if (c.callee() instanceof IdentExpr id) {
                    FnDecl fn = fnDecls.get(id.name());
                    if (fn != null) {
                        for (int i = 0; i < Math.min(fn.params().size(), c.args().size()); i++) {
                            if (isSecretTypeExpr(fn.params().get(i).type(), aliases, new LinkedHashSet<>())) {
                                recordSecretExprLiterals(c.args().get(i), secretLiterals, secretValueSpans);
                            }
                        }
                    }
                    TopLevelDecl td = typeDecls.get(id.name());
                    if (td instanceof RecordDecl r) {
                        for (int i = 0; i < Math.min(r.params().size(), c.args().size()); i++) {
                            if (isSecretTypeExpr(r.params().get(i).type(), aliases, new LinkedHashSet<>())) {
                                recordSecretExprLiterals(c.args().get(i), secretLiterals, secretValueSpans);
                            }
                        }
                    }
                }
                collectSecretsInExpr(c.callee(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                for (var arg : c.args()) {
                    collectSecretsInExpr(arg, aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                }
            }
            case ConstructorExpr c -> {
                String shortName = c.typePath().contains(".")
                    ? c.typePath().substring(c.typePath().lastIndexOf('.') + 1)
                    : c.typePath();
                TopLevelDecl td = typeDecls.get(shortName);
                for (var f : c.fields()) {
                    if (isSecretField(td, f.name(), aliases)) {
                        recordSecretExprLiterals(f.value(), secretLiterals, secretValueSpans);
                    }
                    collectSecretsInExpr(f.value(), aliases, fnDecls, typeDecls, fnSecrets, secretReturn, allSecretKeys, secretLiterals, secretValueSpans);
                }
            }
            case BlockExpr b -> collectSecretsInBlock(b, aliases, fnDecls, typeDecls, new LinkedHashSet<>(fnSecrets), false, allSecretKeys, secretLiterals, secretValueSpans);
            case IfExpr i -> {
                collectSecretsInExpr(i.condition(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
                collectSecretsInBlock(i.thenBranch(), aliases, fnDecls, typeDecls, new LinkedHashSet<>(fnSecrets), false, allSecretKeys, secretLiterals, secretValueSpans);
                collectSecretsInExpr(i.elseBranch(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
            }
            case MatchExpr m -> {
                collectSecretsInExpr(m.scrutinee(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
                for (var arm : m.arms()) {
                    collectSecretsInExpr(arm.body(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
                }
            }
            case WhileExpr w -> {
                collectSecretsInExpr(w.condition(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
                collectSecretsInBlock(w.body(), aliases, fnDecls, typeDecls, new LinkedHashSet<>(fnSecrets), false, allSecretKeys, secretLiterals, secretValueSpans);
            }
            case ForExpr f -> {
                collectSecretsInExpr(f.iterable(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
                collectSecretsInBlock(f.body(), aliases, fnDecls, typeDecls, new LinkedHashSet<>(fnSecrets), false, allSecretKeys, secretLiterals, secretValueSpans);
            }
            case ScopeExpr s -> collectSecretsInBlock(s.body(), aliases, fnDecls, typeDecls, new LinkedHashSet<>(fnSecrets), false, allSecretKeys, secretLiterals, secretValueSpans);
            case ForkExpr f -> collectSecretsInBlock(f.body(), aliases, fnDecls, typeDecls, new LinkedHashSet<>(fnSecrets), false, allSecretKeys, secretLiterals, secretValueSpans);
            case LambdaExpr l -> collectSecretsInBlock(l.body(), aliases, fnDecls, typeDecls, new LinkedHashSet<>(fnSecrets), false, allSecretKeys, secretLiterals, secretValueSpans);
            case BinaryExpr b -> {
                collectSecretsInExpr(b.left(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
                collectSecretsInExpr(b.right(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
            }
            case UnaryExpr u -> collectSecretsInExpr(u.operand(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
            case ParenExpr p -> collectSecretsInExpr(p.inner(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
            case PropagateExpr p -> collectSecretsInExpr(p.inner(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
            case ConsumeExpr c -> collectSecretsInExpr(c.target(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
            case FieldAccessExpr fa -> collectSecretsInExpr(fa.object(), aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
            case TupleExpr t -> {
                for (var e : t.elements()) {
                    collectSecretsInExpr(e, aliases, fnDecls, typeDecls, fnSecrets, false, allSecretKeys, secretLiterals, secretValueSpans);
                }
            }
            default -> {}
        }
    }

    private boolean isSecretField(TopLevelDecl td, String fieldName, Map<String, TypeAliasDecl> aliases) {
        if (td == null || fieldName == null) return false;
        if (td instanceof RecordDecl r) {
            for (var p : r.params()) {
                if (p.name().equals(fieldName)) return isSecretTypeExpr(p.type(), aliases, new LinkedHashSet<>());
            }
        } else if (td instanceof DataDecl d) {
            for (var f : d.fields()) {
                if (f.name().equals(fieldName)) return isSecretTypeExpr(f.type(), aliases, new LinkedHashSet<>());
            }
        }
        return false;
    }

    private void recordSecretExprLiterals(Expr expr, Set<String> secretLiterals, List<SourceSpan> secretValueSpans) {
        if (expr == null) return;
        switch (expr) {
            case LiteralExpr lit -> {
                if (lit.span() != null) {
                    secretValueSpans.add(lit.span());
                }
                if (lit.kind() == LiteralExpr.LiteralKind.STRING && lit.value() instanceof String s && !s.isEmpty()) {
                    secretLiterals.add(s);
                }
            }
            case ParenExpr p -> recordSecretExprLiterals(p.inner(), secretLiterals, secretValueSpans);
            case UnaryExpr u -> {
                if (u.span() != null && u.operand() instanceof LiteralExpr) {
                    secretValueSpans.add(u.span());
                }
                recordSecretExprLiterals(u.operand(), secretLiterals, secretValueSpans);
            }
            case CallExpr c -> {
                for (var a : c.args()) recordSecretExprLiterals(a, secretLiterals, secretValueSpans);
            }
            case TupleExpr t -> {
                for (var e : t.elements()) recordSecretExprLiterals(e, secretLiterals, secretValueSpans);
            }
            default -> {}
        }
    }

    private boolean isSecretTypeExpr(TypeExpr type, Map<String, TypeAliasDecl> aliases, Set<String> seen) {
        if (type == null) return false;
        return switch (type) {
            case SimpleTypeExpr s -> {
                if (s.name().equals("Secret")) yield true;
                if (aliases.containsKey(s.name()) && seen.add(s.name())) {
                    boolean res = isSecretTypeExpr(aliases.get(s.name()).targetType(), aliases, seen);
                    seen.remove(s.name());
                    yield res;
                }
                yield false;
            }
            case GenericTypeExpr g -> {
                if (g.name().equals("Secret")) yield true;
                if (aliases.containsKey(g.name()) && seen.add(g.name())) {
                    TypeAliasDecl alias = aliases.get(g.name());
                    Map<String, TypeExpr> subst = new HashMap<>();
                    for (int i = 0; i < Math.min(alias.typeParams().size(), g.typeArgs().size()); i++) {
                        subst.put(alias.typeParams().get(i), g.typeArgs().get(i));
                    }
                    TypeExpr substituted = substituteAliasTypeParams(alias.targetType(), subst);
                    boolean res = isSecretTypeExpr(substituted, aliases, seen);
                    seen.remove(g.name());
                    yield res;
                }
                for (var arg : g.typeArgs()) {
                    if (isSecretTypeExpr(arg, aliases, seen)) yield true;
                }
                yield false;
            }
            case CapabilityTypeExpr c -> isSecretTypeExpr(c.inner(), aliases, seen);
            case RefinedTypeExpr r -> isSecretTypeExpr(r.baseType(), aliases, seen);
            case RangeTypeExpr r -> isSecretTypeExpr(r.baseType(), aliases, seen);
            default -> false;
        };
    }

    private TypeExpr substituteAliasTypeParams(TypeExpr type, Map<String, TypeExpr> subst) {
        if (type == null || subst.isEmpty()) return type;
        return switch (type) {
            case SimpleTypeExpr s -> subst.getOrDefault(s.name(), s);
            case GenericTypeExpr g -> new GenericTypeExpr(
                g.name(),
                g.typeArgs().stream().map(a -> substituteAliasTypeParams(a, subst)).toList(),
                g.span()
            );
            case CapabilityTypeExpr c -> new CapabilityTypeExpr(c.mode(), substituteAliasTypeParams(c.inner(), subst), c.span());
            case RefinedTypeExpr r -> new RefinedTypeExpr(r.binderName(), substituteAliasTypeParams(r.baseType(), subst), r.predicate(), r.span());
            case RangeTypeExpr r -> new RangeTypeExpr(substituteAliasTypeParams(r.baseType(), subst), r.lowerBound(), r.upperBound(), r.upperInclusive(), r.span());
            default -> type;
        };
    }

    private Set<String> findSecretParamsForDiagnostic(Diagnostic d, SecretRedactionContext secretCtx) {
        Set<String> keys = new LinkedHashSet<>();
        String symbolId = d.symbolId();
        SourceSpan span = d.primarySpan();
        Map<String, TypeAliasDecl> aliases = secretCtx.typeAliases();
        for (var unit : parsedUnits.values()) {
            String mod = unit.modulePath();
            for (var decl : unit.declarations()) {
                if (decl instanceof FnDecl fn) {
                    boolean symMatch = symbolId != null
                        && (symbolId.equals(fn.name()) || symbolId.equals(mod + "." + fn.name()));
                    boolean spanMatch = span != null && fn.span() != null
                        && Objects.equals(span.file(), fn.span().file())
                        && span.startByte() >= fn.span().startByte()
                        && span.endByte() <= fn.span().endByte();
                    if (symMatch || spanMatch) {
                        for (var p : fn.params()) {
                            if (SecretRedactor.isSecretVariableName(p.name())
                                || isSecretTypeExpr(p.type(), aliases, new LinkedHashSet<>())) {
                                keys.add(p.name());
                            }
                        }
                    }
                }
            }
        }
        return keys;
    }

    private String extractSnippet(SourceSpan span) {
        if (span == null || span.file() == null) return null;
        byte[] bytes = sourceUtf8ByFile.get(span.file());
        if (bytes == null) return null;
        int start = Math.max(0, Math.min(span.startByte(), bytes.length));
        int end = Math.max(start, Math.min(span.endByte(), bytes.length));
        if (end <= start) return null;
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }

    private boolean hasDefiniteErrors() {
        return diagnostics.stream().anyMatch(d ->
            d.severity() == Severity.ERROR && d.certainty() == Certainty.DEFINITE);
    }

    private boolean hasCertainty(Certainty certainty) {
        return diagnostics.stream().anyMatch(d -> d.certainty() == certainty);
    }

    private boolean hasCodeHoles(List<CompilationUnit> units) {
        return new ObligationCollector().collect(units, List.of()).stream()
            .anyMatch(o -> "typed_hole".equals(o.kind()));
    }

    private boolean hasProofHoles(List<CompilationUnit> units) {
        return new ObligationCollector().collect(units, List.of()).stream()
            .anyMatch(o -> "proof_hole".equals(o.kind()));
    }

    private HoleExpr findHole(CompilationUnit unit, String holeId) {
        return new HoleFinder().findHole(unit, holeId);
    }

    private SymbolInfo findSymbol(CompilationUnit unit, String symbolId) {
        return new SymbolFinder().find(unit, symbolId);
    }

    private ContextResult buildContext(
        HoleExpr hole,
        boolean proofHoleMode,
        int maxBytes,
        String sourceRevision,
        String policyDigest,
        String targetProfile
    ) {
        List<RelevantSymbol> symbols = new ArrayList<>();
        for (var unit : parsedUnits.values()) {
            String mod = unit.modulePath();
            for (var decl : unit.declarations()) {
                if (decl instanceof FnDecl fn) {
                    symbols.add(new RelevantSymbol(
                        mod + "." + fn.name(),
                        fn.signature(),
                        "workspace",
                        "sha256:" + AstDigest.sha256(fn)
                    ));
                } else if (proofHoleMode && decl instanceof SpecFnDecl sf) {
                    String sig = (sf.isPredicate() ? "spec predicate " : "spec fn ")
                        + sf.name() + "(" + formatParams(sf.params()) + ")"
                        + (sf.returnType() != null ? " -> " + sf.returnType() : "");
                    symbols.add(new RelevantSymbol(
                        mod + "." + sf.name(),
                        sig,
                        "spec",
                        "sha256:" + AstDigest.sha256(sf)
                    ));
                } else if (proofHoleMode && decl instanceof LemmaDecl lm) {
                    String sig = "lemma " + lm.name() + "(" + formatParams(lm.params()) + ")";
                    symbols.add(new RelevantSymbol(
                        mod + "." + lm.name(),
                        sig,
                        "lemma",
                        "sha256:" + AstDigest.sha256(lm)
                    ));
                }
            }
        }

        List<ContextObligation> obligations = new ArrayList<>();
        boolean hasCapture = (hole.expectedType() != null && hole.expectedType().toString().contains("caps("))
            || hole.bindings().stream().anyMatch(b -> b.type() != null
                && (b.type().toString().contains("caps(") || b.type().toString().contains("Arena")));
        if (hasCapture) {
            obligations.add(new ContextObligation(
                "capture_lifetime",
                "Returned or captured views cannot outlive their owning capability/arena"
            ));
        }
        if (proofHoleMode || hole.isProofHole()) {
            obligations.add(new ContextObligation(
                "proof_hole",
                "Complete proof obligation for ?" + hole.name()
            ));
        }

        // Deterministic --max-bytes truncation
        int omitted = 0;
        boolean truncated = false;
        List<RelevantSymbol> kept = new ArrayList<>(symbols);
        HoleContext ctx = new HoleContext(
            hole.name(),
            hole.expectedType(),
            hole.allowedEffects(),
            hole.bindings(),
            kept,
            obligations,
            false,
            0
        );

        if (maxBytes > 0) {
            while (!kept.isEmpty()
                && ctx.toJson(sourceRevision, policyDigest, targetProfile).getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                kept.remove(kept.size() - 1);
                omitted++;
                truncated = true;
                ctx = new HoleContext(
                    hole.name(),
                    hole.expectedType(),
                    hole.allowedEffects(),
                    hole.bindings(),
                    kept,
                    obligations,
                    truncated,
                    omitted
                );
            }
        }

        return new ContextResult(ctx, null);
    }

    private static String formatParams(List<Param> params) {
        var sb = new StringBuilder();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            var p = params.get(i);
            if (p.isConsume()) sb.append("consume ");
            sb.append(p.name()).append(": ").append(p.type());
        }
        return sb.toString();
    }
}
