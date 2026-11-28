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
        return translateExpr(expr, varSorts, new LinkedHashSet<>());
    }

    private SmtExpr translateExpr(Expr expr, Map<String, Sort> varSorts, Set<String> inliningSpecFns) {
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
                Sort sort = varSorts.getOrDefault(id.name(), Sort.INT);
                yield new SmtExpr.Var(id.name(), sort);
            }
            case ParenExpr p -> translateExpr(p.inner(), varSorts, inliningSpecFns);
            case UnaryExpr u -> {
                SmtExpr op = translateExpr(u.operand(), varSorts, inliningSpecFns);
                yield switch (u.op()) {
                    case NEG -> new SmtExpr.Sub(SmtExpr.intConst(0), op);
                    case NOT -> new SmtExpr.Not(op);
                };
            }
            case BinaryExpr b -> {
                SmtExpr l = translateExpr(b.left(), varSorts, inliningSpecFns);
                SmtExpr r = translateExpr(b.right(), varSorts, inliningSpecFns);
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
                SmtExpr cond = translateExpr(ifExpr.condition(), varSorts, inliningSpecFns);
                Expr thenTail = extractBranchReturnOrTail(ifExpr.thenBranch());
                Expr elseTail = ifExpr.elseBranch() instanceof BlockExpr eb
                    ? extractBranchReturnOrTail(eb)
                    : ifExpr.elseBranch();
                if (thenTail == null || elseTail == null) {
                    yield new SmtExpr.UnsupportedExpr("Non-expression if branch in VC");
                }
                yield new SmtExpr.Ite(
                    cond,
                    translateExpr(thenTail, varSorts, inliningSpecFns),
                    translateExpr(elseTail, varSorts, inliningSpecFns)
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
                            yield translateExpr(substituteExpr(spec.body().tailExpr(), subst), varSorts, inliningSpecFns);
                        } finally {
                            inliningSpecFns.remove(id.name());
                        }
                    }
                    List<SmtExpr> args = call.args().stream()
                        .map(a -> translateExpr(a, varSorts, inliningSpecFns)).toList();
                    yield new SmtExpr.UfApp(id.name(), args, retSort);
                }
                List<SmtExpr> args = call.args().stream()
                    .map(a -> translateExpr(a, varSorts, inliningSpecFns)).toList();
                String fnName = call.callee() instanceof IdentExpr id ? id.name() : call.callee().toString();
                yield new SmtExpr.UfApp(fnName, args, Sort.INT);
            }
            case FieldAccessExpr fa -> new SmtExpr.UfApp(
                "field_" + fa.fieldName(),
                List.of(translateExpr(fa.object(), varSorts, inliningSpecFns)),
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
        for (var stmt : body.statements()) {
            switch (stmt) {
                case ValStmt v -> {
                    sorts.put(v.name(), sortOfType(v.explicitType()));
                    premises.add(new SmtExpr.Eq(
                        new SmtExpr.Var(v.name(), sorts.get(v.name())),
                        translateExpr(v.initializer(), sorts)
                    ));
                }
                case LetStmt l -> {
                    sorts.put(l.name(), sortOfType(l.explicitType()));
                    premises.add(new SmtExpr.Eq(
                        new SmtExpr.Var(l.name(), sorts.get(l.name())),
                        translateExpr(l.initializer(), sorts)
                    ));
                }
                case AssumeStmt a -> premises.add(translateExpr(a.condition(), sorts));
                case ExprStmt es -> {
                    if (es.expr() instanceof IfExpr ifExpr && ifExpr.elseBranch() != null) {
                        SmtExpr ite = translateExpr(ifExpr, sorts);
                        if (!(ite instanceof SmtExpr.UnsupportedExpr)) {
                            Sort resSort = sorts.getOrDefault("result", Sort.INT);
                            premises.add(new SmtExpr.Eq(new SmtExpr.Var("result", resSort), ite));
                        }
                    }
                }
                case ReturnStmt r -> {
                    if (r.value() != null) {
                        Sort resSort = sorts.getOrDefault("result", Sort.INT);
                        premises.add(new SmtExpr.Eq(
                            new SmtExpr.Var("result", resSort),
                            translateExpr(r.value(), sorts)
                        ));
                    }
                }
                default -> {}
            }
        }
        if (body.tailExpr() != null) {
            Sort resSort = sorts.getOrDefault("result", Sort.INT);
            premises.add(new SmtExpr.Eq(
                new SmtExpr.Var("result", resSort),
                translateExpr(body.tailExpr(), sorts)
            ));
        }
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
