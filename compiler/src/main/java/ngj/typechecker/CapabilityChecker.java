package ngj.typechecker;

import ngj.ast.*;
import ngj.diagnostic.*;

import java.util.*;

/**
 * Phase 2 & 3 Capability, Effect, Separation & Budget Checker (CAP-02, SEP-01, BUDGET-01):
 * - Effect subtyping & exceedance (NGJ-EFFECT-001)
 * - Capture & lifetime escape (NGJ-CAP-001)
 * - Flow-sensitive consume revocation (NGJ-CAP-002)
 * - Secret.map / List.map / filter local purity & Secret exposure (NGJ-CAP-003)
 * - Safe Mode (NGJ-SAFE-001) & @trusted_pure allowlist (NGJ-CAP-004)
 * - scope/fork separation & Arena.close live-view/unjoined-fork checks (NGJ-SEP-001, NGJ-SEP-002)
 * - Linear Budget<Kind> split/spend bounds & minting (NGJ-BUDGET-001, NGJ-BUDGET-002)
 */
public final class CapabilityChecker {
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Map<String, FnDecl> fnDecls = new HashMap<>();
    private final Map<String, ProtocolDecl> protocols = new HashMap<>();
    private final Map<String, TypeAliasDecl> typeAliases = new HashMap<>();
    private final Map<String, TopLevelDecl> typeDecls = new HashMap<>();
    private final String profile;
    private final Set<String> trustedAllowlist;

    public CapabilityChecker() {
        this("standard", Set.of());
    }

    public CapabilityChecker(String profile, Set<String> trustedAllowlist) {
        this.profile = profile != null ? profile : "standard";
        this.trustedAllowlist = trustedAllowlist != null ? trustedAllowlist : Set.of();
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }

