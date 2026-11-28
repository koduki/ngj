package ngj.gate;

import ngj.ObligationsResult.Obligation;
import ngj.ast.*;
import ngj.diagnostic.Diagnostic;
import ngj.diagnostic.Severity;
import ngj.diagnostic.SourceSpan;

import java.util.*;

/**
 * Extracts structured verification and type-system obligations across compilation units:
 * typed_hole, proof_hole, refinement, capability_mode, capture_lifetime, effect_bound, protocol_state.
 */
public final class ObligationCollector {

    public List<Obligation> collect(List<CompilationUnit> units, List<Diagnostic> diagnostics) {
        List<Obligation> obligations = new ArrayList<>();
        Map<String, TypeAliasDecl> aliases = new LinkedHashMap<>();
        Set<String> protocols = new LinkedHashSet<>();

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                if (decl instanceof TypeAliasDecl ta) {
                    aliases.put(ta.name(), ta);
                } else if (decl instanceof ProtocolDecl proto) {
                    protocols.add(proto.name());
                }
            }
        }

        for (var unit : units) {
            String mod = unit.modulePath();
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case FnDecl fn -> collectFnObligations(mod, fn, aliases, protocols, diagnostics, obligations);
                    case RecordDecl rec -> collectRecordObligations(mod, rec, aliases, diagnostics, obligations);
                    case ProtocolDecl proto -> {
                        String symId = mod + "." + proto.name();
                        boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, proto.span(), symId, "NGJ-PROTO-");
                        obligations.add(new Obligation(
                            "protocol_state:" + symId,
                            "protocol_state",
                            symId,
                            failed ? "failed" : "resolved",
                            "Protocol state machine '" + proto.name() + "' reachability and liveness",
                            proto.span()
                        ));
                    }
                    case LemmaDecl lemma -> {
                        String symId = mod + "." + lemma.name();
                        List<HoleExpr> holes = collectHolesInExpr(lemma.body());
                        for (var h : holes) {
                            String kind = h.isProofHole() ? "proof_hole" : "typed_hole";
                            obligations.add(new Obligation(
                                kind + ":" + symId + ":" + h.name(),
                                kind,
                                symId,
                                "unresolved",
                                "Unresolved " + kind + " ?" + h.name(),
                                h.span()
                            ));
                        }
                        if (!lemma.contracts().isEmpty()) {
                            boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, lemma.span(), symId, "NGJ-VERIFY-", "NGJ-PROOF-");
                            String status = failed ? "failed" : (holes.isEmpty() ? "resolved" : "unresolved");
                            obligations.add(new Obligation(
                                "refinement:" + symId + ":lemma",
                                "refinement",
                                symId,
                                status,
                                "Lemma '" + lemma.name() + "' contract obligation",
                                lemma.span()
                            ));
                        }
                    }
                    default -> {}
                }
            }
        }

        return Collections.unmodifiableList(obligations);
    }

    public int countUnresolved(List<Obligation> obligations) {
        int count = 0;
        for (var ob : obligations) {
            if ("unresolved".equals(ob.status()) || "failed".equals(ob.status())) {
                count++;
            }
        }
        return count;
    }

    private void collectFnObligations(
        String mod,
        FnDecl fn,
        Map<String, TypeAliasDecl> aliases,
        Set<String> protocols,
        List<Diagnostic> diagnostics,
        List<Obligation> obligations
    ) {
        String symId = mod + "." + fn.name();
        List<HoleExpr> holes = collectHolesInExpr(fn.body());
        boolean hasProofHole = holes.stream().anyMatch(HoleExpr::isProofHole);
        boolean hasCodeHole = holes.stream().anyMatch(h -> !h.isProofHole());

        for (var h : holes) {
            String kind = h.isProofHole() ? "proof_hole" : "typed_hole";
            obligations.add(new Obligation(
                kind + ":" + symId + ":" + h.name(),
                kind,
                symId,
                "unresolved",
                "Unresolved " + kind + " ?" + h.name() + " in " + fn.name(),
                h.span()
            ));
        }

        for (var param : fn.params()) {
            String paramId = symId + "." + param.name();
            TypeExpr resolvedType = resolveAlias(param.type(), aliases);

            if (isRefinedOrRange(resolvedType)) {
                boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, fn.span(), symId, "NGJ-REF-", "NGJ-VERIFY-");
                obligations.add(new Obligation(
                    "refinement:" + paramId,
                    "refinement",
                    symId,
                    failed ? "failed" : "resolved",
                    "Refinement constraint on parameter '" + param.name() + ": " + param.type() + "'",
                    param.span()
                ));
            }

            if (param.isConsume() || param.type() instanceof CapabilityTypeExpr) {
                boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, fn.span(), symId, "NGJ-CAP-", "NGJ-BUDGET-");
                String mode = param.isConsume() ? "consume"
                    : ((CapabilityTypeExpr) param.type()).mode().name().toLowerCase(Locale.ROOT);
                obligations.add(new Obligation(
                    "capability_mode:" + paramId,
                    "capability_mode",
                    symId,
                    failed ? "failed" : "resolved",
                    "Capability mode '" + mode + "' on parameter '" + param.name() + "'",
                    param.span()
                ));
            }

            if (hasCaptureSet(param.type())) {
                boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, fn.span(), symId, "NGJ-CAP-001", "NGJ-SEP-");
                obligations.add(new Obligation(
                    "capture_lifetime:" + paramId,
                    "capture_lifetime",
                    symId,
                    failed ? "failed" : "resolved",
                    "Capture lifetime bound on '" + param.name() + ": " + param.type() + "'",
                    param.span()
                ));
            }

            if (isProtocolParam(param.type(), protocols)) {
                boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, fn.span(), symId, "NGJ-PROTO-");
                obligations.add(new Obligation(
                    "protocol_state:" + paramId,
                    "protocol_state",
                    symId,
                    failed ? "failed" : "resolved",
                    "Protocol state transition obligation on '" + param.name() + "'",
                    param.span()
                ));
            }
        }

        if (fn.returnType() != null) {
            TypeExpr resolvedRet = resolveAlias(fn.returnType(), aliases);
            if (isRefinedOrRange(resolvedRet)) {
                boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, fn.span(), symId, "NGJ-REF-");
                obligations.add(new Obligation(
                    "refinement:" + symId + ":return",
                    "refinement",
                    symId,
                    failed ? "failed" : (hasCodeHole ? "unresolved" : "resolved"),
                    "Return refinement constraint: " + fn.returnType(),
                    fn.span()
                ));
            }
            if (hasCaptureSet(fn.returnType())) {
                boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, fn.span(), symId, "NGJ-CAP-001", "NGJ-SEP-");
                obligations.add(new Obligation(
                    "capture_lifetime:" + symId + ":return",
                    "capture_lifetime",
                    symId,
                    failed ? "failed" : "resolved",
                    "Returned capture lifetime bound: " + fn.returnType(),
                    fn.span()
                ));
            }
        }

        if (!fn.contracts().isEmpty()) {
            boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, fn.span(), symId, "NGJ-VERIFY-", "NGJ-REF-");
            String status = failed ? "failed" : ((hasProofHole || hasCodeHole) ? "unresolved" : "resolved");
            obligations.add(new Obligation(
                "refinement:" + symId + ":contract",
                "refinement",
                symId,
                status,
                "Function contract (requires/ensures) on '" + fn.name() + "'",
                fn.span()
            ));
        }

        // Effect bound obligation
        boolean effFailed = hasDiagnosticInSpanOrSymbol(diagnostics, fn.span(), symId, "NGJ-EFFECT-");
        obligations.add(new Obligation(
            "effect_bound:" + symId,
            "effect_bound",
            symId,
            effFailed ? "failed" : "resolved",
            "Effect bound effects(" + String.join(", ", fn.effects()) + ")",
            fn.span()
        ));

        // Local val/var refinement annotations in body
        collectLocalRefinements(symId, fn.body(), aliases, diagnostics, obligations);
    }

    private void collectRecordObligations(
        String mod,
        RecordDecl rec,
        Map<String, TypeAliasDecl> aliases,
        List<Diagnostic> diagnostics,
        List<Obligation> obligations
    ) {
        String symId = mod + "." + rec.name();
        for (var param : rec.params()) {
            if (isRefinedOrRange(resolveAlias(param.type(), aliases))) {
                boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, rec.span(), symId, "NGJ-REF-");
                obligations.add(new Obligation(
                    "refinement:" + symId + "." + param.name(),
                    "refinement",
                    symId,
                    failed ? "failed" : "resolved",
                    "Record field refinement '" + param.name() + ": " + param.type() + "'",
                    param.span()
                ));
            }
        }
    }

    private void collectLocalRefinements(
        String symId,
        Expr expr,
        Map<String, TypeAliasDecl> aliases,
        List<Diagnostic> diagnostics,
        List<Obligation> obligations
    ) {
        if (expr == null) return;
        if (expr instanceof BlockExpr block) {
            for (var stmt : block.statements()) {
                if (stmt instanceof ValStmt v && v.explicitType() != null) {
                    if (isRefinedOrRange(resolveAlias(v.explicitType(), aliases))) {
                        boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, v.span(), symId, "NGJ-REF-");
                        obligations.add(new Obligation(
                            "refinement:" + symId + "." + v.name(),
                            "refinement",
                            symId,
                            failed ? "failed" : "resolved",
                            "Local binding refinement '" + v.name() + ": " + v.explicitType() + "'",
                            v.span()
                        ));
                    }
                    if (hasCaptureSet(v.explicitType())) {
                        boolean failed = hasDiagnosticInSpanOrSymbol(diagnostics, v.span(), symId, "NGJ-CAP-001", "NGJ-SEP-");
                        obligations.add(new Obligation(
                            "capture_lifetime:" + symId + "." + v.name(),
                            "capture_lifetime",
                            symId,
                            failed ? "failed" : "resolved",
                            "Local capture lifetime '" + v.name() + ": " + v.explicitType() + "'",
                            v.span()
                        ));
                    }
                }
            }
        }
    }

    private TypeExpr resolveAlias(TypeExpr type, Map<String, TypeAliasDecl> aliases) {
        Set<String> seen = new HashSet<>();
        TypeExpr cur = type;
        while (cur instanceof SimpleTypeExpr s && aliases.containsKey(s.name()) && seen.add(s.name())) {
            cur = aliases.get(s.name()).targetType();
        }
        return cur;
    }

    private boolean isRefinedOrRange(TypeExpr type) {
        return type instanceof RangeTypeExpr || type instanceof RefinedTypeExpr;
    }

    private boolean hasCaptureSet(TypeExpr type) {
        if (type == null) return false;
        return switch (type) {
            case CaptureSetTypeExpr _ -> true;
            case CapabilityTypeExpr cap -> hasCaptureSet(cap.inner());
            case GenericTypeExpr gen -> {
                if (gen.name().equals("Arena") || gen.name().equals("Bytes")) yield true;
                for (var arg : gen.typeArgs()) {
                    if (hasCaptureSet(arg)) yield true;
                }
                yield false;
            }
            case SimpleTypeExpr s -> s.name().equals("Arena");
            default -> false;
        };
    }

    private boolean isProtocolParam(TypeExpr type, Set<String> protocols) {
        if (type instanceof SimpleTypeExpr s) {
            return protocols.contains(s.name());
        }
        if (type instanceof GenericTypeExpr g) {
            return protocols.contains(g.name());
        }
        if (type instanceof CapabilityTypeExpr c) {
            return isProtocolParam(c.inner(), protocols);
        }
        return false;
    }

    private boolean hasDiagnosticInSpanOrSymbol(List<Diagnostic> diagnostics, SourceSpan declSpan, String symId, String... prefixes) {
        for (var d : diagnostics) {
            if (d.severity() != Severity.ERROR) continue;
            boolean idMatch = false;
            for (String p : prefixes) {
                if (d.id().startsWith(p)) {
                    idMatch = true;
                    break;
                }
            }
            if (!idMatch) continue;
            if (symId.equals(d.symbolId())) return true;
            if (declSpan != null && d.primarySpan() != null
                && Objects.equals(declSpan.file(), d.primarySpan().file())
                && d.primarySpan().startByte() >= declSpan.startByte()
                && d.primarySpan().endByte() <= declSpan.endByte()) {
                return true;
            }
        }
        return false;
    }

    private List<HoleExpr> collectHolesInExpr(Expr expr) {
        List<HoleExpr> result = new ArrayList<>();
        collectHolesRecursive(expr, result);
        return result;
    }

    private void collectHolesRecursive(Expr expr, List<HoleExpr> out) {
        if (expr == null) return;
        switch (expr) {
            case HoleExpr h -> out.add(h);
            case BinaryExpr b -> { collectHolesRecursive(b.left(), out); collectHolesRecursive(b.right(), out); }
            case UnaryExpr u -> collectHolesRecursive(u.operand(), out);
            case CallExpr c -> {
                collectHolesRecursive(c.callee(), out);
                for (var a : c.args()) collectHolesRecursive(a, out);
            }
            case FieldAccessExpr f -> collectHolesRecursive(f.object(), out);
            case ConstructorExpr c -> {
                for (var f : c.fields()) collectHolesRecursive(f.value(), out);
            }
            case IfExpr i -> {
                collectHolesRecursive(i.condition(), out);
                collectHolesRecursive(i.thenBranch(), out);
                collectHolesRecursive(i.elseBranch(), out);
            }
            case MatchExpr m -> {
                collectHolesRecursive(m.scrutinee(), out);
                for (var arm : m.arms()) collectHolesRecursive(arm.body(), out);
            }
            case BlockExpr b -> {
                for (var s : b.statements()) {
                    switch (s) {
                        case LetStmt l -> collectHolesRecursive(l.initializer(), out);
                        case ValStmt v -> collectHolesRecursive(v.initializer(), out);
                        case VarStmt v -> collectHolesRecursive(v.initializer(), out);
                        case AssignStmt a -> collectHolesRecursive(a.value(), out);
                        case ExprStmt e -> collectHolesRecursive(e.expr(), out);
                        case ReturnStmt r -> collectHolesRecursive(r.value(), out);
                        case TupleDestructureStmt t -> collectHolesRecursive(t.initializer(), out);
                        case InvariantStmt inv -> collectHolesRecursive(inv.condition(), out);
                        case ProofStmt p -> collectHolesRecursive(p.body(), out);
                        case AssumeStmt a -> collectHolesRecursive(a.condition(), out);
                    }
                }
                collectHolesRecursive(b.tailExpr(), out);
            }
            case PropagateExpr p -> collectHolesRecursive(p.inner(), out);
            case ParenExpr p -> collectHolesRecursive(p.inner(), out);
            case ForExpr f -> { collectHolesRecursive(f.iterable(), out); collectHolesRecursive(f.body(), out); }
            case WhileExpr w -> {
                collectHolesRecursive(w.condition(), out);
                collectHolesRecursive(w.invariant(), out);
                collectHolesRecursive(w.body(), out);
            }
            case LambdaExpr l -> collectHolesRecursive(l.body(), out);
            case ScopeExpr s -> collectHolesRecursive(s.body(), out);
            case ForkExpr f -> collectHolesRecursive(f.body(), out);
            case ConsumeExpr c -> collectHolesRecursive(c.target(), out);
            case TupleExpr t -> {
                for (var el : t.elements()) collectHolesRecursive(el, out);
            }
            case LiteralExpr _, IdentExpr _ -> {}
        }
    }
}
