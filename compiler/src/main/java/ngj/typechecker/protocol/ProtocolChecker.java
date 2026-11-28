package ngj.typechecker.protocol;

import ngj.ast.*;
import ngj.diagnostic.*;

import java.util.*;

/**
 * Phase 3 Protocol Checker (PROTO-01):
 * - Structural & liveness validation via ProtocolAutomaton (NGJ-PROTO-001)
 * - Refinement trace-inclusion checking (NGJ-PROTO-003)
 * - Flow-sensitive instance-ID & state-token tracking (NGJ-PROTO-004)
 * - @must_resolve final-state completion on all exits including '?' early returns (NGJ-PROTO-002)
 */
public final class ProtocolChecker {
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, ProtocolDecl> protocolDecls = new LinkedHashMap<>();
    private final Map<String, ProtocolAutomaton> automata = new LinkedHashMap<>();
    private final Set<String> mustResolveProtocols = new LinkedHashSet<>();
    private final Map<String, TypeAliasDecl> typeAliases = new HashMap<>();

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public Map<String, ProtocolAutomaton> getAutomata() {
        return Collections.unmodifiableMap(automata);
    }

    public static ProtocolAutomaton toAutomaton(ProtocolDecl decl) {
        List<ProtocolAutomaton.Transition> transitions = decl.transitions().stream()
            .map(t -> new ProtocolAutomaton.Transition(
                t.fromState(), t.name(), t.toState(), Set.copyOf(t.effects())))
            .toList();
        return new ProtocolAutomaton(
            decl.initialState(),
            new LinkedHashSet<>(decl.states()),
            new LinkedHashSet<>(decl.finalStates()),
            transitions
        );
    }

