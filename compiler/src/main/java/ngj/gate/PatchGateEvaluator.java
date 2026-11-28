package ngj.gate;

import ngj.CheckResult;
import ngj.CheckStatus;
import ngj.Compiler;
import ngj.PatchCheckResult;
import ngj.ast.*;
import ngj.diagnostic.Diagnostic;
import ngj.diagnostic.SourceSpan;
import ngj.typechecker.TypeChecker.CheckOptions;

import java.nio.file.Path;
import java.util.*;

/**
 * Evaluates Trust-First mechanical gates in-memory using {@link PatchApplier}
 * without mutating any files on disk.
 */
public final class PatchGateEvaluator {

    private static final Set<String> FORBIDDEN_ANNOTATIONS = Set.of(
        "SuppressWarnings", "unchecked", "skip_verify", "reflect", "jni"
    );
    private static final Set<String> AXIOM_ANNOTATIONS = Set.of(
        "trusted_pure", "java_adapter", "unsafe"
    );

    public PatchCheckResult evaluate(
        Map<Path, String> baseWorkspace,
        String unifiedDiff,
        String policy,
        Set<String> frozenSpecs,
        boolean requireComplete,
        CheckOptions checkOptions
    ) {
        // 1. Map workspace paths to safe relative keys for PatchApplier
        Map<String, Path> keyToPath = new LinkedHashMap<>();
        Map<String, String> virtualFiles = new LinkedHashMap<>();
        Map<String, String> pathAliasToKey = new LinkedHashMap<>();

        int idx = 0;
        for (var entry : baseWorkspace.entrySet()) {
            Path p = entry.getKey();
            String fileName = p.getFileName().toString();
            String safeKey = "w" + (idx++) + "/" + fileName;
            keyToPath.put(safeKey, p);
            virtualFiles.put(safeKey, entry.getValue());
            pathAliasToKey.put(p.toString(), safeKey);
            pathAliasToKey.put(p.toAbsolutePath().normalize().toString(), safeKey);
            pathAliasToKey.put(fileName, safeKey);
        }

        String normalizedDiff = rewriteDiffHeaders(unifiedDiff, pathAliasToKey);
        PatchApplier.PatchResult patchResult = PatchApplier.apply(virtualFiles, normalizedDiff);
        if (!patchResult.applied() && patchResult.errorDetail().contains("expected '', found '<EOF>'")) {
            String trimmedDiff = stripTrailingEmptyHunkLines(normalizedDiff);
            PatchApplier.PatchResult retry = PatchApplier.apply(virtualFiles, trimmedDiff);
            if (retry.applied()) {
                patchResult = retry;
            }
        }
        if (!patchResult.applied()) {
            return PatchCheckResult.badInput("Patch failed to apply: " + patchResult.errorDetail());
        }

        Map<Path, String> patchedWorkspace = new LinkedHashMap<>();
        for (var entry : patchResult.updatedFiles().entrySet()) {
            Path origPath = keyToPath.getOrDefault(entry.getKey(), Path.of(entry.getKey()));
            patchedWorkspace.put(origPath, entry.getValue());
        }

        // 2. Check base and patched workspaces in-memory
        Compiler baseCompiler = new Compiler();
        CheckResult baseCheck = baseCompiler.checkVirtual(baseWorkspace, checkOptions);

        Compiler patchedCompiler = new Compiler();
        CheckResult patchedCheck = patchedCompiler.checkVirtual(patchedWorkspace, checkOptions);

        List<Diagnostic> gateDiagnostics = new ArrayList<>();
        Map<String, Boolean> gates = new LinkedHashMap<>();

        boolean strict = "strict-gates".equals(policy) || policy == null || policy.isBlank();

        // Gate 1: spec-drift (NGJ-GATE-SPEC-DRIFT)
        List<Diagnostic> specDriftDiags = checkSpecDrift(baseCheck.units(), patchedCheck.units(), frozenSpecs);
        gates.put("spec_drift", specDriftDiags.isEmpty());
        gateDiagnostics.addAll(specDriftDiags);

        // Gate 2: axiom-drift (NGJ-GATE-AXIOM-DRIFT)
        List<Diagnostic> axiomDriftDiags = checkAxiomDrift(patchedCheck.units(), checkOptions);
        gates.put("axiom_drift", axiomDriftDiags.isEmpty());
        gateDiagnostics.addAll(axiomDriftDiags);

        // Gate 3: forbidden-construct (NGJ-GATE-FORBIDDEN)
        List<Diagnostic> forbiddenDiags = checkForbiddenConstructs(patchedCheck.units());
        gates.put("forbidden_construct", forbiddenDiags.isEmpty());
        gateDiagnostics.addAll(forbiddenDiags);

        // Gate 4: sibling-check (NGJ-GATE-SIBLING)
        List<Diagnostic> siblingDiags = new ArrayList<>();
        if (patchedCheck.status() == CheckStatus.INVALID) {
            siblingDiags.add(Diagnostic.error(
                "NGJ-GATE-SIBLING",
                "Patched workspace failed type/contract/sibling check across workspace modules",
                firstErrorSpan(patchedCheck.diagnostics())
            ));
            siblingDiags.addAll(patchedCheck.diagnostics());
        }
        gates.put("sibling_check", siblingDiags.isEmpty());
        gateDiagnostics.addAll(siblingDiags);

        // Gate 5: obligation-monotonicity (NGJ-GATE-OBLIGATION)
        ObligationCollector collector = new ObligationCollector();
        var baseObl = collector.collect(baseCheck.units(), baseCheck.diagnostics());
        var patchedObl = collector.collect(patchedCheck.units(), patchedCheck.diagnostics());
        int baseUnresolved = collector.countUnresolved(baseObl);
        int patchedUnresolved = collector.countUnresolved(patchedObl);

        List<Diagnostic> oblDiags = new ArrayList<>();
        if (patchedUnresolved > baseUnresolved) {
            oblDiags.add(Diagnostic.error(
                "NGJ-GATE-OBLIGATION",
                "Unresolved obligations increased from " + baseUnresolved + " to " + patchedUnresolved,
                firstHoleSpan(patchedCheck.units())
            ));
        } else if (requireComplete && patchedUnresolved > 0) {
            oblDiags.add(Diagnostic.error(
                "NGJ-GATE-OBLIGATION",
                "Patch leaves " + patchedUnresolved + " unresolved obligation(s) with --require-complete",
                firstHoleSpan(patchedCheck.units())
            ));
        }
        gates.put("obligation_monotonicity", oblDiags.isEmpty());
        gateDiagnostics.addAll(oblDiags);

        boolean anyGateFailed = gates.values().stream().anyMatch(passed -> !passed);
        String status;
        if (anyGateFailed && strict) {
            status = "invalid";
        } else if (patchedCheck.status() == CheckStatus.INVALID) {
            status = "invalid";
        } else if (patchedUnresolved > 0 || patchedCheck.status() == CheckStatus.INCOMPLETE
            || patchedCheck.status() == CheckStatus.INCOMPLETE_PROOF) {
            status = "incomplete";
        } else {
            status = "passed";
        }

        return new PatchCheckResult(
            status,
            Collections.unmodifiableMap(gates),
            Collections.unmodifiableList(gateDiagnostics),
            baseUnresolved,
            patchedUnresolved,
            null
        );
    }

