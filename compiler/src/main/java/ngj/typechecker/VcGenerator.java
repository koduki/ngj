package ngj.typechecker;

import ngj.ast.*;
import ngj.verifier.SmtExpr;
import ngj.verifier.SmtExpr.Sort;

import java.math.BigInteger;
import java.util.*;

/**
 * Translates NGJ contracts, invariants, lemmas, and pure expressions into
 * {@link SmtExpr} verification conditions for Tier 2 SMT solving.
 */
public final class VcGenerator {
    private final Map<String, SpecFnDecl> specFns;
    private int alphaCounter = 0;

    public VcGenerator(Map<String, SpecFnDecl> specFns) {
        this.specFns = specFns != null ? specFns : Map.of();
    }

    private String freshAlphaName(String base) {
        return "__alpha$" + (++alphaCounter) + "$" + base;
    }

    public SmtExpr translateExpr(Expr expr, Map<String, Sort> varSorts) {
        return translateExpr(expr, varSorts, Map.of(), new LinkedHashSet<>(), false, Set.of());
    }

    public SmtExpr translateExpr(
        Expr expr,
        Map<String, Sort> varSorts,
        Map<String, String> ssaEnv
    ) {
        return translateExpr(expr, varSorts, ssaEnv, new LinkedHashSet<>(), false, Set.of());
    }

    public SmtExpr translateExecutableExpr(
        Expr expr,
        Map<String, Sort> varSorts,
        Map<String, String> ssaEnv,
        Set<String> longVars
    ) {
        return translateExpr(expr, varSorts, ssaEnv, new LinkedHashSet<>(), true, longVars != null ? longVars : Set.of());
    }

