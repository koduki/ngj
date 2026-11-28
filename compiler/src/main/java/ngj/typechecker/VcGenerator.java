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

    public VcGenerator(Map<String, SpecFnDecl> specFns) {
        this.specFns = specFns != null ? specFns : Map.of();
    }

    public SmtExpr translateExpr(Expr expr, Map<String, Sort> varSorts) {
        return translateExpr(expr, varSorts, Map.of(), new LinkedHashSet<>());
    }

    private SmtExpr translateExpr(
        Expr expr,
        Map<String, Sort> varSorts,
        Map<String, String> ssaEnv,
        Set<String> inliningSpecFns
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
            case ParenExpr p -> translateExpr(p.inner(), varSorts, ssaEnv, inliningSpecFns);
            case UnaryExpr u -> {
                SmtExpr op = translateExpr(u.operand(), varSorts, ssaEnv, inliningSpecFns);
                yield switch (u.op()) {
                    case NEG -> new SmtExpr.Sub(SmtExpr.intConst(0), op);
                    case NOT -> new SmtExpr.Not(op);
                };
            }
            case BinaryExpr b -> {
                SmtExpr l = translateExpr(b.left(), varSorts, ssaEnv, inliningSpecFns);
                SmtExpr r = translateExpr(b.right(), varSorts, ssaEnv, inliningSpecFns);
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
                SmtExpr cond = translateExpr(ifExpr.condition(), varSorts, ssaEnv, inliningSpecFns);
                Expr thenTail = extractBranchReturnOrTail(ifExpr.thenBranch());
                Expr elseTail = ifExpr.elseBranch() instanceof BlockExpr eb
                    ? extractBranchReturnOrTail(eb)
                    : ifExpr.elseBranch();
                if (thenTail == null || elseTail == null) {
                    yield new SmtExpr.UnsupportedExpr("Non-expression if branch in VC");
                }
                yield new SmtExpr.Ite(
                    cond,
                    translateExpr(thenTail, varSorts, ssaEnv, inliningSpecFns),
                    translateExpr(elseTail, varSorts, ssaEnv, inliningSpecFns)
                );
            }
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr id && specFns.containsKey(id.name())) {
                    SpecFnDecl spec = specFns.get(id.name());
                    Sort retSort = sortOfType(spec.returnType());
                    if (!inliningSpecFns.contains(id.name())
                        && spec.body() != null && spec.body().tailExpr() != null && spec.body().statements().isEmpty()
                        && spec.params().size() == call.args().size()) {
                        Map<String, Expr> subst = new LinkedHashMap<>();
                        for (int i = 0; i < spec.params().size(); i++) {
                            subst.put(spec.params().get(i).name(), call.args().get(i));
                        }
                        inliningSpecFns.add(id.name());
                        try {
                            yield translateExpr(substituteExpr(spec.body().tailExpr(), subst), varSorts, ssaEnv, inliningSpecFns);
                        } finally {
                            inliningSpecFns.remove(id.name());
                        }
                    }
                    List<SmtExpr> args = call.args().stream()
                        .map(a -> translateExpr(a, varSorts, ssaEnv, inliningSpecFns)).toList();
                    yield new SmtExpr.UfApp(id.name(), args, retSort);
                }
                List<SmtExpr> args = call.args().stream()
                    .map(a -> translateExpr(a, varSorts, ssaEnv, inliningSpecFns)).toList();
                String fnName = call.callee() instanceof IdentExpr id ? id.name() : call.callee().toString();
                yield new SmtExpr.UfApp(fnName, args, Sort.INT);
            }
            case FieldAccessExpr fa -> new SmtExpr.UfApp(
                "field_" + fa.fieldName(),
                List.of(translateExpr(fa.object(), varSorts, ssaEnv, inliningSpecFns)),
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

        if (fn.body() != null) {
            extractBodyFacts(fn.body(), sorts, premises);
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

    private void extractBodyFacts(BlockExpr body, Map<String, Sort> sorts, List<SmtExpr> premises) {
        Sort resSort = sorts.getOrDefault("result", Sort.INT);
        Map<String, String> ssaEnv = new LinkedHashMap<>();
        Map<String, Integer> ssaCounter = new LinkedHashMap<>();
        SmtExpr bodyResult = translateBlockResult(body.statements(), 0, body.tailExpr(), sorts, premises, ssaEnv, ssaCounter);
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
        Map<String, Integer> ssaCounter
    ) {
        if (idx >= stmts.size()) {
            return tailExpr != null ? translateExpr(tailExpr, sorts, ssaEnv, new LinkedHashSet<>()) : null;
        }
        Statement stmt = stmts.get(idx);
        switch (stmt) {
            case ValStmt v -> {
                SmtExpr init = translateExpr(v.initializer(), sorts, ssaEnv, new LinkedHashSet<>());
                Sort sort = sortOfType(v.explicitType());
                String lhs = (ssaEnv.containsKey(v.name()) || sorts.containsKey(v.name()))
                    ? freshSsa(v.name(), sort, sorts, ssaEnv, ssaCounter)
                    : v.name();
                sorts.put(lhs, sort);
                ssaEnv.put(v.name(), lhs);
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), init));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter);
            }
            case LetStmt l -> {
                SmtExpr init = translateExpr(l.initializer(), sorts, ssaEnv, new LinkedHashSet<>());
                Sort sort = sortOfType(l.explicitType());
                String lhs = (ssaEnv.containsKey(l.name()) || sorts.containsKey(l.name()))
                    ? freshSsa(l.name(), sort, sorts, ssaEnv, ssaCounter)
                    : l.name();
                sorts.put(lhs, sort);
                ssaEnv.put(l.name(), lhs);
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), init));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter);
            }
            case VarStmt v -> {
                SmtExpr init = translateExpr(v.initializer(), sorts, ssaEnv, new LinkedHashSet<>());
                Sort sort = sortOfType(v.explicitType());
                String lhs = freshSsa(v.name(), sort, sorts, ssaEnv, ssaCounter);
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), init));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter);
            }
            case AssignStmt a -> {
                SmtExpr rhs = translateExpr(a.value(), sorts, ssaEnv, new LinkedHashSet<>());
                String prevName = ssaEnv.getOrDefault(a.name(), a.name());
                Sort sort = sorts.getOrDefault(prevName, Sort.INT);
                String lhs = freshSsa(a.name(), sort, sorts, ssaEnv, ssaCounter);
                premises.add(new SmtExpr.Eq(new SmtExpr.Var(lhs, sort), rhs));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter);
            }
            case AssumeStmt a -> {
                premises.add(translateExpr(a.condition(), sorts, ssaEnv, new LinkedHashSet<>()));
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter);
            }
            case ReturnStmt r -> {
                return r.value() != null ? translateExpr(r.value(), sorts, ssaEnv, new LinkedHashSet<>()) : null;
            }
            case ExprStmt es -> {
                if (es.expr() instanceof WhileExpr) {
                    return new SmtExpr.UnsupportedExpr("while_mutated_state");
                }
                if (es.expr() instanceof IfExpr ifExpr) {
                    SmtExpr cond = translateExpr(ifExpr.condition(), sorts, ssaEnv, new LinkedHashSet<>());
                    List<SmtExpr> thenPremises = new ArrayList<>();
                    SmtExpr thenRes = translateBlockResult(
                        ifExpr.thenBranch().statements(), 0, ifExpr.thenBranch().tailExpr(),
                        sorts, thenPremises, new LinkedHashMap<>(ssaEnv), ssaCounter);
                    if (thenRes instanceof SmtExpr.UnsupportedExpr) {
                        return thenRes;
                    }
                    SmtExpr elseRes = ifExpr.elseBranch() != null
                        ? (ifExpr.elseBranch() instanceof BlockExpr eb
                            ? translateBlockResult(eb.statements(), 0, eb.tailExpr(),
                                sorts, new ArrayList<>(), new LinkedHashMap<>(ssaEnv), ssaCounter)
                            : translateExpr(ifExpr.elseBranch(), sorts, ssaEnv, new LinkedHashSet<>()))
                        : translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, new LinkedHashMap<>(ssaEnv), ssaCounter);
                    if (elseRes instanceof SmtExpr.UnsupportedExpr) {
                        return elseRes;
                    }
                    if (thenRes != null && elseRes != null && !(cond instanceof SmtExpr.UnsupportedExpr)) {
                        return new SmtExpr.Ite(cond, thenRes, elseRes);
                    }
                    if (thenRes != null && ifExpr.elseBranch() == null) {
                        return new SmtExpr.UnsupportedExpr("partial_return_if");
                    }
                    if (containsMutationOrWhile(ifExpr)) {
                        return new SmtExpr.UnsupportedExpr("if_mutated_state");
                    }
                }
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter);
            }
            default -> {
                return translateBlockResult(stmts, idx + 1, tailExpr, sorts, premises, ssaEnv, ssaCounter);
            }
        }
    }

    private boolean containsMutationOrWhile(Expr expr) {
        if (expr == null) return false;
        return switch (expr) {
            case WhileExpr _ -> true;
            case BlockExpr b -> {
                for (var st : b.statements()) {
                    if (st instanceof AssignStmt) yield true;
                    if (st instanceof ExprStmt es && containsMutationOrWhile(es.expr())) yield true;
                }
                yield containsMutationOrWhile(b.tailExpr());
            }
            case IfExpr i -> containsMutationOrWhile(i.thenBranch()) || containsMutationOrWhile(i.elseBranch());
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
        if (expr == null) return null;
        return switch (expr) {
            case IdentExpr id -> subst.getOrDefault(id.name(), id);
            case BinaryExpr b -> new BinaryExpr(substituteExpr(b.left(), subst), b.op(), substituteExpr(b.right(), subst), b.span());
            case UnaryExpr u -> new UnaryExpr(u.op(), substituteExpr(u.operand(), subst), u.span());
            case ParenExpr p -> new ParenExpr(substituteExpr(p.inner(), subst), p.span());
            default -> expr;
        };
    }
}