    private List<Diagnostic> checkSpecDrift(
        List<CompilationUnit> baseUnits,
        List<CompilationUnit> patchedUnits,
        Set<String> frozenSpecs
    ) {
        Map<String, String> baseDigests = collectFrozenDigests(baseUnits, frozenSpecs);
        Map<String, String> patchedDigests = collectFrozenDigests(patchedUnits, frozenSpecs);

        List<Diagnostic> diags = new ArrayList<>();
        for (var entry : baseDigests.entrySet()) {
            String key = entry.getKey();
            String baseHash = entry.getValue();
            String patchedHash = patchedDigests.get(key);
            if (patchedHash == null) {
                diags.add(Diagnostic.error(
                    "NGJ-GATE-SPEC-DRIFT",
                    "Frozen specification or public contract '" + key + "' was removed or renamed",
                    null
                ));
            } else if (!baseHash.equals(patchedHash)) {
                diags.add(Diagnostic.error(
                    "NGJ-GATE-SPEC-DRIFT",
                    "Frozen specification or public contract '" + key + "' drifted (AST digest changed)",
                    null
                ));
            }
        }
        return diags;
    }

    private Map<String, String> collectFrozenDigests(List<CompilationUnit> units, Set<String> frozenSpecs) {
        Map<String, String> digests = new LinkedHashMap<>();
        for (var unit : units) {
            String mod = unit.modulePath();
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case SpecFnDecl sf -> {
                        if (sf.isPublic() || frozenSpecs.contains(sf.name())) {
                            digests.put(mod + "." + sf.name(), AstDigest.sha256(sf));
                        }
                    }
                    case ProtocolDecl pr -> {
                        if (pr.isPublic() || frozenSpecs.contains(pr.name())) {
                            digests.put(mod + "." + pr.name(), AstDigest.sha256(pr));
                        }
                    }
                    case LemmaDecl lm -> {
                        if (lm.isPublic() || frozenSpecs.contains(lm.name())) {
                            digests.put(mod + "." + lm.name(), AstDigest.sha256(lm));
                        }
                    }
                    case TypeAliasDecl ta -> {
                        if (ta.isPublic() || frozenSpecs.contains(ta.name())) {
                            digests.put(mod + "." + ta.name(), AstDigest.sha256(ta));
                        }
                    }
                    case RecordDecl rec -> {
                        if (rec.isPublic() || frozenSpecs.contains(rec.name())) {
                            digests.put(mod + "." + rec.name(), AstDigest.sha256(rec));
                        }
                    }
                    case EnumDecl en -> {
                        if (en.isPublic() || frozenSpecs.contains(en.name())) {
                            digests.put(mod + "." + en.name(), AstDigest.sha256(en));
                        }
                    }
                    case DataDecl da -> {
                        if (da.isPublic() || frozenSpecs.contains(da.name())) {
                            digests.put(mod + "." + da.name(), AstDigest.sha256(da));
                        }
                    }
                    case FnDecl fn -> {
                        if (fn.isPublic() || frozenSpecs.contains(fn.name())) {
                            // Hash public signature + effects + captureBounds + contracts (excluding body)
                            FnDecl sigOnly = new FnDecl(
                                fn.name(), fn.isPublic(), fn.params(), fn.returnType(),
                                fn.effects(), fn.captureBounds(), fn.annotations(), fn.contracts(),
                                null, fn.span()
                            );
                            digests.put(mod + "." + fn.name() + "#contract", AstDigest.sha256(sigOnly));
                        }
                    }
                }
            }
        }
        return digests;
    }

    private List<Diagnostic> checkAxiomDrift(List<CompilationUnit> units, CheckOptions checkOptions) {
        List<Diagnostic> diags = new ArrayList<>();
        Set<String> allowlist = checkOptions != null ? checkOptions.trustedAllowlist() : Set.of();
        for (var unit : units) {
            for (var decl : unit.declarations()) {
                for (var ann : declAnnotations(decl)) {
                    if (AXIOM_ANNOTATIONS.contains(ann.name())) {
                        String declName = declName(decl);
                        if (!allowlist.contains(declName) || "restricted".equals(checkOptions != null ? checkOptions.profile() : "")) {
                            diags.add(Diagnostic.error(
                                "NGJ-GATE-AXIOM-DRIFT",
                                "Unallowlisted axiom/interop annotation '@" + ann.name() + "' on '" + declName + "'",
                                ann.span() != null ? ann.span() : decl.span()
                            ));
                        }
                    }
                }
            }
        }
        return diags;
    }

    private List<Diagnostic> checkForbiddenConstructs(List<CompilationUnit> units) {
        List<Diagnostic> diags = new ArrayList<>();
        for (var unit : units) {
            for (var decl : unit.declarations()) {
                for (var ann : declAnnotations(decl)) {
                    if (FORBIDDEN_ANNOTATIONS.contains(ann.name())) {
                        diags.add(Diagnostic.error(
                            "NGJ-GATE-FORBIDDEN",
                            "Forbidden annotation '@" + ann.name() + "' under strict-gates",
                            ann.span() != null ? ann.span() : decl.span()
                        ));
                    }
                }
                if (decl instanceof FnDecl fn && fn.body() != null) {
                    scanForbiddenExpr(fn.body(), diags);
                } else if (decl instanceof LemmaDecl lm && lm.body() != null) {
                    scanForbiddenExpr(lm.body(), diags);
                }
            }
        }
        return diags;
    }

    private void scanForbiddenExpr(Expr expr, List<Diagnostic> diags) {
        if (expr == null) return;
        switch (expr) {
            case BlockExpr b -> {
                for (var s : b.statements()) {
                    switch (s) {
                        case AssumeStmt a -> diags.add(Diagnostic.error(
                            "NGJ-GATE-FORBIDDEN",
                            "Forbidden 'assume(...)' construct under strict-gates",
                            a.span()
                        ));
                        case ProofStmt p -> scanForbiddenExpr(p.body(), diags);
                        case LetStmt l -> scanForbiddenExpr(l.initializer(), diags);
                        case ValStmt v -> scanForbiddenExpr(v.initializer(), diags);
                        case VarStmt v -> scanForbiddenExpr(v.initializer(), diags);
                        case AssignStmt a -> scanForbiddenExpr(a.value(), diags);
                        case ExprStmt e -> scanForbiddenExpr(e.expr(), diags);
                        case ReturnStmt r -> scanForbiddenExpr(r.value(), diags);
                        case TupleDestructureStmt t -> scanForbiddenExpr(t.initializer(), diags);
                        case InvariantStmt inv -> scanForbiddenExpr(inv.condition(), diags);
                    }
                }
                scanForbiddenExpr(b.tailExpr(), diags);
            }
            case IfExpr i -> {
                scanForbiddenExpr(i.condition(), diags);
                scanForbiddenExpr(i.thenBranch(), diags);
                scanForbiddenExpr(i.elseBranch(), diags);
            }
            case MatchExpr m -> {
                scanForbiddenExpr(m.scrutinee(), diags);
                for (var arm : m.arms()) scanForbiddenExpr(arm.body(), diags);
            }
            case WhileExpr w -> {
                scanForbiddenExpr(w.condition(), diags);
                scanForbiddenExpr(w.body(), diags);
            }
            case ForExpr f -> {
                scanForbiddenExpr(f.iterable(), diags);
                scanForbiddenExpr(f.body(), diags);
            }
            case CallExpr c -> {
                String calleeStr = c.callee() instanceof IdentExpr id ? id.name() : c.callee().toString();
                if (calleeStr.equals("Unsafe") || calleeStr.endsWith(".Unsafe")) {
                    diags.add(Diagnostic.error(
                        "NGJ-GATE-FORBIDDEN",
                        "Forbidden Unsafe construct under strict-gates",
                        c.span()
                    ));
                }
                scanForbiddenExpr(c.callee(), diags);
                for (var a : c.args()) scanForbiddenExpr(a, diags);
            }
            case ScopeExpr s -> scanForbiddenExpr(s.body(), diags);
            case ForkExpr f -> scanForbiddenExpr(f.body(), diags);
            case LambdaExpr l -> scanForbiddenExpr(l.body(), diags);
            case ParenExpr p -> scanForbiddenExpr(p.inner(), diags);
            case PropagateExpr p -> scanForbiddenExpr(p.inner(), diags);
            case ConsumeExpr c -> scanForbiddenExpr(c.target(), diags);
            case BinaryExpr b -> { scanForbiddenExpr(b.left(), diags); scanForbiddenExpr(b.right(), diags); }
            case UnaryExpr u -> scanForbiddenExpr(u.operand(), diags);
            default -> {}
        }
    }

    private List<Annotation> declAnnotations(TopLevelDecl decl) {
        return switch (decl) {
            case FnDecl f -> f.annotations();
            case RecordDecl r -> r.annotations();
            case EnumDecl e -> e.annotations();
            case TypeAliasDecl t -> t.annotations();
            case ProtocolDecl p -> p.annotations();
            case SpecFnDecl s -> s.annotations();
            case LemmaDecl l -> l.annotations();
            case DataDecl _ -> List.of();
        };
    }

    private String declName(TopLevelDecl decl) {
        return switch (decl) {
            case FnDecl f -> f.name();
            case RecordDecl r -> r.name();
            case DataDecl d -> d.name();
            case EnumDecl e -> e.name();
            case TypeAliasDecl t -> t.name();
            case ProtocolDecl p -> p.name();
            case SpecFnDecl s -> s.name();
            case LemmaDecl l -> l.name();
        };
    }

    private SourceSpan firstErrorSpan(List<Diagnostic> diags) {
        for (var d : diags) {
            if (d.primarySpan() != null) return d.primarySpan();
        }
        return null;
    }

    private SourceSpan firstHoleSpan(List<CompilationUnit> units) {
        for (var u : units) {
            for (var d : u.declarations()) {
                if (d instanceof FnDecl fn) {
                    return fn.span();
                }
            }
        }
        return null;
    }

    private String rewriteDiffHeaders(String diff, Map<String, String> pathAliasToKey) {
        String[] lines = diff.replace("\r\n", "\n").split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("--- ") || line.startsWith("+++ ")) {
                String prefix = line.substring(0, 4);
                String rest = line.substring(4);
                int tab = rest.indexOf('\t');
                String rawPath = (tab >= 0 ? rest.substring(0, tab) : rest).trim();
                String suffix = tab >= 0 ? rest.substring(tab) : "";
                if (!"/dev/null".equals(rawPath)) {
                    String mapped = pathAliasToKey.get(rawPath);
                    if (mapped == null) {
                        String stripped = rawPath.startsWith("a/") || rawPath.startsWith("b/")
                            ? rawPath.substring(2) : rawPath;
                        mapped = pathAliasToKey.get(stripped);
                        if (mapped == null) {
                            try {
                                String fileName = Path.of(stripped).getFileName().toString();
                                mapped = pathAliasToKey.get(fileName);
                            } catch (Exception ignored) {}
                        }
                    }
                    if (mapped != null) {
                        line = prefix + mapped + suffix;
                    }
                }
            }
            sb.append(line);
            if (i + 1 < lines.length) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String stripTrailingEmptyHunkLines(String diff) {
        String[] lines = diff.replace("\r\n", "\n").split("\n", -1);
        List<String> out = new ArrayList<>();
        int lastMinusIdx = -1;
        int lastPlusIdx = -1;
        int hunkHeaderIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("@@ ")) {
                hunkHeaderIdx = out.size();
            } else if (line.equals("-") && (i + 1 < lines.length && lines[i + 1].startsWith("+"))) {
                lastMinusIdx = out.size();
            } else if (line.equals("+") && (i + 1 >= lines.length || lines[i + 1].isEmpty())) {
                lastPlusIdx = out.size();
            }
            out.add(line);
        }
        int removedOld = 0;
        int removedNew = 0;
        if (lastPlusIdx >= 0) {
            out.remove(lastPlusIdx);
            removedNew = 1;
            if (lastMinusIdx > lastPlusIdx) lastMinusIdx--;
        }
        if (lastMinusIdx >= 0) {
            out.remove(lastMinusIdx);
            removedOld = 1;
        }
        if (hunkHeaderIdx >= 0 && (removedOld > 0 || removedNew > 0)) {
            String hdr = out.get(hunkHeaderIdx);
            var m = java.util.regex.Pattern.compile("^@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@(.*)$").matcher(hdr);
            if (m.matches()) {
                int oldC = Math.max(0, Integer.parseInt(m.group(2)) - removedOld);
                int newC = Math.max(0, Integer.parseInt(m.group(4)) - removedNew);
                out.set(hunkHeaderIdx, "@@ -" + m.group(1) + "," + oldC + " +" + m.group(3) + "," + newC + " @@" + m.group(5));
            }
        }
        return String.join("\n", out);
    }
}