    private SmtExpr translateExpr(
        Expr expr,
        Map<String, Sort> varSorts,
        Map<String, String> ssaEnv,
        Set<String> inliningSpecFns,
        boolean executableIntOverflow,
        Set<String> longVars
    ) {
        if (expr == null) {
            return new SmtExpr.UnsupportedExpr("null expression");
        }
        return switch (expr) {
            case LiteralExpr lit -> switch (lit.kind()) {
                case INT, LONG -> SmtExpr.intConst(((Number) lit.value()).longValue());
                case BOOL -> SmtExpr.boolConst((Boolean) lit.value());
                default -> new SmtExpr.UnsupportedExpr("Unsupported literal: " + lit.kind());
            };
            case IdentExpr id -> {
                String ssaName = ssaEnv.getOrDefault(id.name(), id.name());
                Sort sort = varSorts.getOrDefault(ssaName, varSorts.getOrDefault(id.name(), Sort.INT));
                yield new SmtExpr.Var(ssaName, sort);
            }
            case ParenExpr p -> translateExpr(p.inner(), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
            case UnaryExpr u -> {
                SmtExpr op = translateExpr(u.operand(), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
                yield switch (u.op()) {
                    case NEG -> {
                        if (executableIntOverflow) {
                            long minVal = isLongExpr(u.operand(), longVars) ? Long.MIN_VALUE : Integer.MIN_VALUE;
                            yield new SmtExpr.Ite(
                                new SmtExpr.Eq(op, SmtExpr.intConst(minVal)),
                                SmtExpr.intConst(minVal),
                                new SmtExpr.Sub(SmtExpr.intConst(0), op)
                            );
                        }
                        yield new SmtExpr.Sub(SmtExpr.intConst(0), op);
                    }
                    case NOT -> new SmtExpr.Not(op);
                };
            }
            case BinaryExpr b -> {
                SmtExpr l = translateExpr(b.left(), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
                SmtExpr r = translateExpr(b.right(), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
                yield switch (b.op()) {
                    case ADD -> new SmtExpr.Add(l, r);
                    case SUB -> new SmtExpr.Sub(l, r);
                    case MUL -> new SmtExpr.Mul(l, r);
                    case DIV, MOD -> new SmtExpr.UfApp("op_" + b.op().name(), List.of(l, r), Sort.INT);
                    case EQ -> new SmtExpr.Eq(l, r);
                    case NE -> new SmtExpr.Not(new SmtExpr.Eq(l, r));
                    case LT -> new SmtExpr.Lt(l, r);
                    case LE -> new SmtExpr.Le(l, r);
                    case GT -> new SmtExpr.Gt(l, r);
                    case GE -> new SmtExpr.Ge(l, r);
                    case AND -> SmtExpr.and(l, r);
                    case OR -> SmtExpr.or(l, r);
                };
            }
            case IfExpr ifExpr -> {
                if (ifExpr.elseBranch() == null) {
                    yield new SmtExpr.UnsupportedExpr("if without else in VC");
                }
                SmtExpr cond = translateExpr(ifExpr.condition(), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
                SmtExpr thenSmt = translateBranchExpr(ifExpr.thenBranch(), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
                SmtExpr elseSmt = translateBranchExpr(ifExpr.elseBranch(), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
                if (thenSmt instanceof SmtExpr.UnsupportedExpr || elseSmt instanceof SmtExpr.UnsupportedExpr) {
                    yield new SmtExpr.UnsupportedExpr("Non-expression if branch in VC");
                }
                yield new SmtExpr.Ite(cond, thenSmt, elseSmt);
            }
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr id && specFns.containsKey(id.name())) {
                    SpecFnDecl spec = specFns.get(id.name());
                    Sort retSort = sortOfType(spec.returnType());
                    if (!inliningSpecFns.contains(id.name())
                        && spec.body() != null
                        && spec.params().size() == call.args().size()) {
                        Map<String, Expr> subst = new LinkedHashMap<>();
                        for (int i = 0; i < spec.params().size(); i++) {
                            subst.put(spec.params().get(i).name(), call.args().get(i));
                        }
                        inliningSpecFns.add(id.name());
                        try {
                            BlockExpr substitutedBody = substituteBlock(spec.body(), subst);
                            SmtExpr inlined = translateBranchExpr(substitutedBody, varSorts, ssaEnv, inliningSpecFns, false, longVars);
                            if (!(inlined instanceof SmtExpr.UnsupportedExpr)) {
                                yield inlined;
                            }
                        } finally {
                            inliningSpecFns.remove(id.name());
                        }
                    }
                    List<SmtExpr> args = call.args().stream()
                        .map(a -> translateExpr(a, varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars)).toList();
                    yield new SmtExpr.UfApp(id.name(), args, retSort);
                }
                List<SmtExpr> args = call.args().stream()
                    .map(a -> translateExpr(a, varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars)).toList();
                String fnName = call.callee() instanceof IdentExpr id ? id.name() : call.callee().toString();
                yield new SmtExpr.UfApp(fnName, args, Sort.INT);
            }
            case FieldAccessExpr fa -> new SmtExpr.UfApp(
                "field_" + fa.fieldName(),
                List.of(translateExpr(fa.object(), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars)),
                Sort.INT
            );
            case HoleExpr h -> new SmtExpr.UnsupportedExpr("Hole present: ?" + h.name());
            default -> new SmtExpr.UnsupportedExpr("Unsupported AST node in VC: " + expr.getClass().getSimpleName());
        };
    }

    private Expr extractBranchReturnOrTail(BlockExpr block) {
        if (block == null) return null;
        if (block.tailExpr() != null) return block.tailExpr();
        for (var st : block.statements()) {
            if (st instanceof ReturnStmt r && r.value() != null) return r.value();
        }
        return null;
    }

    public SmtExpr buildFnPostconditionVc(FnDecl fn, List<SmtExpr> requires, SmtExpr ensures) {
        Map<String, Sort> sorts = collectSorts(fn.params(), fn.returnType());
        List<SmtExpr> premises = new ArrayList<>(requires);
        Set<String> longVars = collectLongVars(fn.params());

        if (fn.body() != null) {
            if (containsNeg(fn.body())) {
                for (var p : fn.params()) {
                    if (sortOfType(p.type()) == Sort.INT) {
                        SmtExpr.Var pVar = new SmtExpr.Var(p.name(), Sort.INT);
                        if (isLongType(p.type())) {
                            premises.add(new SmtExpr.Ge(pVar, SmtExpr.intConst(Long.MIN_VALUE)));
                            premises.add(new SmtExpr.Le(pVar, SmtExpr.intConst(Long.MAX_VALUE)));
                        } else {
                            premises.add(new SmtExpr.Ge(pVar, SmtExpr.intConst(Integer.MIN_VALUE)));
                            premises.add(new SmtExpr.Le(pVar, SmtExpr.intConst(Integer.MAX_VALUE)));
                        }
                    }
                }
            }
            extractBodyFacts(fn.body(), sorts, premises, longVars);
        }

        if (premises.isEmpty()) {
            return ensures;
        }
        SmtExpr conj = premises.size() == 1 ? premises.get(0) : new SmtExpr.And(premises);
        return new SmtExpr.Implies(conj, ensures);
    }

    public SmtExpr buildLemmaVc(LemmaDecl lemma, List<SmtExpr> requires, SmtExpr ensures) {
        if (requires.isEmpty()) {
            return ensures;
        }
        SmtExpr conj = requires.size() == 1 ? requires.get(0) : new SmtExpr.And(requires);
        return new SmtExpr.Implies(conj, ensures);
    }

    private void extractBodyFacts(BlockExpr body, Map<String, Sort> sorts, List<SmtExpr> premises, Set<String> longVars) {
        Sort resSort = sorts.getOrDefault("result", Sort.INT);
        Map<String, String> ssaEnv = new LinkedHashMap<>();
        Map<String, Integer> ssaCounter = new LinkedHashMap<>();
        SmtExpr bodyResult = translateBlockResult(body.statements(), 0, body.tailExpr(), sorts, premises, ssaEnv, ssaCounter, longVars);
        if (bodyResult != null && !(bodyResult instanceof SmtExpr.UnsupportedExpr)) {
            premises.add(new SmtExpr.Eq(new SmtExpr.Var("result", resSort), bodyResult));
        }
    }

    private String freshSsa(
        String name,
        Sort sort,
        Map<String, Sort> sorts,
        Map<String, String> ssaEnv,
        Map<String, Integer> ssaCounter
    ) {
        int n = ssaCounter.merge(name, 1, Integer::sum);
        String ssaName = name + "$ssa" + n;
        sorts.put(ssaName, sort);
        ssaEnv.put(name, ssaName);
        return ssaName;
    }

    private SmtExpr translateBlockResult(
        List<Statement> stmts,
        int idx,
        Expr tailExpr,
        Map<String, Sort> sorts,
        List<SmtExpr> premises,
        Map<String, String> ssaEnv,
        Map<String, Integer> ssaCounter,
        Set<String> longVars
    ) {
        if (idx >= stmts.size()) {
            return tailExpr != null ? translateExpr(tailExpr, sorts, ssaEnv, new LinkedHashSet<>(), true, longVars) : null;
        }
        Statement stmt = stmts.get(idx);
        switch (stmt) {
            case ValStmt v -> {
                SmtExpr init = translateExpr(v.initializer(), sorts, ssaEnv, new LinkedHashSet<>(), true, longVars);
                Sort sort = sortOfType(v.explicitType());
                String lhs = (ssaEnv.containsKey(v.name()) || sorts.containsKey(v.name()))
                    ? freshSsa(v.name(), sort, sorts, ssaEnv, ssaCounter)
                    : v.name();
                sorts.put(lhs, sort);
                ssaEnv.put(v.name(), lhs);
                if (isLongType(v.explicitType())) {
                    longVars.add(lhs);
                    longVars.add(v.name());
                }
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), init));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter, longVars);
            }
            case LetStmt l -> {
                SmtExpr init = translateExpr(l.initializer(), sorts, ssaEnv, new LinkedHashSet<>(), true, longVars);
                Sort sort = sortOfType(l.explicitType());
                String lhs = (ssaEnv.containsKey(l.name()) || sorts.containsKey(l.name()))
                    ? freshSsa(l.name(), sort, sorts, ssaEnv, ssaCounter)
                    : l.name();
                sorts.put(lhs, sort);
                ssaEnv.put(l.name(), lhs);
                if (isLongType(l.explicitType())) {
                    longVars.add(lhs);
                    longVars.add(l.name());
                }
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), init));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter, longVars);
            }
            case VarStmt v -> {
                SmtExpr init = translateExpr(v.initializer(), sorts, ssaEnv, new LinkedHashSet<>(), true, longVars);
                Sort sort = sortOfType(v.explicitType());
                String lhs = freshSsa(v.name(), sort, sorts, ssaEnv, ssaCounter);
                if (isLongType(v.explicitType())) {
                    longVars.add(lhs);
                    longVars.add(v.name());
                }
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), init));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter, longVars);
            }
            case AssignStmt a -> {
                SmtExpr rhs = translateExpr(a.value(), sorts, ssaEnv, new LinkedHashSet<>(), true, longVars);
                String prevName = ssaEnv.getOrDefault(a.name(), a.name());
                Sort sort = sorts.getOrDefault(prevName, Sort.INT);
                String lhs = freshSsa(a.name(), sort, sorts, ssaEnv, ssaCounter);
                if (longVars.contains(prevName) || longVars.contains(a.name())) {
                    longVars.add(lhs);
                }
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), rhs));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter, longVars);
            }
            case AssumeStmt a -> {
                premises.add(translateExpr(a.condition(), sorts, ssaEnv, new LinkedHashSet<>(), false, longVars));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter, longVars);
            }
            case TupleDestructureStmt td -> {
                if (containsMutationOrWhileOrReturn(td.initializer())) {
                    return new SmtExpr.UnsupportedExpr("tuple_destructure_mutated_state");
                }
                Expr init = td.initializer();
                while (init instanceof ParenExpr p) init = p.inner();
                TupleExpr te = init instanceof TupleExpr t ? t : null;
                List<SmtExpr> rhsList = new ArrayList<>(td.names().size());
                for (int i = 0; i < td.names().size(); i++) {
                    if (te != null && i < te.elements().size()) {
                        rhsList.add(translateExpr(te.elements().get(i), sorts, ssaEnv, new LinkedHashSet<>(), true, longVars));
                    } else {
                        rhsList.add(null);
                    }
                }
                for (int i = 0; i < td.names().size(); i++) {
                    String name = td.names().get(i);
                    TypeExpr explicitType = i < td.explicitTypes().size() ? td.explicitTypes().get(i) : null;
                    Sort sort = sortOfType(explicitType);
                    String lhs = freshSsa(name, sort, sorts, ssaEnv, ssaCounter);
                    if (isLongType(explicitType)) {
                        longVars.add(lhs);
                        longVars.add(name);
                    }
                    SmtExpr rhs = rhsList.get(i);
                    if (rhs != null && !containsUnsupported(rhs)) {
                        premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), rhs));
                    }
                }
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter, longVars);
            }
            case ReturnStmt r -> {
                return r.value() != null ? translateExpr(r.value(), sorts, ssaEnv, new LinkedHashSet<>(), true, longVars) : null;
            }
            case ExprStmt es -> {
                if (es.expr() instanceof WhileExpr) {
                    return new SmtExpr.UnsupportedExpr("while_mutated_state");
                }
                if (es.expr() instanceof IfExpr ifExpr) {
                    SmtExpr cond = translateExpr(ifExpr.condition(), sorts, ssaEnv, new LinkedHashSet<>(), true, longVars);
                    List<SmtExpr> thenPremises = new ArrayList<>();
                    SmtExpr thenRes = translateBlockResult(
                        ifExpr.thenBranch().statements(), 0, ifExpr.thenBranch().tailExpr(),
                        sorts, thenPremises, new LinkedHashMap<>(ssaEnv), ssaCounter, longVars);
                    if (thenRes instanceof SmtExpr.UnsupportedExpr) {
                        return thenRes;
                    }
                    SmtExpr elseRes = ifExpr.elseBranch() != null
                        ? (ifExpr.elseBranch() instanceof BlockExpr eb
                            ? translateBlockResult(eb.statements(), 0, eb.tailExpr(),
                                sorts, new ArrayList<>(), new LinkedHashMap<>(ssaEnv), ssaCounter, longVars)
                            : translateExpr(ifExpr.elseBranch(), sorts, ssaEnv, new LinkedHashSet<>(), true, longVars))
                        : translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, new LinkedHashMap<>(ssaEnv), ssaCounter, longVars);
                    if (elseRes instanceof SmtExpr.UnsupportedExpr) {
                        return elseRes;
                    }
                    if (thenRes != null && elseRes != null && !(cond instanceof SmtExpr.UnsupportedExpr)) {
                        return new SmtExpr.Ite(cond, thenRes, elseRes);
                    }
                    if (thenRes != null && ifExpr.elseBranch() == null) {
                        return new SmtExpr.UnsupportedExpr("partial_return_if");
                    }
                    if (containsMutationOrWhileOrReturn(ifExpr)) {
                        return new SmtExpr.UnsupportedExpr("if_mutated_state");
                    }
                } else if (es.expr() instanceof BlockExpr nestedBlock) {
                    if (containsMutationOrWhile(nestedBlock)) {
                        return new SmtExpr.UnsupportedExpr("block_mutated_state");
                    }
                    List<SmtExpr> blockPremises = new ArrayList<>();
                    SmtExpr blockRes = translateBlockResult(
                        nestedBlock.statements(), 0, null,
                        sorts, blockPremises, new LinkedHashMap<>(ssaEnv), ssaCounter, longVars);
                    if (blockRes instanceof SmtExpr.UnsupportedExpr) {
                        return blockRes;
                    }
                    if (blockRes != null) {
                        premises.addAll(blockPremises);
                        return blockRes;
                    }
                    if (containsReturn(nestedBlock)) {
                        return new SmtExpr.UnsupportedExpr("block_partial_return");
                    }
                } else if (containsMutationOrWhileOrReturn(es.expr())) {
                    return new SmtExpr.UnsupportedExpr("expr_mutated_or_return_state");
                }
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter, longVars);
            }
            default -> {
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter, longVars);
            }
        }
    }

    private boolean containsMutationOrWhileOrReturn(Expr expr) {
        return containsMutationOrWhile(expr) || containsReturn(expr);
    }

    private boolean containsReturn(Expr expr) {
        if (expr == null) return false;
        return switch (expr) {
            case BlockExpr b -> {
                for (var st : b.statements()) {
                    if (st instanceof ReturnStmt) yield true;
                    if (st instanceof ExprStmt es && containsReturn(es.expr())) yield true;
                    if (st instanceof ValStmt v && containsReturn(v.initializer())) yield true;
                    if (st instanceof LetStmt l && containsReturn(l.initializer())) yield true;
                    if (st instanceof VarStmt v && containsReturn(v.initializer())) yield true;
                    if (st instanceof AssignStmt a && containsReturn(a.value())) yield true;
                }
                yield containsReturn(b.tailExpr());
            }
            case IfExpr i -> containsReturn(i.condition()) || containsReturn(i.thenBranch()) || containsReturn(i.elseBranch());
            case WhileExpr w -> containsReturn(w.condition()) || containsReturn(w.body());
            case ForExpr f -> containsReturn(f.iterable()) || containsReturn(f.body());
            case MatchExpr m -> {
                if (containsReturn(m.scrutinee())) yield true;
                for (var arm : m.arms()) {
                    if (containsReturn(arm.body())) yield true;
                }
                yield false;
            }
            case ScopeExpr s -> containsReturn(s.body());
            case ForkExpr f -> containsReturn(f.body());
            case ParenExpr p -> containsReturn(p.inner());
            default -> false;
        };
    }

    private boolean containsMutationOrWhile(Expr expr) {
        if (expr == null) return false;
        return switch (expr) {
            case WhileExpr _ -> true;
            case ForExpr f -> containsMutationOrWhile(f.iterable()) || containsMutationOrWhile(f.body());
            case BlockExpr b -> {
                for (var st : b.statements()) {
                    if (st instanceof AssignStmt) yield true;
                    if (st instanceof ExprStmt es && containsMutationOrWhile(es.expr())) yield true;
                    if (st instanceof ValStmt v && containsMutationOrWhile(v.initializer())) yield true;
                    if (st instanceof LetStmt l && containsMutationOrWhile(l.initializer())) yield true;
                    if (st instanceof VarStmt v && containsMutationOrWhile(v.initializer())) yield true;
                    if (st instanceof ReturnStmt r && containsMutationOrWhile(r.value())) yield true;
                }
                yield containsMutationOrWhile(b.tailExpr());
            }
            case IfExpr i -> containsMutationOrWhile(i.condition()) || containsMutationOrWhile(i.thenBranch()) || containsMutationOrWhile(i.elseBranch());
            case MatchExpr m -> {
                if (containsMutationOrWhile(m.scrutinee())) yield true;
                for (var arm : m.arms()) {
                    if (containsMutationOrWhile(arm.body())) yield true;
                }
                yield false;
            }
            case ScopeExpr s -> containsMutationOrWhile(s.body());
            case ForkExpr f -> containsMutationOrWhile(f.body());
            case ParenExpr p -> containsMutationOrWhile(p.inner());
            default -> false;
        };
    }

    public Map<String, Sort> collectSorts(List<Param> params, TypeExpr returnType) {
        Map<String, Sort> sorts = new LinkedHashMap<>();
        for (var p : params) {
            sorts.put(p.name(), sortOfType(p.type()));
        }
        if (returnType != null) {
            sorts.put("result", sortOfType(returnType));
        }
        return sorts;
    }

    public static Set<String> collectLongVars(List<Param> params) {
        Set<String> longVars = new LinkedHashSet<>();
        for (var p : params) {
            if (isLongType(p.type())) {
                longVars.add(p.name());
            }
        }
        return longVars;
    }

    public static boolean isLongType(TypeExpr type) {
        if (type == null) return false;
        return switch (type) {
            case SimpleTypeExpr s -> s.name().equals("Long");
            case RefinedTypeExpr r -> isLongType(r.baseType());
            case RangeTypeExpr r -> isLongType(r.baseType());
            case CapabilityTypeExpr c -> isLongType(c.inner());
            default -> false;
        };
    }

    private static boolean isLongExpr(Expr expr, Set<String> longVars) {
        if (expr == null) return false;
        return switch (expr) {
            case LiteralExpr lit -> lit.kind() == LiteralExpr.LiteralKind.LONG;
            case IdentExpr id -> longVars.contains(id.name());
            case ParenExpr p -> isLongExpr(p.inner(), longVars);
            case UnaryExpr u -> u.op() == UnaryExpr.UnaryOp.NEG && isLongExpr(u.operand(), longVars);
            case BinaryExpr b -> isLongExpr(b.left(), longVars) || isLongExpr(b.right(), longVars);
            default -> false;
        };
    }

    private static boolean containsNeg(Expr expr) {
        if (expr == null) return false;
        return switch (expr) {
            case UnaryExpr u -> u.op() == UnaryExpr.UnaryOp.NEG || containsNeg(u.operand());
            case BinaryExpr b -> containsNeg(b.left()) || containsNeg(b.right());
            case ParenExpr p -> containsNeg(p.inner());
            case IfExpr i -> containsNeg(i.condition()) || containsNegBlock(i.thenBranch()) || containsNeg(i.elseBranch());
            case BlockExpr b -> containsNegBlock(b);
            case CallExpr c -> {
                for (var a : c.args()) {
                    if (containsNeg(a)) yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    private static boolean containsNegBlock(BlockExpr block) {
        if (block == null) return false;
        for (var st : block.statements()) {
            boolean found = switch (st) {
                case ValStmt v -> containsNeg(v.initializer());
                case LetStmt l -> containsNeg(l.initializer());
                case VarStmt v -> containsNeg(v.initializer());
                case AssignStmt a -> containsNeg(a.value());
                case ReturnStmt r -> containsNeg(r.value());
                case ExprStmt es -> containsNeg(es.expr());
                default -> false;
            };
            if (found) return true;
        }
        return containsNeg(block.tailExpr());
    }

    public static boolean containsUnsupported(SmtExpr expr) {
        if (expr == null) return true;
        return switch (expr) {
            case SmtExpr.UnsupportedExpr _ -> true;
            case SmtExpr.IntConst _, SmtExpr.BoolConst _, SmtExpr.Var _ -> false;
            case SmtExpr.Add a -> containsUnsupported(a.left()) || containsUnsupported(a.right());
            case SmtExpr.Sub s -> containsUnsupported(s.left()) || containsUnsupported(s.right());
            case SmtExpr.Mul m -> containsUnsupported(m.left()) || containsUnsupported(m.right());
            case SmtExpr.Eq e -> containsUnsupported(e.left()) || containsUnsupported(e.right());
            case SmtExpr.Lt l -> containsUnsupported(l.left()) || containsUnsupported(l.right());
            case SmtExpr.Le l -> containsUnsupported(l.left()) || containsUnsupported(l.right());
            case SmtExpr.Gt g -> containsUnsupported(g.left()) || containsUnsupported(g.right());
            case SmtExpr.Ge g -> containsUnsupported(g.left()) || containsUnsupported(g.right());
            case SmtExpr.Not n -> containsUnsupported(n.inner());
            case SmtExpr.Implies i -> containsUnsupported(i.premise()) || containsUnsupported(i.conclusion());
            case SmtExpr.Ite i -> containsUnsupported(i.cond()) || containsUnsupported(i.thenExpr()) || containsUnsupported(i.elseExpr());
            case SmtExpr.And a -> a.conjuncts().stream().anyMatch(VcGenerator::containsUnsupported);
            case SmtExpr.Or o -> o.disjuncts().stream().anyMatch(VcGenerator::containsUnsupported);
            case SmtExpr.UfApp u -> u.args().stream().anyMatch(VcGenerator::containsUnsupported);
        };
    }

    public static Sort sortOfType(TypeExpr type) {
        if (type == null) return Sort.INT;
        return switch (type) {
            case SimpleTypeExpr s -> s.name().equals("Bool") ? Sort.BOOL : Sort.INT;
            case RefinedTypeExpr r -> sortOfType(r.baseType());
            case RangeTypeExpr r -> sortOfType(r.baseType());
            case CapabilityTypeExpr c -> sortOfType(c.inner());
            default -> Sort.INT;
        };
    }

    private Expr substituteExpr(Expr expr, Map<String, Expr> subst) {
        if (expr == null || subst.isEmpty()) return expr;
        return switch (expr) {
            case IdentExpr id -> subst.getOrDefault(id.name(), id);
            case BinaryExpr b -> new BinaryExpr(substituteExpr(b.left(), subst), b.op(), substituteExpr(b.right(), subst), b.span());
            case UnaryExpr u -> new UnaryExpr(u.op(), substituteExpr(u.operand(), subst), u.span());
            case ParenExpr p -> new ParenExpr(substituteExpr(p.inner(), subst), p.span());
            case IfExpr i -> new IfExpr(
                substituteExpr(i.condition(), subst),
                substituteBlock(i.thenBranch(), subst),
                substituteExpr(i.elseBranch(), subst),
                i.span()
            );
            case BlockExpr b -> substituteBlock(b, subst);
            case CallExpr c -> new CallExpr(
                c.callee() instanceof IdentExpr id && !subst.containsKey(id.name())
                    ? id
                    : substituteExpr(c.callee(), subst),
                c.typeArgs(),
                c.args().stream().map(a -> substituteExpr(a, subst)).toList(),
                c.span()
            );
            case FieldAccessExpr fa -> new FieldAccessExpr(substituteExpr(fa.object(), subst), fa.fieldName(), fa.span());
            case PropagateExpr p -> new PropagateExpr(substituteExpr(p.inner(), subst), p.span());
            case ConsumeExpr c -> new ConsumeExpr(substituteExpr(c.target(), subst), c.span());
            case TupleExpr t -> new TupleExpr(
                t.elements().stream().map(e -> substituteExpr(e, subst)).toList(),
                t.span()
            );
            case ConstructorExpr c -> new ConstructorExpr(
                c.typePath(),
                c.fields().stream()
                    .map(f -> new ConstructorExpr.FieldInit(f.name(), substituteExpr(f.value(), subst), f.span()))
                    .toList(),
                c.span()
            );
            case MatchExpr m -> new MatchExpr(
                substituteExpr(m.scrutinee(), subst),
                m.arms().stream().map(arm -> {
                    Map<String, Expr> armSubst = new LinkedHashMap<>(subst);
                    Pattern renamedPattern = alphaRenamePattern(arm.pattern(), armSubst);
                    return new MatchExpr.MatchArm(renamedPattern, substituteExpr(arm.body(), armSubst), arm.span());
                }).toList(),
                m.span()
            );
            case ScopeExpr s -> new ScopeExpr(substituteBlock(s.body(), subst), s.span());
            case ForkExpr f -> new ForkExpr(substituteBlock(f.body(), subst), f.span());
            default -> expr;
        };
    }

    private BlockExpr substituteBlock(BlockExpr block, Map<String, Expr> subst) {
        if (block == null) return null;
        Map<String, Expr> curSubst = new LinkedHashMap<>(subst);
        Map<String, String> renamedVars = new LinkedHashMap<>();
        List<Statement> newStmts = new ArrayList<>(block.statements().size());
        for (var st : block.statements()) {
            switch (st) {
                case ValStmt v -> {
                    Expr newInit = substituteExpr(v.initializer(), curSubst);
                    String fresh = freshAlphaName(v.name());
                    curSubst.put(v.name(), new IdentExpr(fresh, v.span()));
                    renamedVars.put(v.name(), fresh);
                    newStmts.add(new ValStmt(fresh, v.explicitType(), newInit, v.span()));
                }
                case LetStmt l -> {
                    Expr newInit = substituteExpr(l.initializer(), curSubst);
                    String fresh = freshAlphaName(l.name());
                    curSubst.put(l.name(), new IdentExpr(fresh, l.span()));
                    renamedVars.put(l.name(), fresh);
                    newStmts.add(new LetStmt(fresh, l.explicitType(), newInit, l.span()));
                }
                case VarStmt v -> {
                    Expr newInit = substituteExpr(v.initializer(), curSubst);
                    String fresh = freshAlphaName(v.name());
                    curSubst.put(v.name(), new IdentExpr(fresh, v.span()));
                    renamedVars.put(v.name(), fresh);
                    newStmts.add(new VarStmt(fresh, v.explicitType(), newInit, v.span()));
                }
                case AssignStmt a -> newStmts.add(new AssignStmt(
                    renamedVars.getOrDefault(a.name(), a.name()),
                    substituteExpr(a.value(), curSubst),
                    a.span()
                ));
                case ReturnStmt r -> newStmts.add(new ReturnStmt(substituteExpr(r.value(), curSubst), r.span()));
                case ExprStmt es -> newStmts.add(new ExprStmt(substituteExpr(es.expr(), curSubst), es.span()));
                case TupleDestructureStmt td -> {
                    Expr newInit = substituteExpr(td.initializer(), curSubst);
                    List<String> freshNames = new ArrayList<>(td.names().size());
                    for (String n : td.names()) {
                        String fresh = freshAlphaName(n);
                        curSubst.put(n, new IdentExpr(fresh, td.span()));
                        renamedVars.put(n, fresh);
                        freshNames.add(fresh);
                    }
                    newStmts.add(new TupleDestructureStmt(freshNames, td.explicitTypes(), newInit, td.span()));
                }
                case InvariantStmt inv -> newStmts.add(new InvariantStmt(substituteExpr(inv.condition(), curSubst), inv.span()));
                case AssumeStmt a -> newStmts.add(new AssumeStmt(substituteExpr(a.condition(), curSubst), a.span()));
                case ProofStmt p -> newStmts.add(new ProofStmt(substituteBlock(p.body(), curSubst), p.span()));
            }
        }
        Expr newTail = substituteExpr(block.tailExpr(), curSubst);
        return new BlockExpr(newStmts, newTail, block.span());
    }

    private Pattern alphaRenamePattern(Pattern pattern, Map<String, Expr> subst) {
        if (pattern == null) return null;
        return switch (pattern) {
            case BindingPattern bp -> {
                String fresh = freshAlphaName(bp.name());
                subst.put(bp.name(), new IdentExpr(fresh, bp.span()));
                yield new BindingPattern(fresh, bp.span());
            }
            case ConstructorPattern cp -> {
                List<ConstructorPattern.PatternField> fields = new ArrayList<>(cp.fields().size());
                for (var f : cp.fields()) {
                    if (f.pattern() != null) {
                        fields.add(new ConstructorPattern.PatternField(f.name(), alphaRenamePattern(f.pattern(), subst), f.span()));
                    } else if (f.name() != null) {
                        String fresh = freshAlphaName(f.name());
                        subst.put(f.name(), new IdentExpr(fresh, f.span()));
                        fields.add(new ConstructorPattern.PatternField(f.name(), new BindingPattern(fresh, f.span()), f.span()));
                    } else {
                        fields.add(f);
                    }
                }
                yield new ConstructorPattern(cp.typePath(), fields, cp.span());
            }
            default -> pattern;
        };
    }

    private SmtExpr translateBranchExpr(
        Expr branch,
        Map<String, Sort> varSorts,
        Map<String, String> ssaEnv,
        Set<String> inliningSpecFns,
        boolean executableIntOverflow,
        Set<String> longVars
    ) {
        if (!(branch instanceof BlockExpr block)) {
            return translateExpr(branch, varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
        }
        Map<String, Expr> localSubst = new LinkedHashMap<>();
        for (var stmt : block.statements()) {
            switch (stmt) {
                case ValStmt v -> localSubst.put(v.name(), substituteExpr(v.initializer(), localSubst));
                case LetStmt l -> localSubst.put(l.name(), substituteExpr(l.initializer(), localSubst));
                case ReturnStmt r -> {
                    if (r.value() == null) {
                        return new SmtExpr.UnsupportedExpr("void return in if branch");
                    }
                    return translateExpr(substituteExpr(r.value(), localSubst), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
                }
                default -> {
                    return new SmtExpr.UnsupportedExpr("Non-expression statement in if branch");
                }
            }
        }
        if (block.tailExpr() == null) {
            return new SmtExpr.UnsupportedExpr("Missing tail expression in if branch");
        }
        return translateExpr(substituteExpr(block.tailExpr(), localSubst), varSorts, ssaEnv, inliningSpecFns, executableIntOverflow, longVars);
    }
}
