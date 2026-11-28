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

        if (targetFn == null) {
            return CertifySpecResult.badInput("Symbol not found: " + symbolId);
        }

        if (new HoleFinder().hasHoles(new CompilationUnit(
            new ModuleDecl("tmp", null), List.of(), List.of(targetFn), null))) {
            return new CertifySpecResult(
                qualifiedId, false, true, "unproven", smtBackend, "Holes present in symbol",
                false, List.of(), null, List.copyOf(diagnostics), null
            );
        }

        PureJvmSmtSolver jvmSolver = new PureJvmSmtSolver();
        Map<String, SpecFnDecl> specFns = lastTypedIR.specFunctions();
        VcGenerator vcGen = new VcGenerator(specFns);

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
        List<Diagnostic> certDiags = new ArrayList<>();

        if (containsAssume(targetFn.body())) {
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
        Post2ExeVerifier post2Exe = new Post2ExeVerifier(specFns, jvmSolver);
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
            List.copyOf(certDiags),
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

    private boolean containsAssume(Expr expr) {
        if (expr == null) return false;
        return switch (expr) {
            case BlockExpr b -> {
                for (var s : b.statements()) {
                    if (s instanceof AssumeStmt) yield true;
                    if (s instanceof ProofStmt p && containsAssume(p.body())) yield true;
                }
                yield containsAssume(b.tailExpr());
            }
            case IfExpr i -> containsAssume(i.condition()) || containsAssume(i.thenBranch()) || containsAssume(i.elseBranch());
            case WhileExpr w -> containsAssume(w.body());
            default -> false;
        };
    }

    private void enrichAndRedactDiagnostics() {
        List<Diagnostic> enriched = new ArrayList<>(diagnostics.size());
        for (Diagnostic d : diagnostics) {
            Diagnostic cur = d;
            if (cur.exprSnippet() == null || cur.exprSnippet().isBlank()) {
                String snippet = extractSnippet(cur.primarySpan());
                if (snippet != null && !snippet.isBlank()) {
                    cur = cur.withExprSnippet(snippet);
                }
            }
            if (cur.derivationTrace().isEmpty() && cur.primarySpan() != null) {
                String snippet = cur.exprSnippet() != null ? cur.exprSnippet() : "";
                cur = cur.withDerivationTrace(List.of(
                    new DerivationStep(1, cur.primarySpan(), snippet, cur.message())
                ));
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
            Set<String> secretKeys = findSecretParamsForDiagnostic(cur);
            if (cur.solverDiagnosis() != null && !secretKeys.isEmpty()) {
                Map<String, String> state = new LinkedHashMap<>(cur.solverDiagnosis().counterexampleState());
                for (String sk : secretKeys) {
                    state.put(sk, SecretRedactor.MASK);
                }
                cur = cur.withSolverDiagnosis(new SolverDiagnosis(
                    cur.solverDiagnosis().failureClass(),
                    cur.solverDiagnosis().suggestedHintAnchor(),
                    state
                ));
            }
            enriched.add(SecretRedactor.redactDiagnostic(cur, secretKeys));
        }
        diagnostics.clear();
        diagnostics.addAll(enriched);
    }

    private Set<String> findSecretParamsForDiagnostic(Diagnostic d) {
        Set<String> keys = new LinkedHashSet<>();
        String symbolId = d.symbolId();
        SourceSpan span = d.primarySpan();
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
                                || (p.type() != null && p.type().toString().startsWith("Secret<"))) {
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