    public void checkAll(List<CompilationUnit> units) {
        protocolDecls.clear();
        automata.clear();
        mustResolveProtocols.clear();
        typeAliases.clear();

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                if (decl instanceof ProtocolDecl proto) {
                    protocolDecls.put(proto.name(), proto);
                    ProtocolAutomaton aut = toAutomaton(proto);
                    automata.put(proto.name(), aut);
                    for (var ann : proto.annotations()) {
                        if (ann.name().equals("must_resolve")) {
                            mustResolveProtocols.add(proto.name());
                        }
                    }
                    for (String err : aut.validate()) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-PROTO-001",
                            "Protocol '" + proto.name() + "' invalid: " + err,
                            proto.span()
                        ));
                    }
                } else if (decl instanceof TypeAliasDecl ta) {
                    typeAliases.put(ta.name(), ta);
                }
            }
        }

        // Check refines trace inclusion
        for (var proto : protocolDecls.values()) {
            if (proto.parentProtocol() != null) {
                ProtocolAutomaton sub = automata.get(proto.name());
                ProtocolAutomaton sup = automata.get(proto.parentProtocol());
                if (sup == null) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-PROTO-001",
                        "Protocol '" + proto.name() + "' refines unknown protocol '" + proto.parentProtocol() + "'",
                        proto.span()
                    ));
                } else {
                    ProtocolAutomaton projectedSub = projectSubOverSupAlphabet(sub, sup);
                    var ce = ProtocolAutomaton.checkRefines(projectedSub, sup);
                    if (ce.isPresent()) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-PROTO-003",
                            "Protocol '" + proto.name() + "' does not refine '" + proto.parentProtocol()
                                + "': counterexample trace [" + String.join(" -> ", ce.get()) + "]",
                            proto.span()
                        ));
                    }
                }
            }
        }

        // Flow-sensitive instance-ID & token-state tracking in functions
        for (var unit : units) {
            for (var decl : unit.declarations()) {
                if (decl instanceof FnDecl fn) {
                    checkFn(fn);
                }
            }
        }
    }

    private void checkFn(FnDecl fn) {
        ProtoEnv env = new ProtoEnv();
        for (var param : fn.params()) {
            String protoName = protocolNameOf(param.type());
            if (protoName != null && automata.containsKey(protoName)) {
                String state = initialStateOf(param.type(), automata.get(protoName));
                env.bind(param.name(), protoName, state, nextInstanceId(env));
            }
        }
        if (fn.body() != null) {
            checkBlock(fn.body(), env);
            if (!blockAlwaysReturns(fn.body())) {
                checkMustResolveOnExit(env, null, fn.body().span(), "function exit");
            }
        }
    }

    private void checkBlock(BlockExpr block, ProtoEnv env) {
        checkBlockWithResult(block, env);
    }

    private TokenResult checkBlockWithResult(BlockExpr block, ProtoEnv env) {
        if (block == null) return null;
        for (var stmt : block.statements()) {
            checkStmt(stmt, env);
        }
        if (block.tailExpr() != null) {
            return checkExpr(block.tailExpr(), env);
        }
        return null;
    }

    private static ProtocolAutomaton projectSubOverSupAlphabet(ProtocolAutomaton sub, ProtocolAutomaton sup) {
        Set<String> supActions = new HashSet<>();
        for (var t : sup.transitions()) {
            supActions.add(t.action());
        }
        boolean hasExtra = false;
        for (var t : sub.transitions()) {
            if (!supActions.contains(t.action())) {
                hasExtra = true;
                break;
            }
        }
        if (!hasExtra) {
            return sub;
        }
        // Compute tau-closure for sub-only actions and project observable transitions
        Map<String, Set<String>> tauClosure = new HashMap<>();
        for (String s : sub.states()) {
            Set<String> closure = new LinkedHashSet<>();
            ArrayDeque<String> q = new ArrayDeque<>();
            closure.add(s);
            q.add(s);
            while (!q.isEmpty()) {
                String cur = q.removeFirst();
                for (var t : sub.transitions()) {
                    if (t.from().equals(cur) && !supActions.contains(t.action()) && closure.add(t.to())) {
                        q.addLast(t.to());
                    }
                }
            }
            tauClosure.put(s, closure);
        }
        Set<String> projectedFinals = new LinkedHashSet<>();
        for (String s : sub.states()) {
            for (String reach : tauClosure.get(s)) {
                if (sub.finalStates().contains(reach)) {
                    projectedFinals.add(s);
                    break;
                }
            }
        }
        List<ProtocolAutomaton.Transition> projectedTransitions = new ArrayList<>();
        for (String s : sub.states()) {
            for (String mid : tauClosure.get(s)) {
                for (var t : sub.transitions()) {
                    if (t.from().equals(mid) && supActions.contains(t.action())) {
                        projectedTransitions.add(new ProtocolAutomaton.Transition(s, t.action(), t.to(), t.effects()));
                    }
                }
            }
        }
        return new ProtocolAutomaton(sub.initialState(), sub.states(), projectedFinals, projectedTransitions);
    }

    private void checkStmt(Statement stmt, ProtoEnv env) {
        switch (stmt) {
            case ValStmt val -> {
                TokenResult res = checkExpr(val.initializer(), env);
                if (res != null) {
                    checkOverwriteAbandon(env, val.name(), res.instanceId, val.span());
                    env.bind(val.name(), res.protoName, res.state, res.instanceId);
                } else {
                    String protoName = protocolNameOf(val.explicitType());
                    if (protoName != null && automata.containsKey(protoName)) {
                        int id = nextInstanceId(env);
                        checkOverwriteAbandon(env, val.name(), id, val.span());
                        env.bind(val.name(), protoName, initialStateOf(val.explicitType(), automata.get(protoName)), id);
                    }
                }
            }
            case LetStmt let -> {
                TokenResult res = checkExpr(let.initializer(), env);
                if (res != null) {
                    checkOverwriteAbandon(env, let.name(), res.instanceId, let.span());
                    env.bind(let.name(), res.protoName, res.state, res.instanceId);
                } else {
                    String protoName = protocolNameOf(let.explicitType());
                    if (protoName != null && automata.containsKey(protoName)) {
                        int id = nextInstanceId(env);
                        checkOverwriteAbandon(env, let.name(), id, let.span());
                        env.bind(let.name(), protoName, initialStateOf(let.explicitType(), automata.get(protoName)), id);
                    }
                }
            }
            case VarStmt varStmt -> {
                TokenResult res = checkExpr(varStmt.initializer(), env);
                if (res != null) {
                    checkOverwriteAbandon(env, varStmt.name(), res.instanceId, varStmt.span());
                    env.bind(varStmt.name(), res.protoName, res.state, res.instanceId);
                } else {
                    String protoName = protocolNameOf(varStmt.explicitType());
                    if (protoName != null && automata.containsKey(protoName)) {
                        int id = nextInstanceId(env);
                        checkOverwriteAbandon(env, varStmt.name(), id, varStmt.span());
                        env.bind(varStmt.name(), protoName, initialStateOf(varStmt.explicitType(), automata.get(protoName)), id);
                    }
                }
            }
            case AssignStmt assign -> {
                TokenResult res = checkExpr(assign.value(), env);
                if (res != null) {
                    checkOverwriteAbandon(env, assign.name(), res.instanceId, assign.span());
                    env.bind(assign.name(), res.protoName, res.state, res.instanceId);
                }
            }
            case TupleDestructureStmt td -> {
                Expr init = td.initializer();
                while (init instanceof ParenExpr p) init = p.inner();
                if (init instanceof TupleExpr te) {
                    for (int i = 0; i < te.elements().size(); i++) {
                        TokenResult res = checkExpr(te.elements().get(i), env);
                        if (i < td.names().size()) {
                            String name = td.names().get(i);
                            if (res != null) {
                                checkOverwriteAbandon(env, name, res.instanceId, td.span());
                                env.bind(name, res.protoName, res.state, res.instanceId);
                            } else if (i < td.explicitTypes().size()) {
                                String protoName = protocolNameOf(td.explicitTypes().get(i));
                                if (protoName != null && automata.containsKey(protoName)) {
                                    int id = nextInstanceId(env);
                                    checkOverwriteAbandon(env, name, id, td.span());
                                    env.bind(name, protoName, initialStateOf(td.explicitTypes().get(i), automata.get(protoName)), id);
                                }
                            }
                        } else if (res != null) {
                            checkDiscardedToken(res, env, td.span());
                        }
                    }
                } else {
                    checkExpr(td.initializer(), env);
                    for (int i = 0; i < td.names().size(); i++) {
                        if (i < td.explicitTypes().size()) {
                            String protoName = protocolNameOf(td.explicitTypes().get(i));
                            if (protoName != null && automata.containsKey(protoName)) {
                                int id = nextInstanceId(env);
                                checkOverwriteAbandon(env, td.names().get(i), id, td.span());
                                env.bind(td.names().get(i), protoName, initialStateOf(td.explicitTypes().get(i), automata.get(protoName)), id);
                            }
                        }
                    }
                }
            }
            case ReturnStmt ret -> {
                String returnedVar = null;
                if (ret.value() != null) {
                    checkExpr(ret.value(), env);
                    returnedVar = unwrapIdentName(ret.value());
                }
                checkMustResolveOnExit(env, returnedVar, ret.span(), "return");
            }
            case ExprStmt es -> {
                TokenResult discarded = checkExpr(es.expr(), env);
                if (discarded != null) {
                    checkDiscardedToken(discarded, env, es.span());
                }
            }
            case InvariantStmt inv -> checkExpr(inv.condition(), env);
            case ProofStmt _ -> {}
            case AssumeStmt _ -> {}
        }
    }

    private void checkOverwriteAbandon(ProtoEnv env, String varName, int newInstanceId, SourceSpan span) {
        TokenState old = env.tokens.get(varName);
        if (old == null || old.consumed || old.instanceId == newInstanceId) return;
        if (!mustResolveProtocols.contains(old.protoName)) return;
        ProtocolAutomaton aut = automata.get(old.protoName);
        if (aut != null && !aut.finalStates().contains(old.state)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-PROTO-002",
                "@must_resolve protocol '" + old.protoName + "' token '" + varName
                    + "' in non-final state '" + old.state + "' abandoned by rebinding",
                span
            ));
        }
    }

    private void checkDiscardedToken(TokenResult tok, ProtoEnv env, SourceSpan span) {
        if (tok == null || !mustResolveProtocols.contains(tok.protoName)) return;
        ProtocolAutomaton aut = automata.get(tok.protoName);
        if (aut == null || aut.finalStates().contains(tok.state)) return;
        for (TokenState st : env.tokens.values()) {
            if (!st.consumed && st.instanceId == tok.instanceId) {
                return;
            }
        }
        diagnostics.add(Diagnostic.error(
            "NGJ-PROTO-002",
            "@must_resolve protocol '" + tok.protoName + "' token (instance #" + tok.instanceId
                + ") in non-final state '" + tok.state + "' discarded without resolution",
            span
        ));
    }

    private String unwrapIdentName(Expr expr) {
        Expr cur = expr;
        while (cur instanceof ParenExpr p) cur = p.inner();
        return cur instanceof IdentExpr id ? id.name() : null;
    }

    private void checkScopedLocalTokensOnExit(
        ProtoEnv innerEnv,
        Set<String> outerKeys,
        String returnedVar,
        SourceSpan span,
        String exitKind
    ) {
        for (var entry : innerEnv.tokens.entrySet()) {
            String varName = entry.getKey();
            if (outerKeys.contains(varName) || varName.equals(returnedVar)) continue;
            TokenState st = entry.getValue();
            if (st.consumed) continue;
            if (!mustResolveProtocols.contains(st.protoName)) continue;
            ProtocolAutomaton aut = automata.get(st.protoName);
            if (aut != null && !aut.finalStates().contains(st.state)) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-PROTO-002",
                    "@must_resolve protocol '" + st.protoName + "' token '" + varName
                        + "' in non-final state '" + st.state + "' at " + exitKind,
                    span
                ));
            }
        }
    }

    private void copyOuterStatesFrom(ProtoEnv target, ProtoEnv inner, Set<String> outerKeys) {
        for (String key : outerKeys) {
            if (inner.tokens.containsKey(key)) {
                target.tokens.put(key, inner.tokens.get(key).copy());
            }
        }
        target.nextId = Math.max(target.nextId, inner.nextId);
    }

    private TokenResult checkExpr(Expr expr, ProtoEnv env) {
        if (expr == null) return null;
        return switch (expr) {
            case IdentExpr id -> {
                TokenState st = env.tokens.get(id.name());
                if (st != null && st.consumed) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-PROTO-004",
                        "Use of consumed protocol token '" + id.name() + "' (instance #" + st.instanceId + ")",
                        id.span()
                    ));
                    yield null;
                }
                yield st != null ? new TokenResult(st.protoName, st.state, st.instanceId) : null;
            }
            case ConsumeExpr c -> {
                if (c.target() instanceof IdentExpr id) {
                    TokenState st = env.tokens.get(id.name());
                    if (st != null) {
                        if (st.consumed) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-PROTO-004",
                                "Protocol token '" + id.name() + "' already consumed",
                                c.span()
                            ));
                            yield null;
                        }
                        st.consumed = true;
                        yield new TokenResult(st.protoName, st.state, st.instanceId);
                    }
                }
                yield checkExpr(c.target(), env);
            }
            case CallExpr call -> checkCall(call, env);
            case PropagateExpr prop -> {
                TokenResult r = checkExpr(prop.inner(), env);
                // '?' can early-return; all active @must_resolve tokens must already be resolved
                checkMustResolveOnExit(env, null, prop.span(), "'?' early return");
                yield r;
            }
            case IfExpr ifExpr -> {
                checkExpr(ifExpr.condition(), env);
                Set<String> outerKeys = new LinkedHashSet<>(env.tokens.keySet());
                ProtoEnv thenEnv = env.copy();
                TokenResult thenTok = checkBlockWithResult(ifExpr.thenBranch(), thenEnv);
                boolean thenRet = blockAlwaysReturns(ifExpr.thenBranch());
                if (!thenRet) {
                    checkScopedLocalTokensOnExit(
                        thenEnv, outerKeys, unwrapIdentName(ifExpr.thenBranch().tailExpr()),
                        ifExpr.thenBranch().span(), "branch exit");
                }
                ProtoEnv elseEnv = env.copy();
                elseEnv.nextId = Math.max(elseEnv.nextId, thenEnv.nextId);
                TokenResult elseTok = null;
                boolean elseRet = ifExpr.elseBranch() != null && exprAlwaysReturns(ifExpr.elseBranch());
                if (ifExpr.elseBranch() != null) {
                    if (ifExpr.elseBranch() instanceof BlockExpr eb) {
                        elseTok = checkBlockWithResult(eb, elseEnv);
                        if (!elseRet) {
                            checkScopedLocalTokensOnExit(
                                elseEnv, outerKeys, unwrapIdentName(eb.tailExpr()),
                                eb.span(), "branch exit");
                        }
                    } else {
                        elseTok = checkExpr(ifExpr.elseBranch(), elseEnv);
                    }
                }
                env.nextId = Math.max(env.nextId, elseEnv.nextId);
                if (thenRet && !elseRet) {
                    copyOuterStatesFrom(env, elseEnv, outerKeys);
                    yield elseTok;
                } else if (!thenRet && elseRet) {
                    copyOuterStatesFrom(env, thenEnv, outerKeys);
                    yield thenTok;
                } else if (!thenRet && !elseRet) {
                    env.mergeFrom(thenEnv, elseEnv, diagnostics, ifExpr.span());
                    if (thenTok != null && elseTok != null && thenTok.instanceId() != elseTok.instanceId()) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-PROTO-004",
                            "Cannot merge distinct protocol instances (#" + thenTok.instanceId()
                                + " vs #" + elseTok.instanceId() + ") of '" + thenTok.protoName() + "'",
                            ifExpr.span()
                        ));
                    }
                    yield thenTok != null ? thenTok : elseTok;
                }
                yield null;
            }
            case MatchExpr matchExpr -> {
                checkExpr(matchExpr.scrutinee(), env);
                Set<String> outerKeys = new LinkedHashSet<>(env.tokens.keySet());
                ProtoEnv merged = null;
                TokenResult firstTok = null;
                int nextId = env.nextId;
                for (var arm : matchExpr.arms()) {
                    ProtoEnv armEnv = env.copy();
                    armEnv.nextId = nextId;
                    TokenResult armTok;
                    boolean armRet = exprAlwaysReturns(arm.body());
                    if (arm.body() instanceof BlockExpr eb) {
                        armTok = checkBlockWithResult(eb, armEnv);
                        if (!armRet) {
                            checkScopedLocalTokensOnExit(
                                armEnv, outerKeys, unwrapIdentName(eb.tailExpr()),
                                eb.span(), "match arm exit");
                        }
                    } else {
                        armTok = checkExpr(arm.body(), armEnv);
                    }
                    nextId = Math.max(nextId, armEnv.nextId);
                    if (!armRet) {
                        if (merged == null) {
                            merged = env.copy();
                            copyOuterStatesFrom(merged, armEnv, outerKeys);
                            firstTok = armTok;
                        } else {
                            merged.mergeFrom(merged.copy(), armEnv, diagnostics, arm.span());
                            if (firstTok != null && armTok != null && firstTok.instanceId() != armTok.instanceId()) {
                                diagnostics.add(Diagnostic.error(
                                    "NGJ-PROTO-004",
                                    "Cannot merge distinct protocol instances (#" + firstTok.instanceId()
                                        + " vs #" + armTok.instanceId() + ") of '" + firstTok.protoName() + "'",
                                    arm.span()
                                ));
                            } else if (firstTok == null) {
                                firstTok = armTok;
                            }
                        }
                    }
                }
                env.nextId = nextId;
                if (merged != null) {
                    copyOuterStatesFrom(env, merged, outerKeys);
                }
                yield firstTok;
            }
            case WhileExpr whileExpr -> {
                checkExpr(whileExpr.condition(), env);
                if (whileExpr.invariant() != null) {
                    checkExpr(whileExpr.invariant(), env);
                }
                Set<String> outerKeys = new LinkedHashSet<>(env.tokens.keySet());
                ProtoEnv bodyEnv = env.copy();
                checkBlockWithResult(whileExpr.body(), bodyEnv);
                env.nextId = Math.max(env.nextId, bodyEnv.nextId);
                if (!blockAlwaysReturns(whileExpr.body())) {
                    checkScopedLocalTokensOnExit(bodyEnv, outerKeys, null, whileExpr.body().span(), "loop iteration exit");
                    env.mergeFrom(env.copy(), bodyEnv, diagnostics, whileExpr.span());
                }
                yield null;
            }
            case ForExpr forExpr -> {
                checkExpr(forExpr.iterable(), env);
                Set<String> outerKeys = new LinkedHashSet<>(env.tokens.keySet());
                ProtoEnv bodyEnv = env.copy();
                checkBlockWithResult(forExpr.body(), bodyEnv);
                env.nextId = Math.max(env.nextId, bodyEnv.nextId);
                if (!blockAlwaysReturns(forExpr.body())) {
                    checkScopedLocalTokensOnExit(bodyEnv, outerKeys, null, forExpr.body().span(), "loop iteration exit");
                    env.mergeFrom(env.copy(), bodyEnv, diagnostics, forExpr.span());
                }
                yield null;
            }
            case BlockExpr b -> {
                Set<String> outerKeys = new LinkedHashSet<>(env.tokens.keySet());
                ProtoEnv blockEnv = env.copy();
                TokenResult tok = checkBlockWithResult(b, blockEnv);
                if (!blockAlwaysReturns(b)) {
                    checkScopedLocalTokensOnExit(blockEnv, outerKeys, unwrapIdentName(b.tailExpr()), b.span(), "block exit");
                    copyOuterStatesFrom(env, blockEnv, outerKeys);
                } else {
                    env.nextId = Math.max(env.nextId, blockEnv.nextId);
                }
                yield tok;
            }
            case ScopeExpr s -> {
                Set<String> outerKeys = new LinkedHashSet<>(env.tokens.keySet());
                ProtoEnv scopeEnv = env.copy();
                TokenResult tok = checkBlockWithResult(s.body(), scopeEnv);
                if (!blockAlwaysReturns(s.body())) {
                    checkScopedLocalTokensOnExit(scopeEnv, outerKeys, unwrapIdentName(s.body().tailExpr()), s.span(), "scope exit");
                    copyOuterStatesFrom(env, scopeEnv, outerKeys);
                } else {
                    env.nextId = Math.max(env.nextId, scopeEnv.nextId);
                }
                yield tok;
            }
            case ForkExpr f -> {
                Set<String> outerKeys = new LinkedHashSet<>(env.tokens.keySet());
                ProtoEnv forkEnv = env.copy();
                checkBlockWithResult(f.body(), forkEnv);
                if (!blockAlwaysReturns(f.body())) {
                    checkScopedLocalTokensOnExit(forkEnv, outerKeys, unwrapIdentName(f.body().tailExpr()), f.span(), "fork exit");
                    copyOuterStatesFrom(env, forkEnv, outerKeys);
                } else {
                    env.nextId = Math.max(env.nextId, forkEnv.nextId);
                }
                yield null;
            }
            case LambdaExpr l -> {
                ProtoEnv lambdaEnv = new ProtoEnv();
                lambdaEnv.nextId = env.nextId;
                for (var param : l.params()) {
                    String protoName = protocolNameOf(param.type());
                    if (protoName != null && automata.containsKey(protoName)) {
                        String state = initialStateOf(param.type(), automata.get(protoName));
                        lambdaEnv.bind(param.name(), protoName, state, nextInstanceId(lambdaEnv));
                    }
                }
                if (l.body() != null) {
                    checkBlockWithResult(l.body(), lambdaEnv);
                    if (!blockAlwaysReturns(l.body())) {
                        checkMustResolveOnExit(lambdaEnv, unwrapIdentName(l.body().tailExpr()), l.body().span(), "lambda exit");
                    }
                }
                env.nextId = Math.max(env.nextId, lambdaEnv.nextId);
                yield null;
            }
            case ParenExpr p -> checkExpr(p.inner(), env);
            case BinaryExpr b -> {
                checkExpr(b.left(), env);
                checkExpr(b.right(), env);
                yield null;
            }
            case UnaryExpr u -> {
                checkExpr(u.operand(), env);
                yield null;
            }
            case FieldAccessExpr fa -> {
                checkExpr(fa.object(), env);
                yield null;
            }
            case TupleExpr t -> {
                for (var e : t.elements()) checkExpr(e, env);
                yield null;
            }
            case ConstructorExpr c -> {
                for (var f : c.fields()) checkExpr(f.value(), env);
                yield null;
            }
            case LiteralExpr _, HoleExpr _ -> null;
        };
    }

    private TokenResult checkCall(CallExpr call, ProtoEnv env) {
        if (call.callee() instanceof FieldAccessExpr fa) {
            String method = fa.fieldName();
            if (fa.object() instanceof IdentExpr objId) {
                String objName = objId.name();
                // Static call: Proto.start() / Proto.action(consume token)
                if (automata.containsKey(objName)) {
                    ProtocolAutomaton aut = automata.get(objName);
                    if (method.equals("start") || method.equals("init") || method.equals("open") || method.equals("new")) {
                        return new TokenResult(objName, aut.initialState(), nextInstanceId(env));
                    }
                    // Transition with first arg as token
                    if (!call.args().isEmpty()) {
                        Expr firstArg = call.args().get(0);
                        TokenResult tok = checkExpr(firstArg, env);
                        for (int i = 1; i < call.args().size(); i++) checkExpr(call.args().get(i), env);
                        if (tok != null) {
                            TokenResult next = applyTransition(aut, objName, tok, method, call.span());
                            if (next != null) {
                                markArgTokenConsumed(firstArg, env);
                            }
                            return next;
                        }
                    }
                }
                // Instance method call: token.action(...)
                TokenState st = env.tokens.get(objName);
                if (st != null) {
                    if (st.consumed) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-PROTO-004",
                            "Transition '" + method + "' on consumed protocol token '" + objName + "'",
                            call.span()
                        ));
                        return null;
                    }
                    ProtocolAutomaton aut = automata.get(st.protoName);
                    for (var arg : call.args()) checkExpr(arg, env);
                    TokenResult next = applyTransition(aut, st.protoName,
                        new TokenResult(st.protoName, st.state, st.instanceId), method, call.span());
                    if (next != null) {
                        st.state = next.state;
                    }
                    return next;
                }
            }
        }
        checkExpr(call.callee(), env);
        for (var arg : call.args()) checkExpr(arg, env);
        return null;
    }

    private void markArgTokenConsumed(Expr arg, ProtoEnv env) {
        Expr unwrapped = arg instanceof ParenExpr p ? p.inner() : arg;
        if (unwrapped instanceof IdentExpr id) {
            TokenState st = env.tokens.get(id.name());
            if (st != null) {
                st.consumed = true;
            }
        }
    }

    private TokenResult applyTransition(
        ProtocolAutomaton aut,
        String protoName,
        TokenResult tok,
        String action,
        SourceSpan span
    ) {
        if (!tok.protoName.equals(protoName)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-PROTO-004",
                "Protocol mismatch: expected token of '" + protoName + "', got '" + tok.protoName + "'",
                span
            ));
            return null;
        }
        for (var t : aut.transitions()) {
            if (t.from().equals(tok.state) && t.action().equals(action)) {
                return new TokenResult(protoName, t.to(), tok.instanceId);
            }
        }
        diagnostics.add(Diagnostic.error(
            "NGJ-PROTO-004",
            "Invalid protocol transition '" + action + "' on '" + protoName + "' from state '" + tok.state + "'",
            span
        ));
        return null;
    }

    private void checkMustResolveOnExit(ProtoEnv env, String returnedVar, SourceSpan span, String exitKind) {
        for (var entry : env.tokens.entrySet()) {
            String varName = entry.getKey();
            if (varName.equals(returnedVar)) continue;
            TokenState st = entry.getValue();
            if (st.consumed) continue;
            if (!mustResolveProtocols.contains(st.protoName)) continue;
            ProtocolAutomaton aut = automata.get(st.protoName);
            if (aut != null && !aut.finalStates().contains(st.state)) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-PROTO-002",
                    "@must_resolve protocol '" + st.protoName + "' token '" + varName
                        + "' in non-final state '" + st.state + "' at " + exitKind,
                    span
                ));
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
            default -> type;
        };
    }

    private String protocolNameOf(TypeExpr type) {
        TypeExpr resolved = resolveTypeAlias(type);
        if (resolved == null) return null;
        return switch (resolved) {
            case SimpleTypeExpr s -> automata.containsKey(s.name()) ? s.name() : null;
            case GenericTypeExpr g -> automata.containsKey(g.name()) ? g.name() : null;
            case CapabilityTypeExpr c -> protocolNameOf(c.inner());
            default -> null;
        };
    }

    private String initialStateOf(TypeExpr type, ProtocolAutomaton aut) {
        TypeExpr resolved = resolveTypeAlias(type);
        if (resolved instanceof CapabilityTypeExpr c) {
            resolved = resolveTypeAlias(c.inner());
        }
        if (resolved instanceof GenericTypeExpr g && !g.typeArgs().isEmpty()
            && g.typeArgs().get(0) instanceof SimpleTypeExpr st && aut.states().contains(st.name())) {
            return st.name();
        }
        return aut.initialState();
    }

    private int nextInstanceId(ProtoEnv env) {
        return ++env.nextId;
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

    private record TokenResult(String protoName, String state, int instanceId) {}

    private static final class TokenState {
        final String protoName;
        String state;
        final int instanceId;
        boolean consumed;

        TokenState(String protoName, String state, int instanceId, boolean consumed) {
            this.protoName = protoName;
            this.state = state;
            this.instanceId = instanceId;
            this.consumed = consumed;
        }

        TokenState copy() {
            return new TokenState(protoName, state, instanceId, consumed);
        }
    }

    private static final class ProtoEnv {
        final Map<String, TokenState> tokens = new LinkedHashMap<>();
        int nextId = 0;

        void bind(String varName, String protoName, String state, int instanceId) {
            tokens.put(varName, new TokenState(protoName, state, instanceId, false));
        }

        ProtoEnv copy() {
            ProtoEnv c = new ProtoEnv();
            c.nextId = this.nextId;
            for (var e : tokens.entrySet()) c.tokens.put(e.getKey(), e.getValue().copy());
            return c;
        }

        void copyFrom(ProtoEnv other) {
            this.tokens.clear();
            for (var e : other.tokens.entrySet()) this.tokens.put(e.getKey(), e.getValue().copy());
        }

        void mergeFrom(ProtoEnv a, ProtoEnv b, List<Diagnostic> diagnostics, SourceSpan span) {
            for (var key : new LinkedHashSet<>(tokens.keySet())) {
                TokenState sa = a.tokens.get(key);
                TokenState sb = b.tokens.get(key);
                if (sa != null && sb != null) {
                    if (sa.consumed != sb.consumed || !sa.state.equals(sb.state)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-PROTO-004",
                            "Inconsistent protocol state across branches for '" + key + "': '"
                                + sa.state + "' vs '" + sb.state + "'",
                            span
                        ));
                    }
                    tokens.put(key, sa.copy());
                }
            }
        }
    }
}