    public void checkAll(List<CompilationUnit> units) {
        fnDecls.clear();
        protocols.clear();
        typeAliases.clear();
        typeDecls.clear();

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                if (decl instanceof FnDecl fn) {
                    fnDecls.put(fn.name(), fn);
                } else if (decl instanceof ProtocolDecl proto) {
                    protocols.put(proto.name(), proto);
                } else if (decl instanceof TypeAliasDecl ta) {
                    typeAliases.put(ta.name(), ta);
                } else if (decl instanceof RecordDecl r) {
                    typeDecls.put(r.name(), r);
                } else if (decl instanceof DataDecl d) {
                    typeDecls.put(d.name(), d);
                } else if (decl instanceof EnumDecl e) {
                    typeDecls.put(e.name(), e);
                }
            }
        }

        for (var unit : units) {
            for (var decl : unit.declarations()) {
                switch (decl) {
                    case FnDecl fn -> checkFn(fn);
                    case RecordDecl rec -> checkAnnotationsSafeMode(rec.annotations(), rec.span());
                    case EnumDecl en -> checkAnnotationsSafeMode(en.annotations(), en.span());
                    default -> {}
                }
            }
        }
    }

    private void checkAnnotationsSafeMode(List<Annotation> annotations, SourceSpan span) {
        for (var ann : annotations) {
            String n = ann.name();
            if (n.equals("unchecked") || n.equals("unsafe") || n.equals("jni") || n.equals("reflect")) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-SAFE-001",
                    "Unsafe feature '@" + n + "' is forbidden in NGJ Safe Mode",
                    ann.span() != null ? ann.span() : span
                ));
            }
        }
    }

    private void checkFn(FnDecl fn) {
        checkAnnotationsSafeMode(fn.annotations(), fn.span());

        for (var ann : fn.annotations()) {
            if (ann.name().equals("trusted_pure")) {
                if ("restricted".equals(profile) || !trustedAllowlist.contains(fn.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-CAP-004",
                        "@trusted_pure on '" + fn.name() + "' is not allowlisted (or forbidden under restricted profile)",
                        ann.span() != null ? ann.span() : fn.span()
                    ));
                }
            }
        }

        CapEnv env = new CapEnv(fn.effects(), fn.captureBounds());
        for (var param : fn.params()) {
            TypeExpr resolvedParamType = resolveTypeAlias(param.type());
            VarMode mode = modeFromType(resolvedParamType, param.isConsume());
            boolean isSecret = isSecretType(resolvedParamType);
            boolean isLinear = isLinearType(resolvedParamType) || param.isConsume();
            env.declare(param.name(), resolvedParamType, mode, isLinear, isSecret, 0);
            if (param.isConsume()) {
                env.consumeParams.add(param.name());
            }
            linkCaptureTargets(param.name(), resolvedParamType, env);
        }

        if (fn.body() != null) {
            checkBlock(fn.body(), env, true);
        }
    }

    private void checkBlock(BlockExpr block, CapEnv env, boolean isFunctionBody) {
        if (block == null) return;
        env.depth++;
        for (var stmt : block.statements()) {
            checkStmt(stmt, env);
        }
        if (block.tailExpr() != null) {
            checkExpr(block.tailExpr(), env);
            if (isFunctionBody) {
                checkEscape(block.tailExpr(), env, block.tailExpr().span());
            }
        }
        env.depth--;
    }

    private void checkStmt(Statement stmt, CapEnv env) {
        switch (stmt) {
            case ValStmt val -> {
                checkExpr(val.initializer(), env);
                TypeExpr effectiveType = val.explicitType() != null
                    ? resolveTypeAlias(val.explicitType())
                    : inferResourceType(val.initializer(), env);
                VarMode mode = modeFromType(effectiveType, false);
                boolean isSecret = isSecretType(effectiveType) || isSecretExpr(val.initializer(), env);
                boolean isLinear = isLinearType(effectiveType) || isLinearExpr(val.initializer(), env);
                env.declare(val.name(), effectiveType, mode, isLinear, isSecret, env.depth);
                linkAliasOrView(val.name(), val.initializer(), env);
                checkBudgetLiteralInit(val.name(), val.initializer(), env);
            }
            case LetStmt let -> {
                checkExpr(let.initializer(), env);
                TypeExpr effectiveType = let.explicitType() != null
                    ? resolveTypeAlias(let.explicitType())
                    : inferResourceType(let.initializer(), env);
                VarMode mode = modeFromType(effectiveType, false);
                boolean isSecret = isSecretType(effectiveType) || isSecretExpr(let.initializer(), env);
                boolean isLinear = isLinearType(effectiveType) || isLinearExpr(let.initializer(), env);
                env.declare(let.name(), effectiveType, mode, isLinear, isSecret, env.depth);
                linkAliasOrView(let.name(), let.initializer(), env);
            }
            case VarStmt varStmt -> {
                checkExpr(varStmt.initializer(), env);
                TypeExpr effectiveType = varStmt.explicitType() != null
                    ? resolveTypeAlias(varStmt.explicitType())
                    : inferResourceType(varStmt.initializer(), env);
                VarMode mode = VarMode.UPDATE;
                boolean isSecret = isSecretType(effectiveType) || isSecretExpr(varStmt.initializer(), env);
                boolean isLinear = isLinearType(effectiveType);
                env.declare(varStmt.name(), effectiveType, mode, isLinear, isSecret, env.depth);
                linkAliasOrView(varStmt.name(), varStmt.initializer(), env);
            }
            case AssignStmt assign -> {
                checkExpr(assign.value(), env);
                recordAccess(assign.name(), VarMode.UPDATE, env, assign.span());
                linkAliasOrView(assign.name(), assign.value(), env);
            }
            case ReturnStmt ret -> {
                if (ret.value() != null) {
                    checkExpr(ret.value(), env);
                    checkEscape(ret.value(), env, ret.span());
                }
            }
            case ExprStmt es -> checkExpr(es.expr(), env);
            case TupleDestructureStmt td -> {
                checkExpr(td.initializer(), env);
                boolean isBudgetSplit = isBudgetSplitCall(td.initializer(), env);
                Expr unwrappedInit = td.initializer();
                while (unwrappedInit instanceof ParenExpr p) unwrappedInit = p.inner();
                TupleExpr tupleInit = unwrappedInit instanceof TupleExpr te ? te : null;
                AliasGraph initGraph = !isBudgetSplit ? computeAliasGraph(td.initializer(), env, Map.of()) : AliasGraph.EMPTY;
                for (int i = 0; i < td.names().size(); i++) {
                    String elemName = td.names().get(i);
                    Expr compInit = (tupleInit != null && i < tupleInit.elements().size()) ? tupleInit.elements().get(i) : null;
                    TypeExpr t = i < td.explicitTypes().size() ? resolveTypeAlias(td.explicitTypes().get(i)) : null;
                    if (t == null && isBudgetSplit) {
                        t = new SimpleTypeExpr("Budget", td.span());
                    } else if (t == null && compInit != null) {
                        t = inferResourceType(compInit, env);
                    }
                    AliasGraph elemGraph = !isBudgetSplit ? initGraph.elementAt(i) : AliasGraph.EMPTY;
                    boolean isLin = isLinearType(t)
                        || (!isBudgetSplit && compInit != null && isLinearExpr(compInit, env))
                        || (!isBudgetSplit && elemGraph.directRoots().stream().anyMatch(env::isLinear));
                    boolean isSec = isSecretType(t)
                        || (compInit != null && isSecretExpr(compInit, env))
                        || elemGraph.directRoots().stream().anyMatch(env::isSecret);
                    env.declare(elemName, t, modeFromType(t, false), isLin, isSec, env.depth);
                    if (!isBudgetSplit) {
                        if (compInit != null) {
                            linkAliasOrView(elemName, compInit, env);
                        } else if (!elemGraph.isEmpty()) {
                            bindAliasGraph(elemName, elemGraph, env);
                        } else if (!initGraph.directRoots().isEmpty()
                            && (isLin || isSec || env.isCapabilityOrBorrowed(elemName))) {
                            bindAliasGraph(elemName, new AliasGraph(initGraph.directRoots(), Map.of(), Map.of()), env);
                        }
                    }
                }
            }
            case InvariantStmt inv -> checkExpr(inv.condition(), env);
            case ProofStmt _ -> {} // Ghost proof blocks have no runtime capabilities
            case AssumeStmt _ -> {}
        }
    }

    private void checkExpr(Expr expr, CapEnv env) {
        if (expr == null) return;
        switch (expr) {
            case IdentExpr id -> {
                useVariable(id.name(), VarMode.READ, env, id.span());
                if (env.isSecret(id.name()) && !env.inSecretMap) {
                    // Direct Secret<T> value used in non-Secret.map context (e.g. expression/branch)
                }
            }
            case ConsumeExpr consume -> {
                if (consume.target() instanceof IdentExpr id) {
                    consumeVariable(id.name(), env, consume.span());
                } else {
                    checkExpr(consume.target(), env);
                }
            }
            case CallExpr call -> checkCall(call, env);
            case FieldAccessExpr fa -> {
                checkExpr(fa.object(), env);
                if (fa.object() instanceof IdentExpr id) {
                    // Check reflection/Unsafe access
                    if (id.name().equals("Unsafe") || id.name().equals("Class")) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-SAFE-001",
                            "Unsafe/reflection access '" + id.name() + "." + fa.fieldName() + "' is forbidden in Safe Mode",
                            fa.span()
                        ));
                    }
                }
            }
            case BinaryExpr bin -> {
                checkExpr(bin.left(), env);
                checkExpr(bin.right(), env);
                if (isSecretExpr(bin.left(), env) || isSecretExpr(bin.right(), env)) {
                    if (!env.inSecretMap) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-CAP-003",
                            "Cannot operate directly on Secret<T> outside Secret.map",
                            bin.span()
                        ));
                    }
                }
            }
            case UnaryExpr un -> checkExpr(un.operand(), env);
            case ParenExpr par -> checkExpr(par.inner(), env);
            case PropagateExpr prop -> checkExpr(prop.inner(), env);
            case TupleExpr tup -> {
                for (var e : tup.elements()) checkExpr(e, env);
            }
            case ConstructorExpr ctor -> {
                for (var f : ctor.fields()) checkExpr(f.value(), env);
            }
            case BlockExpr block -> checkBlock(block, env, false);
            case IfExpr ifExpr -> {
                checkExpr(ifExpr.condition(), env);
                if (isSecretExpr(ifExpr.condition(), env) && !env.inSecretMap) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-CAP-003",
                        "Cannot branch directly on Secret<T> outside Secret.map",
                        ifExpr.condition().span()
                    ));
                }
                CapEnv thenEnv = env.copy();
                checkBlock(ifExpr.thenBranch(), thenEnv, false);
                CapEnv elseEnv = env.copy();
                if (ifExpr.elseBranch() != null) {
                    if (ifExpr.elseBranch() instanceof BlockExpr eb) {
                        checkBlock(eb, elseEnv, false);
                    } else {
                        checkExpr(ifExpr.elseBranch(), elseEnv);
                    }
                }
                boolean thenReturns = blockAlwaysReturns(ifExpr.thenBranch());
                boolean elseReturns = ifExpr.elseBranch() != null && exprAlwaysReturns(ifExpr.elseBranch());
                if (thenReturns && !elseReturns) {
                    env.copyStatesFrom(elseEnv);
                } else if (!thenReturns && elseReturns) {
                    env.copyStatesFrom(thenEnv);
                } else if (!thenReturns && !elseReturns) {
                    env.mergeStates(thenEnv, elseEnv);
                }
            }
            case MatchExpr matchExpr -> {
                checkExpr(matchExpr.scrutinee(), env);
                List<CapEnv> armEnvs = new ArrayList<>();
                for (var arm : matchExpr.arms()) {
                    CapEnv armEnv = env.copy();
                    checkExpr(arm.body(), armEnv);
                    if (!exprAlwaysReturns(arm.body())) {
                        armEnvs.add(armEnv);
                    }
                }
                if (!armEnvs.isEmpty()) {
                    CapEnv merged = armEnvs.get(0);
                    for (int i = 1; i < armEnvs.size(); i++) {
                        merged.mergeStates(merged, armEnvs.get(i));
                    }
                    env.copyStatesFrom(merged);
                }
            }
            case WhileExpr whileExpr -> {
                checkExpr(whileExpr.condition(), env);
                env.loopDepth++;
                env.loopEntryDepths.addLast(env.depth);
                checkBlock(whileExpr.body(), env, false);
                env.loopEntryDepths.removeLast();
                env.loopDepth--;
            }
            case ForExpr forExpr -> {
                checkExpr(forExpr.iterable(), env);
                env.loopDepth++;
                env.loopEntryDepths.addLast(env.depth);
                checkBlock(forExpr.body(), env, false);
                env.loopEntryDepths.removeLast();
                env.loopDepth--;
            }
            case LambdaExpr lambda -> checkLambda(lambda, env, false);
            case ScopeExpr scope -> checkScope(scope, env);
            case ForkExpr fork -> {
                if (env.activeScope == null) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-SEP-002",
                        "Orphan 'fork' is only allowed inside a 'scope { ... }' block",
                        fork.span()
                    ));
                    checkBlock(fork.body(), env.copy(), false);
                } else {
                    checkFork(fork, env);
                }
            }
            default -> {}
        }
    }

    private void checkCall(CallExpr call, CapEnv env) {
        if (call.callee() instanceof FieldAccessExpr fa) {
            String method = fa.fieldName();
            if (fa.object() instanceof IdentExpr objId) {
                String target = objId.name();
                if (target.equals("Arena") && method.equals("close")) {
                    if (call.args().size() == 1 && call.args().get(0) instanceof ConsumeExpr ce
                        && ce.target() instanceof IdentExpr arenaId) {
                        // Check if live views or unjoined forks exist on arenaId
                        if (env.hasLiveViews(arenaId.name())) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-SEP-001",
                                "Cannot close Arena '" + arenaId.name() + "' while live views/slices exist",
                                call.span()
                            ));
                        }
                        if (env.activeScope != null && env.activeScope.hasUnjoinedForkUsing(arenaId.name())) {
                            diagnostics.add(Diagnostic.error(
                                "NGJ-SEP-001",
                                "Cannot close Arena '" + arenaId.name() + "' while unjoined fork tasks may use it",
                                call.span()
                            ));
                        }
                    }
                } else if (target.equals("Budget")) {
                    if (method.equals("mint") || method.equals("new") || method.equals("create")) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-BUDGET-002",
                            "Unauthorized Budget minting via Budget." + method,
                            call.span()
                        ));
                    }
                } else if ((target.equals("Secret") || target.equals("List"))
                    && (method.equals("map") || method.equals("filter"))) {
                    checkHigherOrderPurity(call, env, target.equals("Secret"));
                    return;
                } else if (target.equals("Secret") && method.equals("expose")) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-CAP-003",
                        "Direct Secret.expose() is forbidden; use Secret.map or Declassify",
                        call.span()
                    ));
                } else if (target.equals("Declassify") && method.equals("declassify")) {
                    requireEffect("Declassify", env, call.span());
                } else if (target.equals("Unsafe") || target.equals("System")) {
                    if (target.equals("Unsafe")) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-SAFE-001",
                            "Unsafe call is forbidden in Safe Mode",
                            call.span()
                        ));
                    }
                } else if (protocols.containsKey(target)) {
                    checkProtocolTransitionEffects(target, method, call.args(), env, call.span());
                    checkExpr(fa.object(), env);
                    for (var arg : call.args()) checkExpr(arg, env);
                    if (!call.args().isEmpty()) {
                        Expr firstArg = call.args().get(0);
                        Expr unwrapped = firstArg instanceof ParenExpr p ? p.inner() : firstArg;
                        if (unwrapped instanceof IdentExpr argId
                            && env.states.get(argId.name()) == CapState.ALIVE) {
                            consumeVariable(argId.name(), env, firstArg.span());
                        }
                    }
                    return;
                }
            }
            // Instance method calls like xs.map(...), xs.filter(...), b.split(...)
            if (method.equals("map") || method.equals("filter")) {
                checkHigherOrderPurity(call, env, isSecretExpr(fa.object(), env));
                return;
            }
            if (method.equals("split") || method.equals("spend")) {
                checkExpr(fa.object(), env);
                checkBudgetOp(call, env);
                if (fa.object() instanceof IdentExpr objId && objId.name().equals("Budget")) {
                    if (!call.args().isEmpty()) {
                        Expr firstArg = call.args().get(0);
                        Expr unwrapped = firstArg instanceof ParenExpr p ? p.inner() : firstArg;
                        if (unwrapped instanceof IdentExpr bId && env.states.get(bId.name()) == CapState.ALIVE) {
                            consumeVariable(bId.name(), env, firstArg.span());
                        }
                    }
                } else {
                    Expr unwrappedObj = fa.object() instanceof ParenExpr p ? p.inner() : fa.object();
                    if (unwrappedObj instanceof IdentExpr bId
                        && (method.equals("spend") || isBudgetBinding(bId.name(), env))
                        && env.states.get(bId.name()) == CapState.ALIVE) {
                        consumeVariable(bId.name(), env, fa.object().span());
                    }
                }
                return;
            }
            if (fa.object() instanceof IdentExpr objId) {
                TypeExpr objType = env.types.get(objId.name());
                if (objType != null) {
                    String objTypeName = typeName(objType);
                    if (protocols.containsKey(objTypeName)) {
                        checkProtocolTransitionEffects(objTypeName, method, List.of(), env, call.span());
                    }
                }
            }
            checkExpr(fa.object(), env);
            for (var arg : call.args()) checkExpr(arg, env);
            return;
        }

        if (call.callee() instanceof IdentExpr fnId) {
            String name = fnId.name();
            if (name.equals("declassify")) {
                requireEffect("Declassify", env, call.span());
            }
            var fn = fnDecls.get(name);
            if (fn != null) {
                for (var eff : fn.effects()) {
                    requireEffect(eff, env, call.span());
                }
                for (int i = 0; i < Math.min(fn.params().size(), call.args().size()); i++) {
                    Param p = fn.params().get(i);
                    Expr arg = call.args().get(i);
                    if (p.isConsume()) {
                        if (arg instanceof ConsumeExpr ce && ce.target() instanceof IdentExpr argId) {
                            consumeVariable(argId.name(), env, arg.span());
                        } else if (arg instanceof IdentExpr argId) {
                            consumeVariable(argId.name(), env, arg.span());
                        } else {
                            checkExpr(arg, env);
                        }
                    } else {
                        checkExpr(arg, env);
                        if (resolveTypeAlias(p.type()) instanceof CapabilityTypeExpr cap
                            && cap.mode() == CapabilityTypeExpr.CapabilityMode.UPDATE) {
                            Expr unwrapped = arg instanceof ParenExpr pe ? pe.inner() : arg;
                            if (unwrapped instanceof IdentExpr argId) {
                                boolean hasReadRoot = env.modes.get(argId.name()) == VarMode.READ;
                                for (String root : env.resolveAllRoots(argId.name())) {
                                    if (env.modes.get(root) == VarMode.READ) {
                                        hasReadRoot = true;
                                        break;
                                    }
                                }
                                if (hasReadRoot) {
                                    diagnostics.add(Diagnostic.error(
                                        "NGJ-SEP-001",
                                        "Cannot pass 'read' capability '" + argId.name() + "' to 'update' parameter '" + p.name() + "'",
                                        arg.span() != null ? arg.span() : call.span()
                                    ));
                                }
                                recordAccess(argId.name(), VarMode.UPDATE, env, arg.span());
                            }
                        }
                    }
                }
                return;
            }
        }

        TypeExpr calleeType = resolveTypeAlias(inferExprType(call.callee(), env));
        if (calleeType instanceof FunctionTypeExpr fnType) {
            for (var eff : fnType.effects()) {
                requireEffect(eff, env, call.span());
            }
        }
        checkExpr(call.callee(), env);
        for (var arg : call.args()) checkExpr(arg, env);
    }

    private void checkBudgetOp(CallExpr call, CapEnv env) {
        for (var arg : call.args()) {
            checkExpr(arg, env);
            if (arg instanceof LiteralExpr lit && lit.value() instanceof Number n) {
                long amount = n.longValue();
                if (amount < 0) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-BUDGET-001",
                        "Budget amount cannot be negative: " + amount,
                        arg.span()
                    ));
                }
            } else if (arg instanceof UnaryExpr u && u.op() == UnaryExpr.UnaryOp.NEG) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-BUDGET-001",
                    "Budget amount cannot be negative",
                    arg.span()
                ));
            } else if (arg instanceof BinaryExpr b && b.op() == BinaryExpr.BinaryOp.ADD
                && b.left() instanceof CallExpr remCall
                && remCall.callee() instanceof FieldAccessExpr fa && fa.fieldName().equals("remaining")
                && b.right() instanceof LiteralExpr lit && lit.value() instanceof Number n
                && n.longValue() > 0) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-BUDGET-001",
                    "Budget amount exceeds remaining budget by " + n.longValue(),
                    arg.span()
                ));
            }
        }
        // Check over-limit if budget variable has a statically known limit
        if (call.args().size() >= 2 && call.args().get(0) instanceof IdentExpr bId
            && call.args().get(1) instanceof LiteralExpr lit && lit.value() instanceof Number n) {
            Long known = env.budgetLimits.get(bId.name());
            long req = n.longValue();
            if (known != null && req > known) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-BUDGET-001",
                    "Budget amount " + req + " exceeds available budget " + known + " on '" + bId.name() + "'",
                    call.span()
                ));
            } else if (known != null && req >= 0) {
                env.budgetLimits.put(bId.name(), known - req);
            }
        } else if (call.args().size() >= 2 && call.args().get(0) instanceof ConsumeExpr ce
            && ce.target() instanceof IdentExpr bId
            && call.args().get(1) instanceof LiteralExpr lit && lit.value() instanceof Number n) {
            Long known = env.budgetLimits.get(bId.name());
            long req = n.longValue();
            if (known != null && req > known) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-BUDGET-001",
                    "Budget amount " + req + " exceeds available budget " + known + " on '" + bId.name() + "'",
                    call.span()
                ));
            }
        }
    }

    private void checkHigherOrderPurity(CallExpr call, CapEnv env, boolean isSecretMap) {
        if (call.callee() instanceof FieldAccessExpr fa) {
            checkExpr(fa.object(), env);
        }
        boolean prevInSecret = env.inSecretMap;
        if (isSecretMap) {
            env.inSecretMap = true;
        }
        for (var arg : call.args()) {
            if (arg instanceof LambdaExpr lambda) {
                checkLambda(lambda, env, true);
            } else {
                checkExpr(arg, env);
            }
        }
        env.inSecretMap = prevInSecret;
    }

    private void checkLambda(LambdaExpr lambda, CapEnv env, boolean requireLocalPure) {
        CapEnv lambdaEnv = env.copy();
        lambdaEnv.depth++;
        for (var p : lambda.params()) {
            lambdaEnv.declare(p.name(), p.type(), modeFromType(p.type(), p.isConsume()),
                isLinearType(p.type()), isSecretType(p.type()), lambdaEnv.depth);
        }
        if (requireLocalPure) {
            checkLocalPurity(lambda.body(), env.declaredVarsSnapshot(), env, lambda.span());
        }
        checkBlock(lambda.body(), lambdaEnv, false);
    }

    private void checkLocalPurity(BlockExpr block, Set<String> outerVars, CapEnv env, SourceSpan contextSpan) {
        if (block == null) return;
        for (var stmt : block.statements()) {
            switch (stmt) {
                case AssignStmt assign -> {
                    if (outerVars.contains(assign.name())) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-CAP-003",
                            "Pure callback cannot mutate outer variable '" + assign.name() + "'",
                            assign.span()
                        ));
                    }
                }
                case ExprStmt es -> checkExprLocalPurity(es.expr(), outerVars, env, contextSpan);
                case ReturnStmt ret -> {
                    if (ret.value() != null) checkExprLocalPurity(ret.value(), outerVars, env, contextSpan);
                }
                case ValStmt v -> checkExprLocalPurity(v.initializer(), outerVars, env, contextSpan);
                case VarStmt v -> checkExprLocalPurity(v.initializer(), outerVars, env, contextSpan);
                default -> {}
            }
        }
        if (block.tailExpr() != null) {
            checkExprLocalPurity(block.tailExpr(), outerVars, env, contextSpan);
        }
    }

    private void checkExprLocalPurity(Expr expr, Set<String> outerVars, CapEnv env, SourceSpan contextSpan) {
        if (expr == null) return;
        switch (expr) {
            case IdentExpr id -> {
                if (outerVars.contains(id.name()) && env.isCapabilityOrBorrowed(id.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-CAP-003",
                        "Pure callback cannot capture capability-bearing variable '" + id.name() + "'",
                        id.span() != null ? id.span() : contextSpan
                    ));
                }
            }
            case ConsumeExpr c -> {
                if (c.target() instanceof IdentExpr id && outerVars.contains(id.name())) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-CAP-003",
                        "Pure callback cannot consume outer resource '" + id.name() + "'",
                        c.span()
                    ));
                }
            }
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr id) {
                    var fn = fnDecls.get(id.name());
                    if (fn != null && !fn.effects().isEmpty()) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-CAP-003",
                            "Pure callback cannot invoke effectful function '" + id.name() + "'",
                            call.span()
                        ));
                    }
                } else if (call.callee() instanceof FieldAccessExpr fa && fa.object() instanceof IdentExpr objId) {
                    ProtocolDecl proto = protocols.get(objId.name());
                    if (proto != null) {
                        for (var t : proto.transitions()) {
                            if (t.name().equals(fa.fieldName()) && !t.effects().isEmpty()) {
                                diagnostics.add(Diagnostic.error(
                                    "NGJ-CAP-003",
                                    "Pure callback cannot invoke effectful protocol transition '" + objId.name() + "." + fa.fieldName() + "'",
                                    call.span()
                                ));
                                break;
                            }
                        }
                    }
                }
                checkExprLocalPurity(call.callee(), outerVars, env, contextSpan);
                for (var a : call.args()) checkExprLocalPurity(a, outerVars, env, contextSpan);
            }
            case FieldAccessExpr fa -> checkExprLocalPurity(fa.object(), outerVars, env, contextSpan);
            case BinaryExpr b -> {
                checkExprLocalPurity(b.left(), outerVars, env, contextSpan);
                checkExprLocalPurity(b.right(), outerVars, env, contextSpan);
            }
            case UnaryExpr u -> checkExprLocalPurity(u.operand(), outerVars, env, contextSpan);
            case ParenExpr p -> checkExprLocalPurity(p.inner(), outerVars, env, contextSpan);
            case BlockExpr b -> checkLocalPurity(b, outerVars, env, contextSpan);
            case IfExpr i -> {
                checkExprLocalPurity(i.condition(), outerVars, env, contextSpan);
                checkLocalPurity(i.thenBranch(), outerVars, env, contextSpan);
                if (i.elseBranch() != null) checkExprLocalPurity(i.elseBranch(), outerVars, env, contextSpan);
            }
            default -> {}
        }
    }

    private void checkScope(ScopeExpr scope, CapEnv env) {
        ScopeContext prevScope = env.activeScope;
        ScopeContext ctx = new ScopeContext(env.depth);
        env.activeScope = ctx;
        checkBlock(scope.body(), env, false);
        env.activeScope = prevScope;
    }

    private void checkFork(ForkExpr fork, CapEnv env) {
        ScopeContext scope = env.activeScope;
        ForkRecord record = new ForkRecord();
        scope.forks.add(record);
        CapEnv forkEnv = env.copy();
        forkEnv.currentFork = record;
        checkBlock(fork.body(), forkEnv, false);

        // Check separation against all previous forks in the same scope
        for (int i = 0; i < scope.forks.size() - 1; i++) {
            ForkRecord prev = scope.forks.get(i);
            for (var entry : record.accesses.entrySet()) {
                String varName = entry.getKey();
                VarMode mode = entry.getValue();
                VarMode prevMode = prev.accesses.get(varName);
                if (prevMode != null) {
                    if (mode == VarMode.UPDATE || prevMode == VarMode.UPDATE || env.isLinear(varName)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-SEP-001",
                            "Parallel fork conflict on '" + varName + "': conflicting update or unsplit linear resource",
                            fork.span()
                        ));
                    }
                }
            }
        }
    }

    private void requireEffect(String effect, CapEnv env, SourceSpan span) {
        if (!env.allowedEffects.contains(effect)) {
            diagnostics.add(Diagnostic.error(
                "NGJ-EFFECT-001",
                "Effect '" + effect + "' is not permitted by enclosing function's effects("
                    + String.join(", ", env.allowedEffects) + ")",
                span
            ));
        }
    }

    private void useVariable(String name, VarMode accessMode, CapEnv env, SourceSpan span) {
        CapState state = env.states.get(name);
        if (state == null) return;
        if (state == CapState.CONSUMED || state == CapState.MAYBE_CONSUMED) {
            diagnostics.add(Diagnostic.error(
                "NGJ-CAP-002",
                "Use of consumed or potentially consumed binding '" + name + "'",
                span
            ));
            return;
        }
        recordAccess(name, accessMode, env, span);
    }

    private void consumeVariable(String name, CapEnv env, SourceSpan span) {
        CapState state = env.states.get(name);
        if (state == null) return;
        Set<String> targets = new LinkedHashSet<>();
        targets.add(name);
        targets.addAll(env.resolveAllRoots(name));

        for (String target : targets) {
            if (env.modes.get(target) == VarMode.READ) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-CAP-002",
                    "Cannot consume 'read'-borrowed binding '" + name + "'",
                    span
                ));
                return;
            }
        }
        for (String target : targets) {
            CapState tState = env.states.get(target);
            if (tState == CapState.CONSUMED || tState == CapState.MAYBE_CONSUMED) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-CAP-002",
                    "Double or conditional consume of '" + name + "'",
                    span
                ));
                return;
            }
        }
        if (env.loopDepth > 0 && !env.loopEntryDepths.isEmpty()) {
            int loopEntryDepth = env.loopEntryDepths.peekLast();
            for (String target : targets) {
                Integer declDepth = env.declDepths.get(target);
                if (declDepth != null && declDepth <= loopEntryDepth) {
                    diagnostics.add(Diagnostic.error(
                        "NGJ-CAP-002",
                        "Cannot consume outer binding '" + name + "' inside a loop",
                        span
                    ));
                    return;
                }
            }
        }
        for (String target : targets) {
            if (env.hasLiveViews(target)) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-CAP-002",
                    "Cannot consume '" + name + "' while live borrowed views exist",
                    span
                ));
                break;
            }
        }
        env.markConsumed(name);
        recordAccess(name, VarMode.UPDATE, env, span);
    }

    private void recordAccess(String name, VarMode mode, CapEnv env, SourceSpan span) {
        Set<String> roots = env.resolveAllRoots(name);
        if (env.currentFork != null) {
            for (String root : roots) {
                env.currentFork.accesses.merge(root, mode, (a, b) -> a == VarMode.UPDATE || b == VarMode.UPDATE ? VarMode.UPDATE : VarMode.READ);
            }
        } else if (env.activeScope != null) {
            // Parent access while forks exist in current scope
            for (String root : roots) {
                for (var f : env.activeScope.forks) {
                    VarMode forkMode = f.accesses.get(root);
                    if (forkMode == VarMode.UPDATE || (forkMode != null && mode == VarMode.UPDATE)) {
                        diagnostics.add(Diagnostic.error(
                            "NGJ-SEP-001",
                            "Parent cannot access '" + name + "' before scope join while forked task updates it",
                            span
                        ));
                    }
                }
            }
        }
    }

    private void checkEscape(Expr expr, CapEnv env, SourceSpan span) {
        if (expr instanceof IdentExpr id) {
            String root = env.viewOf.getOrDefault(id.name(), id.name());
            Integer depth = env.declDepths.get(root);
            if (env.viewOf.containsKey(id.name())
                && ((depth != null && depth > 0) || env.consumeParams.contains(root))) {
                diagnostics.add(Diagnostic.error(
                    "NGJ-CAP-001",
                    "Borrowed view '" + id.name() + "' cannot escape owner '" + root + "'",
                    span
                ));
            }
        }
    }

    private void linkCaptureTargets(String varName, TypeExpr type, CapEnv env) {
        if (type instanceof CapabilityTypeExpr cap) {
            linkCaptureTargets(varName, cap.inner(), env);
        } else if (type instanceof GenericTypeExpr gen) {
            for (var arg : gen.typeArgs()) {
                if (arg instanceof CaptureSetTypeExpr caps) {
                    for (String target : caps.targets()) {
                        env.viewOf.put(varName, target);
                        env.views.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(varName);
                    }
                }
            }
        }
    }

    private void linkAliasOrView(String varName, Expr init, CapEnv env) {
        AliasGraph graph = computeAliasGraph(init, env, Map.of());
        if (!graph.isEmpty()) {
            bindAliasGraph(varName, graph, env);
        }
        linkViewCalls(varName, init, env, new LinkedHashMap<>());
    }

    private void bindAliasGraph(String varName, AliasGraph graph, CapEnv env) {
        if (graph == null || graph.isEmpty()) return;
        AliasGraph existing = env.aliasGraphs.get(varName);
        env.aliasGraphs.put(varName, existing == null ? graph : AliasGraph.merge(existing, graph));
        for (String root : graph.directRoots()) {
            if (root.equals(varName)) continue;
            env.aliasOf.putIfAbsent(varName, root);
            env.aliasRoots.computeIfAbsent(varName, k -> new LinkedHashSet<>()).add(root);
            env.aliases.computeIfAbsent(root, k -> new LinkedHashSet<>()).add(varName);
            if (env.isLinear(root)) {
                env.linearVars.add(varName);
            }
            if (env.isSecret(root)) {
                env.secretVars.add(varName);
            }
            if (env.modes.get(root) == VarMode.READ) {
                env.modes.put(varName, VarMode.READ);
            }
            if (env.viewOf.containsKey(root)) {
                String viewOwner = env.viewOf.get(root);
                env.viewOf.put(varName, viewOwner);
                env.views.computeIfAbsent(viewOwner, k -> new LinkedHashSet<>()).add(varName);
            }
            CapState rootState = env.states.get(root);
            if (rootState == CapState.CONSUMED) {
                env.states.put(varName, CapState.CONSUMED);
            } else if (rootState == CapState.MAYBE_CONSUMED && env.states.get(varName) != CapState.CONSUMED) {
                env.states.put(varName, CapState.MAYBE_CONSUMED);
            }
        }
    }

    private AliasGraph computeAliasGraph(Expr init, CapEnv env, Map<String, AliasGraph> localGraphs) {
        if (init == null) return AliasGraph.EMPTY;
        return switch (init) {
            case ConsumeExpr _ -> AliasGraph.EMPTY; // Ownership transferred; source already consumed
            case ParenExpr p -> computeAliasGraph(p.inner(), env, localGraphs);
            case PropagateExpr p -> computeAliasGraph(p.inner(), env, localGraphs);
            case IdentExpr id -> {
                if (localGraphs.containsKey(id.name())) {
                    yield localGraphs.get(id.name());
                }
                Set<String> roots = env.resolveAllRoots(id.name());
                AliasGraph stored = env.aliasGraphs.get(id.name());
                if (stored != null && !stored.isEmpty()) {
                    Set<String> combined = new LinkedHashSet<>(stored.directRoots());
                    combined.addAll(roots);
                    yield new AliasGraph(combined, stored.tupleElems(), stored.fieldElems());
                }
                yield new AliasGraph(roots, Map.of(), Map.of());
            }
            case TupleExpr t -> {
                Map<Integer, AliasGraph> elems = new LinkedHashMap<>();
                for (int i = 0; i < t.elements().size(); i++) {
                    AliasGraph eg = computeAliasGraph(t.elements().get(i), env, localGraphs);
                    if (!eg.isEmpty()) {
                        elems.put(i, eg);
                    }
                }
                yield elems.isEmpty() ? AliasGraph.EMPTY : new AliasGraph(Set.of(), elems, Map.of());
            }
            case ConstructorExpr c -> {
                Map<String, AliasGraph> fields = new LinkedHashMap<>();
                for (var f : c.fields()) {
                    AliasGraph fg = computeAliasGraph(f.value(), env, localGraphs);
                    if (!fg.isEmpty()) {
                        fields.put(f.name(), fg);
                    }
                }
                yield fields.isEmpty() ? AliasGraph.EMPTY : new AliasGraph(Set.of(), Map.of(), fields);
            }
            case CallExpr c -> {
                if (c.callee() instanceof IdentExpr id) {
                    TopLevelDecl td = typeDecls.get(id.name());
                    if (td instanceof RecordDecl r) {
                        Map<String, AliasGraph> fields = new LinkedHashMap<>();
                        for (int i = 0; i < Math.min(r.params().size(), c.args().size()); i++) {
                            AliasGraph fg = computeAliasGraph(c.args().get(i), env, localGraphs);
                            if (!fg.isEmpty()) {
                                fields.put(r.params().get(i).name(), fg);
                            }
                        }
                        yield fields.isEmpty() ? AliasGraph.EMPTY : new AliasGraph(Set.of(), Map.of(), fields);
                    }
                }
                yield AliasGraph.EMPTY;
            }
            case FieldAccessExpr fa -> {
                AliasGraph objGraph = computeAliasGraph(fa.object(), env, localGraphs);
                AliasGraph fieldGraph = objGraph.fieldAt(fa.fieldName());
                if (!fieldGraph.isEmpty()) {
                    yield fieldGraph;
                }
                try {
                    int idx = Integer.parseInt(fa.fieldName());
                    AliasGraph elemGraph = objGraph.elementAt(idx);
                    if (!elemGraph.isEmpty()) yield elemGraph;
                } catch (NumberFormatException ignored) {}
                yield AliasGraph.EMPTY;
            }
            case IfExpr i -> {
                AliasGraph thenG = computeAliasGraph(i.thenBranch(), env, localGraphs);
                AliasGraph elseG = i.elseBranch() != null ? computeAliasGraph(i.elseBranch(), env, localGraphs) : AliasGraph.EMPTY;
                yield AliasGraph.merge(thenG, elseG);
            }
            case MatchExpr m -> {
                AliasGraph acc = AliasGraph.EMPTY;
                for (var arm : m.arms()) {
                    acc = AliasGraph.merge(acc, computeAliasGraph(arm.body(), env, localGraphs));
                }
                yield acc;
            }
            case BlockExpr b -> {
                Map<String, AliasGraph> inner = new LinkedHashMap<>(localGraphs);
                for (var st : b.statements()) {
                    switch (st) {
                        case ValStmt v -> inner.put(v.name(), computeAliasGraph(v.initializer(), env, inner));
                        case LetStmt l -> inner.put(l.name(), computeAliasGraph(l.initializer(), env, inner));
                        case VarStmt v -> inner.put(v.name(), computeAliasGraph(v.initializer(), env, inner));
                        case AssignStmt a -> inner.put(a.name(), computeAliasGraph(a.value(), env, inner));
                        case TupleDestructureStmt td -> {
                            AliasGraph tg = computeAliasGraph(td.initializer(), env, inner);
                            for (int i = 0; i < td.names().size(); i++) {
                                inner.put(td.names().get(i), tg.elementAt(i));
                            }
                        }
                        default -> {}
                    }
                }
                yield b.tailExpr() != null ? computeAliasGraph(b.tailExpr(), env, inner) : AliasGraph.EMPTY;
            }
            case ScopeExpr s -> computeAliasGraph(s.body(), env, localGraphs);
            case ForkExpr f -> computeAliasGraph(f.body(), env, localGraphs);
            default -> AliasGraph.EMPTY;
        };
    }

    private void linkViewCalls(String varName, Expr init, CapEnv env, Map<String, Expr> localBindings) {
        if (init == null) return;
        switch (init) {
            case ParenExpr p -> linkViewCalls(varName, p.inner(), env, localBindings);
            case PropagateExpr p -> linkViewCalls(varName, p.inner(), env, localBindings);
            case IdentExpr id -> {
                if (localBindings.containsKey(id.name())) {
                    Expr target = localBindings.get(id.name());
                    Map<String, Expr> next = new LinkedHashMap<>(localBindings);
                    next.remove(id.name());
                    linkViewCalls(varName, target, env, next);
                    return;
                }
                if (env.viewOf.containsKey(id.name())) {
                    String viewOwner = env.viewOf.get(id.name());
                    env.viewOf.put(varName, viewOwner);
                    env.views.computeIfAbsent(viewOwner, k -> new LinkedHashSet<>()).add(varName);
                }
            }
            case CallExpr c -> {
                if (c.callee() instanceof FieldAccessExpr fa) {
                    String m = fa.fieldName();
                    if ((m.equals("slice") || m.equals("sliceExact") || m.equals("alloc")
                        || m.equals("borrow") || m.equals("asBytes"))
                        && fa.object() instanceof IdentExpr ownerId) {
                        for (String ownerRoot : env.resolveAllRoots(ownerId.name())) {
                            env.viewOf.put(varName, ownerRoot);
                            env.views.computeIfAbsent(ownerRoot, k -> new LinkedHashSet<>()).add(varName);
                        }
                    }
                }
            }
            case IfExpr i -> {
                linkViewCalls(varName, i.thenBranch(), env, localBindings);
                if (i.elseBranch() != null) {
                    linkViewCalls(varName, i.elseBranch(), env, localBindings);
                }
            }
            case MatchExpr m -> {
                for (var arm : m.arms()) {
                    linkViewCalls(varName, arm.body(), env, localBindings);
                }
            }
            case BlockExpr b -> {
                Map<String, Expr> inner = new LinkedHashMap<>(localBindings);
                for (var st : b.statements()) {
                    switch (st) {
                        case ValStmt v -> inner.put(v.name(), v.initializer());
                        case LetStmt l -> inner.put(l.name(), l.initializer());
                        case VarStmt v -> inner.put(v.name(), v.initializer());
                        case AssignStmt a -> inner.put(a.name(), a.value());
                        default -> {}
                    }
                }
                if (b.tailExpr() != null) {
                    linkViewCalls(varName, b.tailExpr(), env, inner);
                }
            }
            case ScopeExpr s -> linkViewCalls(varName, s.body(), env, localBindings);
            case ForkExpr f -> linkViewCalls(varName, f.body(), env, localBindings);
            default -> {}
        }
    }

    private void checkBudgetLiteralInit(String varName, Expr init, CapEnv env) {
        if (init instanceof CallExpr c && !c.args().isEmpty()
            && c.args().get(0) instanceof LiteralExpr lit && lit.value() instanceof Number n) {
            TypeExpr t = env.types.get(varName);
            if (t != null && typeName(t).equals("Budget")) {
                env.budgetLimits.put(varName, n.longValue());
            }
        }
    }

    private VarMode modeFromType(TypeExpr t, boolean isConsume) {
        if (isConsume) return VarMode.OWNED;
        TypeExpr resolved = resolveTypeAlias(t);
        if (resolved instanceof CapabilityTypeExpr cap) {
            return cap.mode() == CapabilityTypeExpr.CapabilityMode.READ ? VarMode.READ : VarMode.UPDATE;
        }
        return VarMode.OWNED;
    }

    private boolean isSecretType(TypeExpr t) {
        return t != null && typeName(t).equals("Secret");
    }

    private boolean isSecretExpr(Expr expr, CapEnv env) {
        if (expr == null) return false;
        return switch (expr) {
            case IdentExpr id -> env.isSecret(id.name());
            case FieldAccessExpr fa -> isSecretExpr(fa.object(), env)
                || isSecretType(resolveFieldType(inferExprType(fa.object(), env), fa.fieldName()));
            case CallExpr c -> {
                if (c.callee() instanceof FieldAccessExpr fa
                    && fa.object() instanceof IdentExpr obj
                    && obj.name().equals("Secret")
                    && (fa.fieldName().equals("of") || fa.fieldName().equals("map"))) {
                    yield true;
                }
                if (c.callee() instanceof IdentExpr id) {
                    FnDecl fn = fnDecls.get(id.name());
                    if (fn != null && isSecretType(fn.returnType())) {
                        yield true;
                    }
                }
                yield false;
            }
            case ParenExpr p -> isSecretExpr(p.inner(), env);
            case PropagateExpr p -> isSecretExpr(p.inner(), env);
            case IfExpr i -> isSecretExpr(i.thenBranch(), env) || isSecretExpr(i.elseBranch(), env);
            case BlockExpr b -> isSecretExpr(b.tailExpr(), env);
            case MatchExpr m -> m.arms().stream().anyMatch(a -> isSecretExpr(a.body(), env));
            default -> false;
        };
    }

    private TypeExpr inferExprType(Expr expr, CapEnv env) {
        if (expr == null) return null;
        return switch (expr) {
            case IdentExpr id -> resolveTypeAlias(env.types.get(id.name()));
            case ParenExpr p -> inferExprType(p.inner(), env);
            case FieldAccessExpr fa -> resolveFieldType(inferExprType(fa.object(), env), fa.fieldName());
            case CallExpr c -> {
                if (c.callee() instanceof IdentExpr id) {
                    FnDecl fn = fnDecls.get(id.name());
                    if (fn != null) yield resolveTypeAlias(fn.returnType());
                    if (typeDecls.containsKey(id.name())) yield new SimpleTypeExpr(id.name(), c.span());
                }
                yield null;
            }
            case ConstructorExpr c -> {
                String shortName = c.typePath().contains(".")
                    ? c.typePath().substring(c.typePath().lastIndexOf('.') + 1)
                    : c.typePath();
                yield new SimpleTypeExpr(shortName, c.span());
            }
            default -> null;
        };
    }

    private TypeExpr resolveFieldType(TypeExpr ownerType, String fieldName) {
        TypeExpr resolved = resolveTypeAlias(ownerType);
        if (resolved == null || fieldName == null) return null;
        if (resolved instanceof CapabilityTypeExpr c) return resolveFieldType(c.inner(), fieldName);
        if (resolved instanceof RefinedTypeExpr r) return resolveFieldType(r.baseType(), fieldName);
        String ownerName = typeName(resolved);
        TopLevelDecl decl = typeDecls.get(ownerName);
        if (decl instanceof RecordDecl r) {
            for (var p : r.params()) {
                if (p.name().equals(fieldName)) return resolveTypeAlias(p.type());
            }
        } else if (decl instanceof DataDecl d) {
            for (var f : d.fields()) {
                if (f.name().equals(fieldName)) return resolveTypeAlias(f.type());
            }
        }
        return null;
    }

    private boolean isLinearType(TypeExpr t) {
        if (t == null) return false;
        String n = typeName(t);
        return n.equals("Arena") || n.equals("OwnedBytes") || n.equals("Budget");
    }

    private boolean isLinearExpr(Expr expr, CapEnv env) {
        if (expr == null) return false;
        return switch (expr) {
            case IdentExpr id -> env.isLinear(id.name());
            case ParenExpr p -> isLinearExpr(p.inner(), env);
            case PropagateExpr p -> isLinearExpr(p.inner(), env);
            case IfExpr i -> isLinearExpr(i.thenBranch(), env) || isLinearExpr(i.elseBranch(), env);
            case BlockExpr b -> isLinearExpr(b.tailExpr(), env);
            case MatchExpr m -> m.arms().stream().anyMatch(a -> isLinearExpr(a.body(), env));
            default -> false;
        };
    }

    private boolean isViewType(TypeExpr t) {
        if (t == null) return false;
        String n = typeName(t);
        return n.equals("Slice") || n.equals("Bytes");
    }

    private void checkProtocolTransitionEffects(
        String protoName,
        String action,
        List<Expr> args,
        CapEnv env,
        SourceSpan span
    ) {
        ProtocolDecl proto = protocols.get(protoName);
        if (proto == null) return;
        String knownFromState = null;
        if (!args.isEmpty()) {
            Expr first = args.get(0);
            if (first instanceof ConsumeExpr ce) first = ce.target();
            if (first instanceof ParenExpr pe) first = pe.inner();
            if (first instanceof IdentExpr id) {
                TypeExpr t = env.types.get(id.name());
                if (t instanceof CapabilityTypeExpr cap) t = cap.inner();
                if (t instanceof GenericTypeExpr g && !g.typeArgs().isEmpty()
                    && g.typeArgs().get(0) instanceof SimpleTypeExpr st) {
                    knownFromState = st.name();
                }
            }
        }
        ProtocolDecl.Transition matched = null;
        for (var t : proto.transitions()) {
            if (!t.name().equals(action)) continue;
            if (knownFromState != null && t.fromState().equals(knownFromState)) {
                matched = t;
                break;
            }
            if (matched == null) {
                matched = t;
            }
        }
        if (matched != null) {
            for (var eff : matched.effects()) {
                requireEffect(eff, env, span);
            }
        }
    }

    private TypeExpr inferResourceType(Expr init, CapEnv env) {
        if (init == null) return null;
        if (init instanceof IdentExpr id) {
            return env.types.get(id.name());
        }
        if (init instanceof ConsumeExpr ce) {
            return inferResourceType(ce.target(), env);
        }
        if (init instanceof ParenExpr pe) {
            return inferResourceType(pe.inner(), env);
        }
        if (init instanceof CallExpr c && c.callee() instanceof FieldAccessExpr fa
            && fa.object() instanceof IdentExpr objId) {
            String target = objId.name();
            if (target.equals("Budget") && fa.fieldName().equals("mint")) {
                return new SimpleTypeExpr("Budget", c.span());
            }
            if (protocols.containsKey(target)) {
                ProtocolDecl proto = protocols.get(target);
                for (var t : proto.transitions()) {
                    if (t.name().equals(fa.fieldName())) {
                        return new GenericTypeExpr(target,
                            List.of(new SimpleTypeExpr(t.toState(), c.span())),
                            c.span());
                    }
                }
                return new SimpleTypeExpr(target, c.span());
            }
            TypeExpr objType = env.types.get(target);
            if (objType != null) {
                String objTypeName = typeName(objType);
                if (protocols.containsKey(objTypeName)) {
                    ProtocolDecl proto = protocols.get(objTypeName);
                    for (var t : proto.transitions()) {
                        if (t.name().equals(fa.fieldName())) {
                            return new GenericTypeExpr(objTypeName,
                                List.of(new SimpleTypeExpr(t.toState(), c.span())),
                                c.span());
                        }
                    }
                    return new SimpleTypeExpr(objTypeName, c.span());
                }
            }
        }
        return null;
    }

    private boolean isBudgetSplitCall(Expr init, CapEnv env) {
        if (init instanceof ParenExpr pe) return isBudgetSplitCall(pe.inner(), env);
        if (init instanceof CallExpr c && c.callee() instanceof FieldAccessExpr fa
            && fa.fieldName().equals("split")) {
            if (fa.object() instanceof IdentExpr objId) {
                return objId.name().equals("Budget") || isBudgetBinding(objId.name(), env);
            }
        }
        return false;
    }

    private boolean isBudgetBinding(String name, CapEnv env) {
        if (env.budgetLimits.containsKey(name)) return true;
        TypeExpr t = env.types.get(name);
        return t != null && typeName(t).equals("Budget");
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

    private String typeName(TypeExpr t) {
        TypeExpr resolved = resolveTypeAlias(t);
        if (resolved == null) return "";
        return switch (resolved) {
            case SimpleTypeExpr s -> s.name();
            case GenericTypeExpr g -> g.name();
            case CapabilityTypeExpr c -> typeName(c.inner());
            case RefinedTypeExpr r -> typeName(r.baseType());
            case RangeTypeExpr r -> typeName(r.baseType());
            default -> "";
        };
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

    private enum VarMode { READ, UPDATE, OWNED }
    private enum CapState { ALIVE, CONSUMED, MAYBE_CONSUMED }

    private static final class ForkRecord {
        final Map<String, VarMode> accesses = new LinkedHashMap<>();
    }

    private static final class ScopeContext {
        final int depth;
        final List<ForkRecord> forks = new ArrayList<>();
        ScopeContext(int depth) { this.depth = depth; }
        boolean hasUnjoinedForkUsing(String varName) {
            for (var f : forks) {
                if (f.accesses.containsKey(varName)) return true;
            }
            return false;
        }
    }

    private record AliasGraph(
        Set<String> directRoots,
        Map<Integer, AliasGraph> tupleElems,
        Map<String, AliasGraph> fieldElems
    ) {
        static final AliasGraph EMPTY = new AliasGraph(Set.of(), Map.of(), Map.of());

        boolean isEmpty() {
            return directRoots.isEmpty() && tupleElems.isEmpty() && fieldElems.isEmpty();
        }

        AliasGraph elementAt(int idx) {
            return tupleElems.getOrDefault(idx, EMPTY);
        }

        AliasGraph fieldAt(String field) {
            return fieldElems.getOrDefault(field, EMPTY);
        }

        AliasGraph filterOuter(Set<String> outerVars) {
            Set<String> filteredRoots = new LinkedHashSet<>();
            for (String r : directRoots) {
                if (outerVars.contains(r)) filteredRoots.add(r);
            }
            Map<Integer, AliasGraph> filteredTuple = new LinkedHashMap<>();
            for (var e : tupleElems.entrySet()) {
                AliasGraph sub = e.getValue().filterOuter(outerVars);
                if (!sub.isEmpty()) filteredTuple.put(e.getKey(), sub);
            }
            Map<String, AliasGraph> filteredFields = new LinkedHashMap<>();
            for (var e : fieldElems.entrySet()) {
                AliasGraph sub = e.getValue().filterOuter(outerVars);
                if (!sub.isEmpty()) filteredFields.put(e.getKey(), sub);
            }
            if (filteredRoots.isEmpty() && filteredTuple.isEmpty() && filteredFields.isEmpty()) {
                return EMPTY;
            }
            return new AliasGraph(filteredRoots, filteredTuple, filteredFields);
        }

        static AliasGraph merge(AliasGraph a, AliasGraph b) {
            if (a == null || a.isEmpty()) return b != null ? b : EMPTY;
            if (b == null || b.isEmpty()) return a;
            Set<String> roots = new LinkedHashSet<>(a.directRoots);
            roots.addAll(b.directRoots);
            Map<Integer, AliasGraph> tuple = new LinkedHashMap<>(a.tupleElems);
            for (var e : b.tupleElems.entrySet()) {
                tuple.merge(e.getKey(), e.getValue(), AliasGraph::merge);
            }
            Map<String, AliasGraph> fields = new LinkedHashMap<>(a.fieldElems);
            for (var e : b.fieldElems.entrySet()) {
                fields.merge(e.getKey(), e.getValue(), AliasGraph::merge);
            }
            return new AliasGraph(roots, tuple, fields);
        }
    }

    private static final class CapEnv {
        final Set<String> allowedEffects;
        final Set<String> captureBounds;
        final Map<String, TypeExpr> types = new LinkedHashMap<>();
        final Map<String, VarMode> modes = new LinkedHashMap<>();
        final Map<String, CapState> states = new LinkedHashMap<>();
        final Map<String, Integer> declDepths = new LinkedHashMap<>();
        final Set<String> linearVars = new LinkedHashSet<>();
        final Set<String> secretVars = new LinkedHashSet<>();
        final Set<String> consumeParams = new LinkedHashSet<>();
        final Map<String, String> aliasOf = new LinkedHashMap<>();
        final Map<String, Set<String>> aliasRoots = new LinkedHashMap<>();
        final Map<String, Set<String>> aliases = new LinkedHashMap<>();
        final Map<String, AliasGraph> aliasGraphs = new LinkedHashMap<>();
        final Map<String, String> viewOf = new LinkedHashMap<>();
        final Map<String, Set<String>> views = new LinkedHashMap<>();
        final Map<String, Long> budgetLimits = new LinkedHashMap<>();
        int depth = 0;
        int loopDepth = 0;
        final Deque<Integer> loopEntryDepths = new ArrayDeque<>();
        boolean inSecretMap = false;
        ScopeContext activeScope = null;
        ForkRecord currentFork = null;

        CapEnv(List<String> allowedEffects, List<String> captureBounds) {
            this.allowedEffects = new LinkedHashSet<>(allowedEffects != null ? allowedEffects : List.of());
            this.captureBounds = new LinkedHashSet<>(captureBounds != null ? captureBounds : List.of());
        }

        boolean isCapabilityOrBorrowed(String name) {
            VarMode m = modes.get(name);
            if (m == VarMode.READ || m == VarMode.UPDATE) return true;
            if (linearVars.contains(name) || viewOf.containsKey(name)) return true;
            TypeExpr t = types.get(name);
            return t instanceof CapabilityTypeExpr
                || (t instanceof GenericTypeExpr g && g.typeArgs().stream().anyMatch(a -> a instanceof CaptureSetTypeExpr));
        }

        void declare(String name, TypeExpr type, VarMode mode, boolean isLinear, boolean isSecret, int depth) {
            if (type != null) types.put(name, type);
            modes.put(name, mode);
            states.put(name, CapState.ALIVE);
            declDepths.put(name, depth);
            if (isLinear) linearVars.add(name);
            if (isSecret) secretVars.add(name);
        }

        boolean isSecret(String name) { return secretVars.contains(name); }
        boolean isLinear(String name) { return linearVars.contains(name); }

        boolean hasLiveViews(String owner) {
            Set<String> vs = views.get(owner);
            if (vs == null) return false;
            for (String v : vs) {
                if (states.get(v) == CapState.ALIVE) return true;
            }
            return false;
        }

        String resolveAlias(String name) {
            String cur = name;
            Set<String> seen = new LinkedHashSet<>();
            while (aliasOf.containsKey(cur) && seen.add(cur)) {
                cur = aliasOf.get(cur);
            }
            return cur;
        }

        Set<String> resolveAllRoots(String name) {
            Set<String> roots = new LinkedHashSet<>();
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> work = new ArrayDeque<>();
            work.add(name);
            while (!work.isEmpty()) {
                String cur = work.poll();
                if (!visited.add(cur)) continue;
                Set<String> parents = aliasRoots.get(cur);
                if (parents != null && !parents.isEmpty()) {
                    work.addAll(parents);
                } else if (aliasOf.containsKey(cur)) {
                    work.add(aliasOf.get(cur));
                } else {
                    roots.add(cur);
                }
            }
            if (roots.isEmpty()) {
                roots.add(name);
            }
            return roots;
        }

        void markConsumed(String name) {
            // Phase 1 (UP): find all possible root owners that `name` may alias
            Set<String> roots = resolveAllRoots(name);
            // Phase 2 (DOWN): invalidate `name`, its possible roots, and all aliases derived from them
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> worklist = new ArrayDeque<>();
            worklist.add(name);
            worklist.addAll(roots);
            while (!worklist.isEmpty()) {
                String cur = worklist.poll();
                if (!visited.add(cur)) continue;
                states.put(cur, CapState.CONSUMED);
                Set<String> al = aliases.get(cur);
                if (al != null) {
                    worklist.addAll(al);
                }
            }
        }

        Set<String> declaredVarsSnapshot() {
            return new LinkedHashSet<>(states.keySet());
        }

        CapEnv copy() {
            CapEnv c = new CapEnv(List.copyOf(allowedEffects), List.copyOf(captureBounds));
            c.types.putAll(types);
            c.modes.putAll(modes);
            c.states.putAll(states);
            c.declDepths.putAll(declDepths);
            c.linearVars.addAll(linearVars);
            c.secretVars.addAll(secretVars);
            c.aliasOf.putAll(aliasOf);
            for (var e : aliasRoots.entrySet()) c.aliasRoots.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            for (var e : aliases.entrySet()) c.aliases.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            c.aliasGraphs.putAll(aliasGraphs);
            c.viewOf.putAll(viewOf);
            for (var e : views.entrySet()) c.views.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
            c.budgetLimits.putAll(budgetLimits);
            c.depth = depth;
            c.loopDepth = loopDepth;
            c.loopEntryDepths.addAll(loopEntryDepths);
            c.inSecretMap = inSecretMap;
            c.activeScope = activeScope;
            c.currentFork = currentFork;
            return c;
        }

        private void mergeBranchRelationsFrom(CapEnv branch) {
            Set<String> outerVars = states.keySet();
            for (var key : outerVars) {
                Set<String> bRoots = branch.aliasRoots.get(key);
                if (bRoots != null) {
                    for (String r : bRoots) {
                        if (outerVars.contains(r)) {
                            aliasOf.putIfAbsent(key, r);
                            aliasRoots.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(r);
                            aliases.computeIfAbsent(r, k -> new LinkedHashSet<>()).add(key);
                        }
                    }
                }
                String bParent = branch.aliasOf.get(key);
                if (bParent != null && outerVars.contains(bParent)) {
                    aliasOf.putIfAbsent(key, bParent);
                    aliasRoots.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(bParent);
                    aliases.computeIfAbsent(bParent, k -> new LinkedHashSet<>()).add(key);
                }
                Set<String> bAliases = branch.aliases.get(key);
                if (bAliases != null) {
                    for (String al : bAliases) {
                        if (outerVars.contains(al)) {
                            aliasOf.putIfAbsent(al, key);
                            aliasRoots.computeIfAbsent(al, k -> new LinkedHashSet<>()).add(key);
                            aliases.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(al);
                        }
                    }
                }
                AliasGraph bGraph = branch.aliasGraphs.get(key);
                if (bGraph != null && !bGraph.isEmpty()) {
                    AliasGraph filtered = bGraph.filterOuter(outerVars);
                    if (!filtered.isEmpty()) {
                        aliasGraphs.merge(key, filtered, AliasGraph::merge);
                    }
                }
                String bViewOwner = branch.viewOf.get(key);
                if (bViewOwner != null && outerVars.contains(bViewOwner)) {
                    viewOf.putIfAbsent(key, bViewOwner);
                    views.computeIfAbsent(bViewOwner, k -> new LinkedHashSet<>()).add(key);
                }
                if (branch.linearVars.contains(key)) {
                    linearVars.add(key);
                }
                if (branch.secretVars.contains(key)) {
                    secretVars.add(key);
                }
                if (branch.modes.get(key) == VarMode.READ) {
                    modes.put(key, VarMode.READ);
                }
            }
        }

        void copyStatesFrom(CapEnv other) {
            for (var key : states.keySet()) {
                if (other.states.containsKey(key)) {
                    states.put(key, other.states.get(key));
                }
            }
            mergeBranchRelationsFrom(other);
        }

        void mergeStates(CapEnv a, CapEnv b) {
            for (var key : states.keySet()) {
                CapState sa = a.states.getOrDefault(key, CapState.ALIVE);
                CapState sb = b.states.getOrDefault(key, CapState.ALIVE);
                if (sa == sb) {
                    states.put(key, sa);
                } else {
                    states.put(key, CapState.MAYBE_CONSUMED);
                }
            }
            mergeBranchRelationsFrom(a);
            mergeBranchRelationsFrom(b);
        }
    }
}
