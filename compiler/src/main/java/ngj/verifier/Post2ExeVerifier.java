package ngj.verifier;

import ngj.ast.*;
import ngj.diagnostic.SecretRedactor;
import ngj.typechecker.VcGenerator;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Opcode;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.util.*;

import static java.lang.classfile.ClassFile.*;

/**
 * CERT-01 Surface C: Post2Exe isolated in-memory JVM bytecode predicate compiler & verifier.
 *
 * <p>Compiles {@code requires} + {@code ensures} + referenced {@code spec fn} declarations into an
 * isolated in-memory JVM class via {@link ClassFile} (JEP 484), loads it in an isolated
 * {@link ClassLoader}, and executes:
 * <ul>
 *   <li>Negative candidate / boundary mutant rejection checks</li>
 *   <li>Deterministic seeded ({@code 0x5EED2026L}) PBT + mutation witness checks with {@link SecretRedactor} masking</li>
 *   <li>2-call uniqueness check ({@code requires(x) && ensures(x, r1) && ensures(x, r2) => r1 == r2}) via {@link PureJvmSmtSolver}</li>
 * </ul>
 */
public final class Post2ExeVerifier {
    public static final long PBT_SEED = 0x5EED2026L;

    public record Post2ExeReport(
        boolean passed,
        int post2exeNegativeTotal,
        int post2exeNegativeRejected,
        double post2exeRejectionRate,
        int mutationPbtKilled,
        int mutationPbtTotal,
        boolean uniquenessProved,
        String uniquenessDetail,
        String strengthClassification,
        List<String> witnesses
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("passed", passed);
            map.put("post2exe_negative_total", post2exeNegativeTotal);
            map.put("post2exe_negative_rejected", post2exeNegativeRejected);
            map.put("post2exe_rejection_rate", post2exeRejectionRate);
            map.put("mutation_pbt_killed", mutationPbtKilled);
            map.put("mutation_pbt_total", mutationPbtTotal);
            map.put("uniqueness_proved", uniquenessProved);
            map.put("uniqueness_detail", uniquenessDetail);
            map.put("strength_classification", strengthClassification);
            map.put("witnesses", witnesses);
            return Collections.unmodifiableMap(map);
        }
    }

    private final Map<String, SpecFnDecl> specFns;
    private final PureJvmSmtSolver smtSolver;

    public Post2ExeVerifier(Map<String, SpecFnDecl> specFns) {
        this(specFns, new PureJvmSmtSolver());
    }

    public Post2ExeVerifier(Map<String, SpecFnDecl> specFns, PureJvmSmtSolver smtSolver) {
        this.specFns = specFns != null ? specFns : Map.of();
        this.smtSolver = smtSolver != null ? smtSolver : new PureJvmSmtSolver();
    }

    public Post2ExeReport verify(FnDecl fn) {
        Objects.requireNonNull(fn, "fn");

        List<Expr> requiresExprs = fn.contracts().stream()
            .filter(c -> c.kind() == ContractClause.Kind.REQUIRES)
            .map(ContractClause::condition)
            .toList();
        List<Expr> ensuresExprs = fn.contracts().stream()
            .filter(c -> c.kind() == ContractClause.Kind.ENSURES)
            .map(ContractClause::condition)
            .toList();

        // 1. 2-call uniqueness check via PureJvmSmtSolver + VcGenerator
        UniquenessResult uniqueness = checkTwoCallUniqueness(fn, requiresExprs, ensuresExprs);

        // 2. Compile requires + ensures + referenced SpecFnDecls into isolated in-memory JVM bytecode
        String internalName = "ngj/post2exe/Contract$" + sanitizeName(fn.name()) + "$Pred";
        String binaryName = internalName.replace('/', '.');
        byte[] bytecode = compilePredicateClass(internalName, fn, requiresExprs, ensuresExprs);

        Class<?> predClass = new IsolatedClassLoader().define(binaryName, bytecode);
        Method checkRequires;
        Method checkEnsures;
        try {
            checkRequires = predClass.getMethod("checkRequires", long[].class);
            checkEnsures = predClass.getMethod("checkEnsures", long[].class, long.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Generated Post2Exe predicate class missing expected methods", e);
        }

        // 3. Deterministic seeded PBT + negative candidate rejection
        Random rng = new Random(PBT_SEED);
        int paramCount = fn.params().size();
        Set<String> secretKeys = collectSecretParamNames(fn);
        List<long[]> validInputs = collectValidInputs(paramCount, checkRequires, rng);

        int negTotal = 0;
        int negRejected = 0;
        int mutTotal = 0;
        int mutKilled = 0;
        List<String> witnesses = new ArrayList<>();

        for (long[] args : validInputs) {
            OptionalLong satisfyingResult = findSatisfyingResult(fn, args, checkEnsures, rng);
            if (satisfyingResult.isEmpty()) {
                continue;
            }
            long goodRes = satisfyingResult.getAsLong();
            long[] mutants = new long[] {
                goodRes + 1L,
                goodRes - 1L,
                goodRes == 0L ? 1L : 0L,
                -goodRes - 1L,
                goodRes * 2L + 1L
            };
            for (long mutant : mutants) {
                if (mutant == goodRes) {
                    continue;
                }
                negTotal++;
                mutTotal++;
                boolean accepted = invokeEnsures(checkEnsures, args, mutant);
                if (!accepted) {
                    negRejected++;
                    mutKilled++;
                } else if (witnesses.size() < 5) {
                    witnesses.add(formatRedactedWitness(fn, args, mutant, secretKeys));
                }
            }
        }

        double rejectionRate = negTotal == 0 ? 0.0 : (double) negRejected / (double) negTotal;
        String strength;
        if (uniqueness.proved && rejectionRate > 0.0 && mutKilled > 0) {
            strength = "strong_deterministic";
        } else if (rejectionRate > 0.0) {
            strength = "weak_adequate";
        } else {
            strength = "vacuous_or_trivial";
        }

        boolean passed = !validInputs.isEmpty() && rejectionRate > 0.0;
        return new Post2ExeReport(
            passed,
            negTotal,
            negRejected,
            rejectionRate,
            mutKilled,
            mutTotal,
            uniqueness.proved,
            uniqueness.detail,
            strength,
            List.copyOf(witnesses)
        );
    }

    private record UniquenessResult(boolean proved, String detail) {}

    private UniquenessResult checkTwoCallUniqueness(FnDecl fn, List<Expr> requiresExprs, List<Expr> ensuresExprs) {
        if (ensuresExprs.isEmpty()) {
            return new UniquenessResult(false, "No ensures clause present");
        }
        VcGenerator vcGen = new VcGenerator(specFns);
        Map<String, SmtExpr.Sort> sorts = new LinkedHashMap<>();
        for (Param p : fn.params()) {
            sorts.put(p.name(), vcGen.sortOfType(p.type()));
        }
        SmtExpr.Sort retSort = vcGen.sortOfType(fn.returnType());
        sorts.put("result", retSort);

        List<SmtExpr> premises = new ArrayList<>();
        for (Expr req : requiresExprs) {
            premises.add(vcGen.translateExpr(req, sorts));
        }

        List<SmtExpr> ensList = new ArrayList<>();
        for (Expr ens : ensuresExprs) {
            ensList.add(vcGen.translateExpr(ens, sorts));
        }
        SmtExpr ensExpr = ensList.size() == 1 ? ensList.get(0) : new SmtExpr.And(ensList);
        SmtExpr ens1 = renameVar(ensExpr, "result", "result_1");
        SmtExpr ens2 = renameVar(ensExpr, "result", "result_2");

        List<SmtExpr> allPremises = new ArrayList<>(premises);
        allPremises.add(ens1);
        allPremises.add(ens2);

        SmtExpr antecedent = allPremises.size() == 1 ? allPremises.get(0) : new SmtExpr.And(allPremises);
        SmtExpr consequent = new SmtExpr.Eq(new SmtExpr.Var("result_1", retSort), new SmtExpr.Var("result_2", retSort));
        SmtOutcome outcome = smtSolver.prove(new SmtExpr.Implies(antecedent, consequent));
        return new UniquenessResult(outcome.status() == SmtOutcome.Status.PROVED, outcome.status().name() + ": " + outcome.detail());
    }

    private SmtExpr renameVar(SmtExpr expr, String from, String to) {
        return switch (expr) {
            case SmtExpr.Var v -> v.name().equals(from) ? new SmtExpr.Var(to, v.sort()) : v;
            case SmtExpr.Not n -> new SmtExpr.Not(renameVar(n.inner(), from, to));
            case SmtExpr.And a -> new SmtExpr.And(a.conjuncts().stream().map(c -> renameVar(c, from, to)).toList());
            case SmtExpr.Or o -> new SmtExpr.Or(o.disjuncts().stream().map(d -> renameVar(d, from, to)).toList());
            case SmtExpr.Implies i -> new SmtExpr.Implies(renameVar(i.premise(), from, to), renameVar(i.conclusion(), from, to));
            case SmtExpr.Eq eq -> new SmtExpr.Eq(renameVar(eq.left(), from, to), renameVar(eq.right(), from, to));
            case SmtExpr.Lt lt -> new SmtExpr.Lt(renameVar(lt.left(), from, to), renameVar(lt.right(), from, to));
            case SmtExpr.Le le -> new SmtExpr.Le(renameVar(le.left(), from, to), renameVar(le.right(), from, to));
            case SmtExpr.Gt gt -> new SmtExpr.Gt(renameVar(gt.left(), from, to), renameVar(gt.right(), from, to));
            case SmtExpr.Ge ge -> new SmtExpr.Ge(renameVar(ge.left(), from, to), renameVar(ge.right(), from, to));
            case SmtExpr.Add add -> new SmtExpr.Add(renameVar(add.left(), from, to), renameVar(add.right(), from, to));
            case SmtExpr.Sub sub -> new SmtExpr.Sub(renameVar(sub.left(), from, to), renameVar(sub.right(), from, to));
            case SmtExpr.Mul mul -> new SmtExpr.Mul(renameVar(mul.left(), from, to), renameVar(mul.right(), from, to));
            case SmtExpr.Ite ite -> new SmtExpr.Ite(
                renameVar(ite.cond(), from, to),
                renameVar(ite.thenExpr(), from, to),
                renameVar(ite.elseExpr(), from, to)
            );
            case SmtExpr.UfApp uf -> new SmtExpr.UfApp(
                uf.function(),
                uf.args().stream().map(a -> renameVar(a, from, to)).toList(),
                uf.sort()
            );
            default -> expr;
        };
    }

    private List<long[]> collectValidInputs(int paramCount, Method checkRequires, Random rng) {
        List<long[]> valid = new ArrayList<>();
        if (paramCount == 0) {
            long[] empty = new long[0];
            if (invokeRequires(checkRequires, empty)) {
                valid.add(empty);
            }
            return valid;
        }
        long[] seeds = new long[] { 0L, 1L, -1L, 2L, -2L, 5L, 10L, -10L, 42L, 100L, -100L, 1000L, -1000L };
        for (long s : seeds) {
            long[] candidate = new long[paramCount];
            Arrays.fill(candidate, s);
            if (invokeRequires(checkRequires, candidate)) {
                valid.add(candidate);
            }
        }
        for (int i = 0; i < 64 && valid.size() < 16; i++) {
            long[] candidate = new long[paramCount];
            for (int p = 0; p < paramCount; p++) {
                candidate[p] = rng.nextInt(2001) - 1000;
            }
            if (invokeRequires(checkRequires, candidate)) {
                valid.add(candidate);
            }
        }
        return valid;
    }

    private OptionalLong findSatisfyingResult(FnDecl fn, long[] args, Method checkEnsures, Random rng) {
        // Check common values and simple derived values first
        long[] candidates = new long[16 + args.length * 2];
        int idx = 0;
        candidates[idx++] = 0L;
        candidates[idx++] = 1L;
        candidates[idx++] = -1L;
        for (long a : args) {
            candidates[idx++] = a;
            candidates[idx++] = -a;
        }
        if (args.length > 0) {
            candidates[idx++] = args[0] + 1L;
            candidates[idx++] = args[0] - 1L;
            candidates[idx++] = args[0] * 2L;
            candidates[idx++] = Math.abs(args[0]);
        }
        for (int i = 0; i < idx; i++) {
            if (invokeEnsures(checkEnsures, args, candidates[i])) {
                return OptionalLong.of(candidates[i]);
            }
        }
        for (int i = 0; i < 64; i++) {
            long r = rng.nextInt(2001) - 1000;
            if (invokeEnsures(checkEnsures, args, r)) {
                return OptionalLong.of(r);
            }
        }
        return OptionalLong.empty();
    }

    private boolean invokeRequires(Method checkRequires, long[] args) {
        try {
            return (Boolean) checkRequires.invoke(null, (Object) args);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean invokeEnsures(Method checkEnsures, long[] args, long result) {
        try {
            return (Boolean) checkEnsures.invoke(null, args, result);
        } catch (Exception e) {
            return false;
        }
    }

    private Set<String> collectSecretParamNames(FnDecl fn) {
        Set<String> secrets = new HashSet<>();
        for (Param p : fn.params()) {
            if (p.type().toString().contains("Secret") || SecretRedactor.isSecretVariableName(p.name())) {
                secrets.add(p.name());
            }
        }
        return secrets;
    }

    private String formatRedactedWitness(FnDecl fn, long[] args, long mutantResult, Set<String> secretKeys) {
        Map<String, String> state = new LinkedHashMap<>();
        for (int i = 0; i < fn.params().size() && i < args.length; i++) {
            String name = fn.params().get(i).name();
            state.put(name, Long.toString(args[i]));
        }
        state.put("result", Long.toString(mutantResult));
        Map<String, String> redacted = SecretRedactor.redactState(state, secretKeys);
        return SecretRedactor.redactText("Accepted mutant witness: " + redacted);
    }

    private byte[] compilePredicateClass(
        String internalName,
        FnDecl fn,
        List<Expr> requiresExprs,
        List<Expr> ensuresExprs
    ) {
        ClassDesc ownerDesc = ClassDesc.ofInternalName(internalName);
        int classVersion = Runtime.version().feature() + 44;

        return ClassFile.of().build(ownerDesc, cb -> {
            cb.withVersion(classVersion, 0);
            cb.withFlags(ACC_PUBLIC | ACC_FINAL);
            cb.withSuperclass(ConstantDescs.CD_Object);

            // Emit referenced SpecFnDecls as static helper methods returning long
            for (SpecFnDecl spec : specFns.values()) {
                ClassDesc[] pDescs = new ClassDesc[spec.params().size()];
                Arrays.fill(pDescs, ConstantDescs.CD_long);
                MethodTypeDesc specDesc = MethodTypeDesc.of(ConstantDescs.CD_long, pDescs);
                cb.withMethod("spec$" + spec.name(), specDesc, ACC_PUBLIC | ACC_STATIC, mb -> mb.withCode(code -> {
                    EmitCtx ctx = new EmitCtx(code, ownerDesc);
                    for (Param p : spec.params()) {
                        ctx.allocateLong(p.name());
                    }
                    if (spec.body() != null) {
                        emitBlockAsLong(ctx, spec.body());
                    } else {
                        code.lconst_0();
                    }
                    code.lreturn();
                }));
            }

            // Emit checkRequires(long[] args) -> boolean
            cb.withMethod(
                "checkRequires",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_long.arrayType()),
                ACC_PUBLIC | ACC_STATIC,
                mb -> mb.withCode(code -> {
                    EmitCtx ctx = new EmitCtx(code, ownerDesc);
                    ctx.nextSlot = 1; // slot 0 is long[] args
                    bindArgsArray(ctx, fn.params(), 0);
                    emitConjunction(ctx, requiresExprs);
                    code.ireturn();
                })
            );

            // Emit checkEnsures(long[] args, long result) -> boolean
            cb.withMethod(
                "checkEnsures",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_long.arrayType(), ConstantDescs.CD_long),
                ACC_PUBLIC | ACC_STATIC,
                mb -> mb.withCode(code -> {
                    EmitCtx ctx = new EmitCtx(code, ownerDesc);
                    ctx.nextSlot = 3; // slot 0 is long[] args, slots 1-2 are long result
                    ctx.slots.put("result", 1);
                    bindArgsArray(ctx, fn.params(), 0);
                    emitConjunction(ctx, ensuresExprs);
                    code.ireturn();
                })
            );
        });
    }

    private void bindArgsArray(EmitCtx ctx, List<Param> params, int arraySlot) {
        for (int i = 0; i < params.size(); i++) {
            int slot = ctx.allocateLong(params.get(i).name());
            ctx.code.aload(arraySlot);
            ctx.code.ldc(i);
            ctx.code.laload();
            ctx.code.lstore(slot);
        }
    }

    private void emitConjunction(EmitCtx ctx, List<Expr> exprs) {
        if (exprs.isEmpty()) {
            ctx.code.iconst_1();
            return;
        }
        var falseLabel = ctx.code.newLabel();
        var endLabel = ctx.code.newLabel();
        for (Expr e : exprs) {
            emitExprAsLong(ctx, e);
            ctx.code.lconst_0();
            ctx.code.lcmp();
            ctx.code.ifeq(falseLabel);
        }
        ctx.code.iconst_1();
        ctx.code.goto_(endLabel);
        ctx.code.labelBinding(falseLabel);
        ctx.code.iconst_0();
        ctx.code.labelBinding(endLabel);
    }

    private void emitBlockAsLong(EmitCtx ctx, BlockExpr block) {
        for (Statement stmt : block.statements()) {
            switch (stmt) {
                case ValStmt v -> {
                    emitExprAsLong(ctx, v.initializer());
                    int slot = ctx.allocateLong(v.name());
                    ctx.code.lstore(slot);
                }
                case LetStmt l -> {
                    emitExprAsLong(ctx, l.initializer());
                    int slot = ctx.allocateLong(l.name());
                    ctx.code.lstore(slot);
                }
                case ReturnStmt r -> {
                    if (r.value() != null) {
                        emitExprAsLong(ctx, r.value());
                    } else {
                        ctx.code.lconst_0();
                    }
                    return;
                }
                default -> {}
            }
        }
        if (block.tailExpr() != null) {
            emitExprAsLong(ctx, block.tailExpr());
        } else {
            ctx.code.lconst_0();
        }
    }

    private void emitExprAsLong(EmitCtx ctx, Expr expr) {
        if (expr == null) {
            ctx.code.lconst_0();
            return;
        }
        switch (expr) {
            case LiteralExpr lit -> {
                switch (lit.kind()) {
                    case INT, LONG -> ctx.code.ldc(((Number) lit.value()).longValue());
                    case BOOL -> ctx.code.ldc(((Boolean) lit.value()) ? 1L : 0L);
                    default -> ctx.code.lconst_0();
                }
            }
            case IdentExpr id -> {
                Integer slot = ctx.slots.get(id.name());
                if (slot != null) {
                    ctx.code.lload(slot);
                } else {
                    ctx.code.lconst_0();
                }
            }
            case ParenExpr p -> emitExprAsLong(ctx, p.inner());
            case UnaryExpr u -> {
                emitExprAsLong(ctx, u.operand());
                switch (u.op()) {
                    case NEG -> ctx.code.lneg();
                    case NOT -> {
                        var trueLbl = ctx.code.newLabel();
                        var endLbl = ctx.code.newLabel();
                        ctx.code.lconst_0();
                        ctx.code.lcmp();
                        ctx.code.ifeq(trueLbl);
                        ctx.code.lconst_0();
                        ctx.code.goto_(endLbl);
                        ctx.code.labelBinding(trueLbl);
                        ctx.code.lconst_1();
                        ctx.code.labelBinding(endLbl);
                    }
                }
            }
            case BinaryExpr b -> {
                if (b.op() == BinaryExpr.BinaryOp.AND) {
                    var falseLbl = ctx.code.newLabel();
                    var endLbl = ctx.code.newLabel();
                    emitExprAsLong(ctx, b.left());
                    ctx.code.lconst_0();
                    ctx.code.lcmp();
                    ctx.code.ifeq(falseLbl);
                    emitExprAsLong(ctx, b.right());
                    ctx.code.lconst_0();
                    ctx.code.lcmp();
                    ctx.code.ifeq(falseLbl);
                    ctx.code.lconst_1();
                    ctx.code.goto_(endLbl);
                    ctx.code.labelBinding(falseLbl);
                    ctx.code.lconst_0();
                    ctx.code.labelBinding(endLbl);
                    return;
                }
                if (b.op() == BinaryExpr.BinaryOp.OR) {
                    var trueLbl = ctx.code.newLabel();
                    var endLbl = ctx.code.newLabel();
                    emitExprAsLong(ctx, b.left());
                    ctx.code.lconst_0();
                    ctx.code.lcmp();
                    ctx.code.ifne(trueLbl);
                    emitExprAsLong(ctx, b.right());
                    ctx.code.lconst_0();
                    ctx.code.lcmp();
                    ctx.code.ifne(trueLbl);
                    ctx.code.lconst_0();
                    ctx.code.goto_(endLbl);
                    ctx.code.labelBinding(trueLbl);
                    ctx.code.lconst_1();
                    ctx.code.labelBinding(endLbl);
                    return;
                }
                emitExprAsLong(ctx, b.left());
                emitExprAsLong(ctx, b.right());
                switch (b.op()) {
                    case ADD -> ctx.code.ladd();
                    case SUB -> ctx.code.lsub();
                    case MUL -> ctx.code.lmul();
                    case DIV -> ctx.code.ldiv();
                    case MOD -> ctx.code.lrem();
                    case EQ -> emitLongCmp(ctx, Opcode.IFEQ);
                    case NE -> emitLongCmp(ctx, Opcode.IFNE);
                    case LT -> emitLongCmp(ctx, Opcode.IFLT);
                    case LE -> emitLongCmp(ctx, Opcode.IFLE);
                    case GT -> emitLongCmp(ctx, Opcode.IFGT);
                    case GE -> emitLongCmp(ctx, Opcode.IFGE);
                    default -> {}
                }
            }
            case IfExpr ifExpr -> {
                var elseLbl = ctx.code.newLabel();
                var endLbl = ctx.code.newLabel();
                emitExprAsLong(ctx, ifExpr.condition());
                ctx.code.lconst_0();
                ctx.code.lcmp();
                ctx.code.ifeq(elseLbl);
                emitBlockAsLong(ctx, ifExpr.thenBranch());
                ctx.code.goto_(endLbl);
                ctx.code.labelBinding(elseLbl);
                if (ifExpr.elseBranch() instanceof BlockExpr eb) {
                    emitBlockAsLong(ctx, eb);
                } else if (ifExpr.elseBranch() != null) {
                    emitExprAsLong(ctx, ifExpr.elseBranch());
                } else {
                    ctx.code.lconst_0();
                }
                ctx.code.labelBinding(endLbl);
            }
            case BlockExpr block -> emitBlockAsLong(ctx, block);
            case CallExpr call -> {
                if (call.callee() instanceof IdentExpr id && specFns.containsKey(id.name())) {
                    SpecFnDecl spec = specFns.get(id.name());
                    for (Expr arg : call.args()) {
                        emitExprAsLong(ctx, arg);
                    }
                    ClassDesc[] pDescs = new ClassDesc[spec.params().size()];
                    Arrays.fill(pDescs, ConstantDescs.CD_long);
                    ctx.code.invokestatic(
                        ctx.ownerDesc,
                        "spec$" + spec.name(),
                        MethodTypeDesc.of(ConstantDescs.CD_long, pDescs)
                    );
                } else {
                    ctx.code.lconst_0();
                }
            }
            default -> ctx.code.lconst_0();
        }
    }

    private void emitLongCmp(EmitCtx ctx, Opcode branchTrue) {
        ctx.code.lcmp();
        ctx.code.ifThenElse(branchTrue, tb -> tb.lconst_1(), fb -> fb.lconst_0());
    }

    private static String sanitizeName(String name) {
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static final class EmitCtx {
        final CodeBuilder code;
        final ClassDesc ownerDesc;
        final Map<String, Integer> slots = new HashMap<>();
        int nextSlot = 0;

        EmitCtx(CodeBuilder code, ClassDesc ownerDesc) {
            this.code = code;
            this.ownerDesc = ownerDesc;
        }

        int allocateLong(String name) {
            int slot = nextSlot;
            slots.put(name, slot);
            nextSlot += 2;
            return slot;
        }
    }

    private static final class IsolatedClassLoader extends ClassLoader {
        IsolatedClassLoader() {
            super(Post2ExeVerifier.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytecode) {
            return defineClass(binaryName, bytecode, 0, bytecode.length);
        }
    }
}
