package ngj.verifier;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Deterministic pure-JVM DPLL(T) + {@link BigInteger} QF-LIA Simplex and
 * Branch-and-Bound SMT solver for NGJ Tier 2 verification.
 */
public final class PureJvmSmtSolver {

    public static final long DEFAULT_TIMEOUT_MS = 2_000L;
    public static final int DEFAULT_MAX_NODES = 10_000;

    private final long timeoutMs;
    private final int maxNodes;

    public PureJvmSmtSolver() {
        this(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_NODES);
    }

    public PureJvmSmtSolver(long timeoutMs, int maxNodes) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        if (maxNodes <= 0) {
            throw new IllegalArgumentException("maxNodes must be positive: " + maxNodes);
        }
        this.timeoutMs = timeoutMs;
        this.maxNodes = maxNodes;
    }

    public long timeoutMs() {
        return timeoutMs;
    }

    public int maxNodes() {
        return maxNodes;
    }

    /**
     * Attempts to prove {@code obligation} valid (i.e. {@code Not(obligation)} is UNSAT).
     */
    public SmtOutcome prove(SmtExpr obligation) {
        Objects.requireNonNull(obligation, "obligation");
        SmtExpr negated = new SmtExpr.Not(obligation);
        SatResult res = solveSatInternal(negated);
        return switch (res.kind) {
            case UNSAT -> SmtOutcome.proved("Valid under pure-JVM DPLL(T)/QF-LIA");
            case SAT -> SmtOutcome.counterexample(res.model, "Counterexample found");
            case TIMEOUT -> SmtOutcome.timeout(res.detail);
            case UNSUPPORTED -> SmtOutcome.unsupported(res.detail);
            case UNPROVEN -> SmtOutcome.unproven(res.detail);
        };
    }

    /**
     * Checks satisfiability of {@code formula}.
     * Returns {@link SmtOutcome.Status#COUNTEREXAMPLE} with model when SAT,
     * {@link SmtOutcome.Status#PROVED} when UNSAT.
     */
    public SmtOutcome checkSat(SmtExpr formula) {
        Objects.requireNonNull(formula, "formula");
        SatResult res = solveSatInternal(formula);
        return switch (res.kind) {
            case SAT -> SmtOutcome.counterexample(res.model, "Satisfiable");
            case UNSAT -> SmtOutcome.proved("Unsatisfiable");
            case TIMEOUT -> SmtOutcome.timeout(res.detail);
            case UNSUPPORTED -> SmtOutcome.unsupported(res.detail);
            case UNPROVEN -> SmtOutcome.unproven(res.detail);
        };
    }

    private enum ResultKind {
        SAT, UNSAT, TIMEOUT, UNPROVEN, UNSUPPORTED
    }

    private record SatResult(ResultKind kind, Map<String, String> model, String detail) {
        static SatResult sat(Map<String, String> model) {
            return new SatResult(ResultKind.SAT, model, "");
        }

        static SatResult unsat() {
            return new SatResult(ResultKind.UNSAT, Map.of(), "");
        }

        static SatResult timeout(String detail) {
            return new SatResult(ResultKind.TIMEOUT, Map.of(), detail);
        }

        static SatResult unproven(String detail) {
            return new SatResult(ResultKind.UNPROVEN, Map.of(), detail);
        }

        static SatResult unsupported(String detail) {
            return new SatResult(ResultKind.UNSUPPORTED, Map.of(), detail);
        }
    }

    private SatResult solveSatInternal(SmtExpr root) {
        String unsupportedReason = findUnsupported(root);
        if (unsupportedReason != null) {
            return SatResult.unsupported(unsupportedReason);
        }

        Map<String, SmtExpr.Sort> userVars = new TreeMap<>();
        collectUserVars(root, userVars);

        Preprocessor prep = new Preprocessor();
        SmtExpr lowered = prep.lower(root);

        CnfEncoder encoder = new CnfEncoder();
        int topLit = encoder.encode(lowered);
        encoder.addClause(new int[]{topLit});

        long deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L;
        SearchBudget budget = new SearchBudget(maxNodes, deadlineNanos);

        DpllSolver dpll = new DpllSolver(encoder, budget);
        SatResult result = dpll.solve();
        if (result.kind == ResultKind.SAT) {
            Map<String, String> cleanModel = new TreeMap<>();
            for (Map.Entry<String, SmtExpr.Sort> entry : userVars.entrySet()) {
                String name = entry.getKey();
                if (entry.getValue() == SmtExpr.Sort.BOOL) {
                    cleanModel.put(name, result.model.getOrDefault(name, "false"));
                } else {
                    cleanModel.put(name, result.model.getOrDefault(name, "0"));
                }
            }
            return SatResult.sat(Collections.unmodifiableMap(cleanModel));
        }
        return result;
    }

    private static String findUnsupported(SmtExpr expr) {
        return switch (expr) {
            case SmtExpr.UnsupportedExpr u -> u.reason();
            case SmtExpr.IntConst ignored -> null;
            case SmtExpr.BoolConst ignored -> null;
            case SmtExpr.Var ignored -> null;
            case SmtExpr.Add a -> firstNonNull(findUnsupported(a.left()), findUnsupported(a.right()));
            case SmtExpr.Sub s -> firstNonNull(findUnsupported(s.left()), findUnsupported(s.right()));
            case SmtExpr.Mul m -> {
                String sub = firstNonNull(findUnsupported(m.left()), findUnsupported(m.right()));
                if (sub != null) {
                    yield sub;
                }
                if (evalConstInt(m.left()).isEmpty() && evalConstInt(m.right()).isEmpty()) {
                    yield "Non-linear multiplication is unsupported in QF-LIA";
                }
                yield null;
            }
            case SmtExpr.Eq e -> firstNonNull(findUnsupported(e.left()), findUnsupported(e.right()));
            case SmtExpr.Lt e -> firstNonNull(findUnsupported(e.left()), findUnsupported(e.right()));
            case SmtExpr.Le e -> firstNonNull(findUnsupported(e.left()), findUnsupported(e.right()));
            case SmtExpr.Gt e -> firstNonNull(findUnsupported(e.left()), findUnsupported(e.right()));
            case SmtExpr.Ge e -> firstNonNull(findUnsupported(e.left()), findUnsupported(e.right()));
            case SmtExpr.Not n -> findUnsupported(n.inner());
            case SmtExpr.Implies i -> firstNonNull(findUnsupported(i.premise()), findUnsupported(i.conclusion()));
            case SmtExpr.Ite i -> firstNonNull(
                    findUnsupported(i.cond()),
                    firstNonNull(findUnsupported(i.thenExpr()), findUnsupported(i.elseExpr())));
            case SmtExpr.And a -> {
                for (SmtExpr c : a.conjuncts()) {
                    String r = findUnsupported(c);
                    if (r != null) {
                        yield r;
                    }
                }
                yield null;
            }
            case SmtExpr.Or o -> {
                for (SmtExpr d : o.disjuncts()) {
                    String r = findUnsupported(d);
                    if (r != null) {
                        yield r;
                    }
                }
                yield null;
            }
            case SmtExpr.UfApp u -> {
                for (SmtExpr arg : u.args()) {
                    String r = findUnsupported(arg);
                    if (r != null) {
                        yield r;
                    }
                }
                yield null;
            }
        };
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static Optional<BigInteger> evalConstInt(SmtExpr expr) {
        return switch (expr) {
            case SmtExpr.IntConst c -> Optional.of(c.value());
            case SmtExpr.Add a -> {
                var l = evalConstInt(a.left());
                var r = evalConstInt(a.right());
                yield (l.isPresent() && r.isPresent()) ? Optional.of(l.get().add(r.get())) : Optional.empty();
            }
            case SmtExpr.Sub s -> {
                var l = evalConstInt(s.left());
                var r = evalConstInt(s.right());
                yield (l.isPresent() && r.isPresent()) ? Optional.of(l.get().subtract(r.get())) : Optional.empty();
            }
            case SmtExpr.Mul m -> {
                var l = evalConstInt(m.left());
                var r = evalConstInt(m.right());
                yield (l.isPresent() && r.isPresent()) ? Optional.of(l.get().multiply(r.get())) : Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    private static void collectUserVars(SmtExpr expr, Map<String, SmtExpr.Sort> out) {
        switch (expr) {
            case SmtExpr.Var v -> out.put(v.name(), v.sort());
            case SmtExpr.IntConst ignored -> {}
            case SmtExpr.BoolConst ignored -> {}
            case SmtExpr.UnsupportedExpr ignored -> {}
            case SmtExpr.Add a -> {
                collectUserVars(a.left(), out);
                collectUserVars(a.right(), out);
            }
            case SmtExpr.Sub s -> {
                collectUserVars(s.left(), out);
                collectUserVars(s.right(), out);
            }
            case SmtExpr.Mul m -> {
                collectUserVars(m.left(), out);
                collectUserVars(m.right(), out);
            }
            case SmtExpr.Eq e -> {
                collectUserVars(e.left(), out);
                collectUserVars(e.right(), out);
            }
            case SmtExpr.Lt e -> {
                collectUserVars(e.left(), out);
                collectUserVars(e.right(), out);
            }
            case SmtExpr.Le e -> {
                collectUserVars(e.left(), out);
                collectUserVars(e.right(), out);
            }
            case SmtExpr.Gt e -> {
                collectUserVars(e.left(), out);
                collectUserVars(e.right(), out);
            }
            case SmtExpr.Ge e -> {
                collectUserVars(e.left(), out);
                collectUserVars(e.right(), out);
            }
            case SmtExpr.Not n -> collectUserVars(n.inner(), out);
            case SmtExpr.Implies i -> {
                collectUserVars(i.premise(), out);
                collectUserVars(i.conclusion(), out);
            }
            case SmtExpr.Ite i -> {
                collectUserVars(i.cond(), out);
                collectUserVars(i.thenExpr(), out);
                collectUserVars(i.elseExpr(), out);
            }
            case SmtExpr.And a -> {
                for (SmtExpr c : a.conjuncts()) {
                    collectUserVars(c, out);
                }
            }
            case SmtExpr.Or o -> {
                for (SmtExpr d : o.disjuncts()) {
                    collectUserVars(d, out);
                }
            }
            case SmtExpr.UfApp u -> {
                for (SmtExpr arg : u.args()) {
                    collectUserVars(arg, out);
                }
            }
        }
    }

    /**
     * Eliminates {@code UfApp} (via Ackermannization) and integer {@code Ite}
     * (via fresh variables and guarded equalities).
     */
    private static final class Preprocessor {
        private int freshCounter = 0;
        private final List<SmtExpr> extraAxioms = new ArrayList<>();
        private final Map<SmtExpr.UfApp, SmtExpr.Var> ufMap = new LinkedHashMap<>();

        SmtExpr lower(SmtExpr expr) {
            SmtExpr rewritten = rewrite(expr);
            addAckermannAxioms();
            if (extraAxioms.isEmpty()) {
                return rewritten;
            }
            List<SmtExpr> all = new ArrayList<>(extraAxioms.size() + 1);
            all.add(rewritten);
            all.addAll(extraAxioms);
            return new SmtExpr.And(all);
        }

        private SmtExpr rewrite(SmtExpr expr) {
            return switch (expr) {
                case SmtExpr.IntConst c -> c;
                case SmtExpr.BoolConst b -> b;
                case SmtExpr.Var v -> v;
                case SmtExpr.UnsupportedExpr u -> u;
                case SmtExpr.Add a -> new SmtExpr.Add(rewrite(a.left()), rewrite(a.right()));
                case SmtExpr.Sub s -> new SmtExpr.Sub(rewrite(s.left()), rewrite(s.right()));
                case SmtExpr.Mul m -> new SmtExpr.Mul(rewrite(m.left()), rewrite(m.right()));
                case SmtExpr.Eq e -> new SmtExpr.Eq(rewrite(e.left()), rewrite(e.right()));
                case SmtExpr.Lt e -> new SmtExpr.Lt(rewrite(e.left()), rewrite(e.right()));
                case SmtExpr.Le e -> new SmtExpr.Le(rewrite(e.left()), rewrite(e.right()));
                case SmtExpr.Gt e -> new SmtExpr.Gt(rewrite(e.left()), rewrite(e.right()));
                case SmtExpr.Ge e -> new SmtExpr.Ge(rewrite(e.left()), rewrite(e.right()));
                case SmtExpr.Not n -> new SmtExpr.Not(rewrite(n.inner()));
                case SmtExpr.Implies i -> new SmtExpr.Implies(rewrite(i.premise()), rewrite(i.conclusion()));
                case SmtExpr.And a -> {
                    List<SmtExpr> list = new ArrayList<>(a.conjuncts().size());
                    for (SmtExpr c : a.conjuncts()) {
                        list.add(rewrite(c));
                    }
                    yield new SmtExpr.And(list);
                }
                case SmtExpr.Or o -> {
                    List<SmtExpr> list = new ArrayList<>(o.disjuncts().size());
                    for (SmtExpr d : o.disjuncts()) {
                        list.add(rewrite(d));
                    }
                    yield new SmtExpr.Or(list);
                }
                case SmtExpr.Ite i -> {
                    SmtExpr cond = rewrite(i.cond());
                    SmtExpr thenE = rewrite(i.thenExpr());
                    SmtExpr elseE = rewrite(i.elseExpr());
                    if (isBoolExpr(thenE) && isBoolExpr(elseE)) {
                        yield new SmtExpr.And(List.of(
                                new SmtExpr.Implies(cond, thenE),
                                new SmtExpr.Implies(new SmtExpr.Not(cond), elseE)));
                    }
                    SmtExpr.Var v = new SmtExpr.Var("$ite_" + (freshCounter++), SmtExpr.Sort.INT);
                    extraAxioms.add(new SmtExpr.Implies(cond, new SmtExpr.Eq(v, thenE)));
                    extraAxioms.add(new SmtExpr.Implies(new SmtExpr.Not(cond), new SmtExpr.Eq(v, elseE)));
                    yield v;
                }
                case SmtExpr.UfApp u -> {
                    List<SmtExpr> args = new ArrayList<>(u.args().size());
                    for (SmtExpr arg : u.args()) {
                        args.add(rewrite(arg));
                    }
                    SmtExpr.UfApp normalized = new SmtExpr.UfApp(u.function(), args, u.sort());
                    SmtExpr.Var existing = ufMap.get(normalized);
                    if (existing != null) {
                        yield existing;
                    }
                    SmtExpr.Var fresh = new SmtExpr.Var("$uf_" + (freshCounter++), u.sort());
                    ufMap.put(normalized, fresh);
                    yield fresh;
                }
            };
        }

        private void addAckermannAxioms() {
            List<Map.Entry<SmtExpr.UfApp, SmtExpr.Var>> entries = new ArrayList<>(ufMap.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                for (int j = i + 1; j < entries.size(); j++) {
                    SmtExpr.UfApp a = entries.get(i).getKey();
                    SmtExpr.UfApp b = entries.get(j).getKey();
                    if (a.function().equals(b.function()) && a.args().size() == b.args().size()) {
                        SmtExpr.Var va = entries.get(i).getValue();
                        SmtExpr.Var vb = entries.get(j).getValue();
                        if (a.args().isEmpty()) {
                            extraAxioms.add(new SmtExpr.Eq(va, vb));
                        } else {
                            List<SmtExpr> eqs = new ArrayList<>(a.args().size());
                            for (int k = 0; k < a.args().size(); k++) {
                                eqs.add(new SmtExpr.Eq(a.args().get(k), b.args().get(k)));
                            }
                            extraAxioms.add(new SmtExpr.Implies(
                                    new SmtExpr.And(eqs), new SmtExpr.Eq(va, vb)));
                        }
                    }
                }
            }
        }

        private static boolean isBoolExpr(SmtExpr expr) {
            return switch (expr) {
                case SmtExpr.BoolConst ignored -> true;
                case SmtExpr.Var v -> v.sort() == SmtExpr.Sort.BOOL;
                case SmtExpr.Eq ignored -> true;
                case SmtExpr.Lt ignored -> true;
                case SmtExpr.Le ignored -> true;
                case SmtExpr.Gt ignored -> true;
                case SmtExpr.Ge ignored -> true;
                case SmtExpr.And ignored -> true;
                case SmtExpr.Or ignored -> true;
                case SmtExpr.Not ignored -> true;
                case SmtExpr.Implies ignored -> true;
                case SmtExpr.UfApp u -> u.sort() == SmtExpr.Sort.BOOL;
                case SmtExpr.Ite i -> isBoolExpr(i.thenExpr()) && isBoolExpr(i.elseExpr());
                default -> false;
            };
        }
    }

    /**
     * Normalized linear integer expression: {@code sum(coeff * var) + constant}.
     */
    private record LinearForm(TreeMap<String, BigInteger> coeffs, BigInteger constant) {
        static LinearForm constant(BigInteger c) {
            return new LinearForm(new TreeMap<>(), c);
        }

        static LinearForm var(String name) {
            TreeMap<String, BigInteger> map = new TreeMap<>();
            map.put(name, BigInteger.ONE);
            return new LinearForm(map, BigInteger.ZERO);
        }

        LinearForm add(LinearForm other) {
            TreeMap<String, BigInteger> res = new TreeMap<>(this.coeffs);
            for (Map.Entry<String, BigInteger> e : other.coeffs.entrySet()) {
                BigInteger sum = res.getOrDefault(e.getKey(), BigInteger.ZERO).add(e.getValue());
                if (sum.signum() == 0) {
                    res.remove(e.getKey());
                } else {
                    res.put(e.getKey(), sum);
                }
            }
            return new LinearForm(res, this.constant.add(other.constant));
        }

        LinearForm sub(LinearForm other) {
            return add(other.scale(BigInteger.valueOf(-1)));
        }

        LinearForm scale(BigInteger k) {
            if (k.signum() == 0) {
                return constant(BigInteger.ZERO);
            }
            TreeMap<String, BigInteger> res = new TreeMap<>();
            for (Map.Entry<String, BigInteger> e : this.coeffs.entrySet()) {
                res.put(e.getKey(), e.getValue().multiply(k));
            }
            return new LinearForm(res, this.constant.multiply(k));
        }

        static LinearForm fromExpr(SmtExpr expr) {
            return switch (expr) {
                case SmtExpr.IntConst c -> constant(c.value());
                case SmtExpr.Var v -> var(v.name());
                case SmtExpr.Add a -> fromExpr(a.left()).add(fromExpr(a.right()));
                case SmtExpr.Sub s -> fromExpr(s.left()).sub(fromExpr(s.right()));
                case SmtExpr.Mul m -> {
                    Optional<BigInteger> lc = evalConstInt(m.left());
                    if (lc.isPresent()) {
                        yield fromExpr(m.right()).scale(lc.get());
                    }
                    Optional<BigInteger> rc = evalConstInt(m.right());
                    if (rc.isPresent()) {
                        yield fromExpr(m.left()).scale(rc.get());
                    }
                    throw new IllegalStateException("Non-linear multiplication should have been rejected");
                }
                default -> throw new IllegalStateException("Unexpected integer expr: " + expr);
            };
        }
    }

    /**
     * Normalized linear constraint {@code sum(coeffs * var) <= rhs}.
     */
    private record LinearLe(TreeMap<String, BigInteger> coeffs, BigInteger rhs) {
        LinearLe negate() {
            TreeMap<String, BigInteger> neg = new TreeMap<>();
            for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
                neg.put(e.getKey(), e.getValue().negate());
            }
            return new LinearLe(neg, rhs.negate().subtract(BigInteger.ONE));
        }
    }

    private static final class CnfEncoder {
        private int nextVar = 1;
        private final int trueVar;
        private final List<int[]> clauses = new ArrayList<>();
        private final Map<String, Integer> boolVars = new LinkedHashMap<>();
        private final Map<LinearLe, Integer> theoryAtoms = new LinkedHashMap<>();
        private final Map<Integer, LinearLe> idToTheory = new LinkedHashMap<>();
        private final Map<Integer, String> idToBoolName = new LinkedHashMap<>();

        CnfEncoder() {
            this.trueVar = nextVar++;
            clauses.add(new int[]{trueVar});
        }

        int freshVar() {
            return nextVar++;
        }

        void addClause(int[] clause) {
            clauses.add(clause);
        }

        int encode(SmtExpr expr) {
            return switch (expr) {
                case SmtExpr.BoolConst b -> b.value() ? trueVar : -trueVar;
                case SmtExpr.Var v -> boolVars.computeIfAbsent(v.name(), k -> {
                    int id = freshVar();
                    idToBoolName.put(id, k);
                    return id;
                });
                case SmtExpr.Not n -> -encode(n.inner());
                case SmtExpr.And a -> {
                    if (a.conjuncts().isEmpty()) {
                        yield trueVar;
                    }
                    List<Integer> lits = new ArrayList<>(a.conjuncts().size());
                    for (SmtExpr c : a.conjuncts()) {
                        lits.add(encode(c));
                    }
                    if (lits.size() == 1) {
                        yield lits.get(0);
                    }
                    int out = freshVar();
                    int[] back = new int[lits.size() + 1];
                    back[0] = out;
                    for (int i = 0; i < lits.size(); i++) {
                        int lit = lits.get(i);
                        clauses.add(new int[]{-out, lit});
                        back[i + 1] = -lit;
                    }
                    clauses.add(back);
                    yield out;
                }
                case SmtExpr.Or o -> {
                    if (o.disjuncts().isEmpty()) {
                        yield -trueVar;
                    }
                    List<Integer> lits = new ArrayList<>(o.disjuncts().size());
                    for (SmtExpr d : o.disjuncts()) {
                        lits.add(encode(d));
                    }
                    if (lits.size() == 1) {
                        yield lits.get(0);
                    }
                    int out = freshVar();
                    int[] fwd = new int[lits.size() + 1];
                    fwd[0] = -out;
                    for (int i = 0; i < lits.size(); i++) {
                        int lit = lits.get(i);
                        clauses.add(new int[]{out, -lit});
                        fwd[i + 1] = lit;
                    }
                    clauses.add(fwd);
                    yield out;
                }
                case SmtExpr.Implies imp -> encode(new SmtExpr.Or(
                        List.of(new SmtExpr.Not(imp.premise()), imp.conclusion())));
                case SmtExpr.Eq eq -> {
                    if (Preprocessor.isBoolExpr(eq.left()) && Preprocessor.isBoolExpr(eq.right())) {
                        int a = encode(eq.left());
                        int b = encode(eq.right());
                        int out = freshVar();
                        // out <=> (a <=> b)
                        clauses.add(new int[]{-out, -a, b});
                        clauses.add(new int[]{-out, a, -b});
                        clauses.add(new int[]{out, a, b});
                        clauses.add(new int[]{out, -a, -b});
                        yield out;
                    }
                    yield encode(new SmtExpr.And(List.of(
                            new SmtExpr.Le(eq.left(), eq.right()),
                            new SmtExpr.Le(eq.right(), eq.left()))));
                }
                case SmtExpr.Le le -> encodeLinearLe(
                        LinearForm.fromExpr(le.left()).sub(LinearForm.fromExpr(le.right())),
                        BigInteger.ZERO);
                case SmtExpr.Lt lt -> encodeLinearLe(
                        LinearForm.fromExpr(lt.left()).sub(LinearForm.fromExpr(lt.right())),
                        BigInteger.valueOf(-1));
                case SmtExpr.Ge ge -> encodeLinearLe(
                        LinearForm.fromExpr(ge.right()).sub(LinearForm.fromExpr(ge.left())),
                        BigInteger.ZERO);
                case SmtExpr.Gt gt -> encodeLinearLe(
                        LinearForm.fromExpr(gt.right()).sub(LinearForm.fromExpr(gt.left())),
                        BigInteger.valueOf(-1));
                default -> throw new IllegalStateException("Unexpected boolean expr: " + expr);
            };
        }

        private int encodeLinearLe(LinearForm diff, BigInteger bound) {
            BigInteger rhs = bound.subtract(diff.constant());
            if (diff.coeffs().isEmpty()) {
                return BigInteger.ZERO.compareTo(rhs) <= 0 ? trueVar : -trueVar;
            }
            LinearLe atom = new LinearLe(diff.coeffs(), rhs);
            Integer existing = theoryAtoms.get(atom);
            if (existing != null) {
                return existing;
            }
            int id = freshVar();
            theoryAtoms.put(atom, id);
            idToTheory.put(id, atom);
            return id;
        }
    }

    private static final class SearchBudget {
        private final int maxNodes;
        private final long deadlineNanos;
        private int nodesVisited = 0;

        SearchBudget(int maxNodes, long deadlineNanos) {
            this.maxNodes = maxNodes;
            this.deadlineNanos = deadlineNanos;
        }

        boolean checkExhausted() {
            return consumeWork(1);
        }

        boolean consumeWork(int units) {
            nodesVisited += Math.max(1, units);
            return isTimedOut();
        }

        boolean isTimedOut() {
            return System.nanoTime() > deadlineNanos || nodesVisited > maxNodes;
        }
    }

    private static final class DpllSolver {
        private final CnfEncoder encoder;
        private final SearchBudget budget;
        private final int numVars;

        DpllSolver(CnfEncoder encoder, SearchBudget budget) {
            this.encoder = encoder;
            this.budget = budget;
            this.numVars = encoder.nextVar - 1;
        }

        SatResult solve() {
            int[] assign = new int[numVars + 1];
            return search(assign);
        }

        private SatResult search(int[] assign) {
            if (budget.checkExhausted()) {
                return SatResult.timeout("DPLL/branch-and-bound budget exceeded");
            }
            if (!unitPropagate(assign)) {
                return SatResult.unsat();
            }

            int unassigned = -1;
            for (int v = 1; v <= numVars; v++) {
                if (assign[v] == 0) {
                    unassigned = v;
                    break;
                }
            }
            if (unassigned == -1) {
                return checkTheory(assign);
            }

            // Deterministic branching: try true (+1), then false (-1)
            int[] copyTrue = assign.clone();
            copyTrue[unassigned] = 1;
            SatResult resTrue = search(copyTrue);
            if (resTrue.kind == ResultKind.SAT || resTrue.kind == ResultKind.TIMEOUT) {
                return resTrue;
            }

            int[] copyFalse = assign.clone();
            copyFalse[unassigned] = -1;
            return search(copyFalse);
        }

        private boolean unitPropagate(int[] assign) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int[] clause : encoder.clauses) {
                    boolean satisfied = false;
                    int unassignedCount = 0;
                    int lastUnassigned = 0;
                    for (int lit : clause) {
                        int var = Math.abs(lit);
                        int val = assign[var];
                        if (val == 0) {
                            unassignedCount++;
                            lastUnassigned = lit;
                        } else if ((lit > 0 && val == 1) || (lit < 0 && val == -1)) {
                            satisfied = true;
                            break;
                        }
                    }
                    if (satisfied) {
                        continue;
                    }
                    if (unassignedCount == 0) {
                        return false;
                    }
                    if (unassignedCount == 1) {
                        assign[Math.abs(lastUnassigned)] = lastUnassigned > 0 ? 1 : -1;
                        changed = true;
                    }
                }
            }
            return true;
        }

        private SatResult checkTheory(int[] assign) {
            List<LinearLe> active = new ArrayList<>();
            for (Map.Entry<Integer, LinearLe> entry : encoder.idToTheory.entrySet()) {
                int id = entry.getKey();
                if (assign[id] == 1) {
                    active.add(entry.getValue());
                } else if (assign[id] == -1) {
                    active.add(entry.getValue().negate());
                }
            }

            SimplexBnbSolver qflia = new SimplexBnbSolver(active, budget);
            SimplexBnbSolver.Outcome outcome = qflia.solve();
            return switch (outcome.status) {
                case SAT -> {
                    Map<String, String> model = new TreeMap<>();
                    for (Map.Entry<Integer, String> b : encoder.idToBoolName.entrySet()) {
                        model.put(b.getValue(), assign[b.getKey()] == 1 ? "true" : "false");
                    }
                    for (Map.Entry<String, BigInteger> iv : outcome.intModel.entrySet()) {
                        model.put(iv.getKey(), iv.getValue().toString());
                    }
                    yield SatResult.sat(model);
                }
                case UNSAT -> SatResult.unsat();
                case TIMEOUT -> SatResult.timeout("QF-LIA branch-and-bound budget exceeded");
            };
        }
    }

    /**
     * Exact rational {@link BigInteger} fraction for Simplex.
     */
    private static final class BigFraction implements Comparable<BigFraction> {
        static final BigFraction ZERO = new BigFraction(BigInteger.ZERO, BigInteger.ONE);
        static final BigFraction ONE = new BigFraction(BigInteger.ONE, BigInteger.ONE);

        final BigInteger num;
        final BigInteger den;

        private BigFraction(BigInteger num, BigInteger den) {
            this.num = num;
            this.den = den;
        }

        static BigFraction of(BigInteger num, BigInteger den) {
            if (den.signum() == 0) {
                throw new ArithmeticException("Zero denominator");
            }
            if (num.signum() == 0) {
                return ZERO;
            }
            if (den.signum() < 0) {
                num = num.negate();
                den = den.negate();
            }
            BigInteger g = num.abs().gcd(den);
            return new BigFraction(num.divide(g), den.divide(g));
        }

        static BigFraction of(BigInteger num) {
            return num.signum() == 0 ? ZERO : new BigFraction(num, BigInteger.ONE);
        }

        boolean isInteger() {
            return den.equals(BigInteger.ONE);
        }

        BigInteger floor() {
            if (isInteger()) {
                return num;
            }
            BigInteger[] qr = num.divideAndRemainder(den);
            if (num.signum() < 0 && qr[1].signum() != 0) {
                return qr[0].subtract(BigInteger.ONE);
            }
            return qr[0];
        }

        BigFraction add(BigFraction o) {
            if (this.num.signum() == 0) {
                return o;
            }
            if (o.num.signum() == 0) {
                return this;
            }
            return of(this.num.multiply(o.den).add(o.num.multiply(this.den)),
                    this.den.multiply(o.den));
        }

        BigFraction sub(BigFraction o) {
            if (o.num.signum() == 0) {
                return this;
            }
            return of(this.num.multiply(o.den).subtract(o.num.multiply(this.den)),
                    this.den.multiply(o.den));
        }

        BigFraction mul(BigFraction o) {
            if (this.num.signum() == 0 || o.num.signum() == 0) {
                return ZERO;
            }
            return of(this.num.multiply(o.num), this.den.multiply(o.den));
        }

        BigFraction div(BigFraction o) {
            return of(this.num.multiply(o.den), this.den.multiply(o.num));
        }

        BigFraction negate() {
            return num.signum() == 0 ? ZERO : new BigFraction(num.negate(), den);
        }

        int signum() {
            return num.signum();
        }

        @Override
        public int compareTo(BigFraction o) {
            return this.num.multiply(o.den).compareTo(o.num.multiply(this.den));
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            return obj instanceof BigFraction o && num.equals(o.num) && den.equals(o.den);
        }

        @Override
        public int hashCode() {
            return Objects.hash(num, den);
        }
    }

    /**
     * Dutertre-de Moura General Simplex with Bland's rule + Integer Branch-and-Bound.
     */
    private static final class SimplexBnbSolver {
        enum Status { SAT, UNSAT, TIMEOUT }

        record Outcome(Status status, Map<String, BigInteger> intModel) {}

        private static final int MAX_SIMPLEX_PIVOTS = 20_000;

        private final List<String> origVarNames;
        private final int numOrig;
        private final int numSlacks;
        private final int totalVars;
        private final BigFraction[][] initialTableau;
        private final BigFraction[] initialUpper;
        private final SearchBudget budget;

        SimplexBnbSolver(List<LinearLe> constraints, SearchBudget budget) {
            this.budget = budget;
            TreeMap<String, Integer> varIndex = new TreeMap<>();
            for (LinearLe c : constraints) {
                for (String v : c.coeffs().keySet()) {
                    varIndex.computeIfAbsent(v, k -> varIndex.size());
                }
            }
            this.origVarNames = new ArrayList<>(varIndex.keySet());
            this.numOrig = origVarNames.size();
            this.numSlacks = constraints.size();
            this.totalVars = numOrig + numSlacks;
            this.initialTableau = new BigFraction[numSlacks][numOrig];
            this.initialUpper = new BigFraction[totalVars];

            for (int r = 0; r < numSlacks; r++) {
                LinearLe c = constraints.get(r);
                for (int col = 0; col < numOrig; col++) {
                    initialTableau[r][col] = BigFraction.ZERO;
                }
                for (Map.Entry<String, BigInteger> e : c.coeffs().entrySet()) {
                    int col = varIndex.get(e.getKey());
                    initialTableau[r][col] = BigFraction.of(e.getValue());
                }
                initialUpper[numOrig + r] = BigFraction.of(c.rhs());
            }
        }

        Outcome solve() {
            if (numSlacks == 0) {
                return new Outcome(Status.SAT, Map.of());
            }
            BigFraction[] lower = new BigFraction[totalVars];
            BigFraction[] upper = initialUpper.clone();
            return branchAndBound(lower, upper);
        }

        private Outcome branchAndBound(BigFraction[] lower, BigFraction[] upper) {
            if (budget.checkExhausted()) {
                return new Outcome(Status.TIMEOUT, Map.of());
            }

            // Quick infeasibility check on variable bounds
            for (int v = 0; v < numOrig; v++) {
                if (lower[v] != null && upper[v] != null && lower[v].compareTo(upper[v]) > 0) {
                    return new Outcome(Status.UNSAT, Map.of());
                }
            }

            BigFraction[] solution = runSimplex(lower, upper);
            if (solution == null) {
                return budget.isTimedOut()
                        ? new Outcome(Status.TIMEOUT, Map.of())
                        : new Outcome(Status.UNSAT, Map.of());
            }

            // Check if all original variables are integral
            int fracVar = -1;
            for (int v = 0; v < numOrig; v++) {
                if (!solution[v].isInteger()) {
                    fracVar = v;
                    break;
                }
            }

            if (fracVar == -1) {
                Map<String, BigInteger> model = new TreeMap<>();
                for (int v = 0; v < numOrig; v++) {
                    model.put(origVarNames.get(v), solution[v].num);
                }
                return new Outcome(Status.SAT, model);
            }

            BigInteger fl = solution[fracVar].floor();
            BigInteger ce = fl.add(BigInteger.ONE);

            // Branch 1: x <= fl
            BigFraction[] lo1 = lower.clone();
            BigFraction[] up1 = upper.clone();
            BigFraction flFrac = BigFraction.of(fl);
            up1[fracVar] = up1[fracVar] == null ? flFrac : (flFrac.compareTo(up1[fracVar]) < 0 ? flFrac : up1[fracVar]);
            Outcome o1 = branchAndBound(lo1, up1);
            if (o1.status == Status.SAT || o1.status == Status.TIMEOUT) {
                return o1;
            }

            // Branch 2: x >= ce
            BigFraction[] lo2 = lower.clone();
            BigFraction[] up2 = upper.clone();
            BigFraction ceFrac = BigFraction.of(ce);
            lo2[fracVar] = lo2[fracVar] == null ? ceFrac : (ceFrac.compareTo(lo2[fracVar]) > 0 ? ceFrac : lo2[fracVar]);
            return branchAndBound(lo2, up2);
        }

        private BigFraction[] runSimplex(BigFraction[] lower, BigFraction[] upper) {
            int[] basic = new int[numSlacks];
            int[] nonBasic = new int[numOrig];
            BigFraction[][] tab = new BigFraction[numSlacks][numOrig];
            BigFraction[] val = new BigFraction[totalVars];

            for (int c = 0; c < numOrig; c++) {
                nonBasic[c] = c;
                BigFraction v = BigFraction.ZERO;
                if (lower[c] != null && v.compareTo(lower[c]) < 0) {
                    v = lower[c];
                }
                if (upper[c] != null && v.compareTo(upper[c]) > 0) {
                    v = upper[c];
                }
                val[c] = v;
            }
            for (int r = 0; r < numSlacks; r++) {
                basic[r] = numOrig + r;
                BigFraction sum = BigFraction.ZERO;
                for (int c = 0; c < numOrig; c++) {
                    tab[r][c] = initialTableau[r][c];
                    sum = sum.add(tab[r][c].mul(val[nonBasic[c]]));
                }
                val[basic[r]] = sum;
            }

            final int pivotCost = Math.max(1, (numOrig * numSlacks) / 100);
            for (int iter = 0; iter < MAX_SIMPLEX_PIVOTS; iter++) {
                if (budget.consumeWork(pivotCost)) {
                    return null;
                }
                // Bland's rule: smallest variable ID among basic variables violating bounds
                int leaveRow = -1;
                int leaveVar = Integer.MAX_VALUE;
                boolean needIncrease = false;

                for (int r = 0; r < numSlacks; r++) {
                    int b = basic[r];
                    if (b < leaveVar) {
                        if (lower[b] != null && val[b].compareTo(lower[b]) < 0) {
                            leaveRow = r;
                            leaveVar = b;
                            needIncrease = true;
                        } else if (upper[b] != null && val[b].compareTo(upper[b]) > 0) {
                            leaveRow = r;
                            leaveVar = b;
                            needIncrease = false;
                        }
                    }
                }

                if (leaveRow == -1) {
                    return val;
                }

                // Bland's rule: smallest variable ID among eligible non-basic variables
                int enterCol = -1;
                int enterVar = Integer.MAX_VALUE;
                for (int c = 0; c < numOrig; c++) {
                    int nb = nonBasic[c];
                    BigFraction coeff = tab[leaveRow][c];
                    if (coeff.signum() == 0 || nb >= enterVar) {
                        continue;
                    }
                    boolean canIncrease = upper[nb] == null || val[nb].compareTo(upper[nb]) < 0;
                    boolean canDecrease = lower[nb] == null || val[nb].compareTo(lower[nb]) > 0;
                    if (needIncrease) {
                        if ((coeff.signum() > 0 && canIncrease) || (coeff.signum() < 0 && canDecrease)) {
                            enterCol = c;
                            enterVar = nb;
                        }
                    } else {
                        if ((coeff.signum() > 0 && canDecrease) || (coeff.signum() < 0 && canIncrease)) {
                            enterCol = c;
                            enterVar = nb;
                        }
                    }
                }

                if (enterCol == -1) {
                    return null;
                }

                // Pivot (leaveRow, enterCol) and update val[leaveVar] to its violated bound
                BigFraction targetVal = needIncrease ? lower[leaveVar] : upper[leaveVar];
                pivotAndUpdate(tab, basic, nonBasic, val, leaveRow, enterCol, targetVal);
            }
            return null;
        }

        private void pivotAndUpdate(
                BigFraction[][] tab,
                int[] basic,
                int[] nonBasic,
                BigFraction[] val,
                int leaveRow,
                int enterCol,
                BigFraction targetLeaveVal) {

            int leaveVar = basic[leaveRow];
            int enterVar = nonBasic[enterCol];
            BigFraction a = tab[leaveRow][enterCol];

            // deltaEnter = (targetLeaveVal - val[leaveVar]) / a
            BigFraction deltaLeave = targetLeaveVal.sub(val[leaveVar]);
            BigFraction deltaEnter = deltaLeave.div(a);

            val[enterVar] = val[enterVar].add(deltaEnter);
            for (int r = 0; r < numSlacks; r++) {
                if (r == leaveRow) {
                    val[leaveVar] = targetLeaveVal;
                } else if (tab[r][enterCol].signum() != 0) {
                    val[basic[r]] = val[basic[r]].add(tab[r][enterCol].mul(deltaEnter));
                }
            }

            // Rewrite leaveRow: enterVar = (1/a)*leaveVar - sum_{c != enterCol} (tab[leaveRow][c]/a)*nonBasic[c]
            BigFraction invA = BigFraction.ONE.div(a);
            for (int c = 0; c < numOrig; c++) {
                if (c == enterCol) {
                    tab[leaveRow][c] = invA;
                } else {
                    tab[leaveRow][c] = tab[leaveRow][c].div(a).negate();
                }
            }

            // Substitute enterVar in all other rows
            for (int r = 0; r < numSlacks; r++) {
                if (r == leaveRow) {
                    continue;
                }
                BigFraction factor = tab[r][enterCol];
                if (factor.signum() == 0) {
                    continue;
                }
                tab[r][enterCol] = BigFraction.ZERO;
                for (int c = 0; c < numOrig; c++) {
                    tab[r][c] = tab[r][c].add(factor.mul(tab[leaveRow][c]));
                }
            }

            basic[leaveRow] = enterVar;
            nonBasic[enterCol] = leaveVar;
        }
    }
}
