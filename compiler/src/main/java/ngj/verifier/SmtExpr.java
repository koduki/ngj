package ngj.verifier;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * Sealed algebraic AST for boolean, QF-LIA, equality, and uninterpreted-function
 * verification obligations.
 */
public sealed interface SmtExpr permits
        SmtExpr.IntConst,
        SmtExpr.BoolConst,
        SmtExpr.Var,
        SmtExpr.Add,
        SmtExpr.Sub,
        SmtExpr.Mul,
        SmtExpr.Eq,
        SmtExpr.Lt,
        SmtExpr.Le,
        SmtExpr.Gt,
        SmtExpr.Ge,
        SmtExpr.And,
        SmtExpr.Or,
        SmtExpr.Not,
        SmtExpr.Implies,
        SmtExpr.Ite,
        SmtExpr.UfApp,
        SmtExpr.UnsupportedExpr {

    enum Sort {
        INT,
        BOOL
    }

    record IntConst(BigInteger value) implements SmtExpr {
        public IntConst {
            Objects.requireNonNull(value, "value");
        }
    }

    record BoolConst(boolean value) implements SmtExpr {}

    record Var(String name, Sort sort) implements SmtExpr {
        public Var {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sort, "sort");
        }
    }

    record Add(SmtExpr left, SmtExpr right) implements SmtExpr {
        public Add {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Sub(SmtExpr left, SmtExpr right) implements SmtExpr {
        public Sub {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Mul(SmtExpr left, SmtExpr right) implements SmtExpr {
        public Mul {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Eq(SmtExpr left, SmtExpr right) implements SmtExpr {
        public Eq {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Lt(SmtExpr left, SmtExpr right) implements SmtExpr {
        public Lt {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Le(SmtExpr left, SmtExpr right) implements SmtExpr {
        public Le {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Gt(SmtExpr left, SmtExpr right) implements SmtExpr {
        public Gt {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record Ge(SmtExpr left, SmtExpr right) implements SmtExpr {
        public Ge {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record And(List<SmtExpr> conjuncts) implements SmtExpr {
        public And {
            conjuncts = List.copyOf(Objects.requireNonNull(conjuncts, "conjuncts"));
        }
    }

    record Or(List<SmtExpr> disjuncts) implements SmtExpr {
        public Or {
            disjuncts = List.copyOf(Objects.requireNonNull(disjuncts, "disjuncts"));
        }
    }

    record Not(SmtExpr inner) implements SmtExpr {
        public Not {
            Objects.requireNonNull(inner, "inner");
        }
    }

    record Implies(SmtExpr premise, SmtExpr conclusion) implements SmtExpr {
        public Implies {
            Objects.requireNonNull(premise, "premise");
            Objects.requireNonNull(conclusion, "conclusion");
        }
    }

    record Ite(SmtExpr cond, SmtExpr thenExpr, SmtExpr elseExpr) implements SmtExpr {
        public Ite {
            Objects.requireNonNull(cond, "cond");
            Objects.requireNonNull(thenExpr, "thenExpr");
            Objects.requireNonNull(elseExpr, "elseExpr");
        }
    }

    record UfApp(String function, List<SmtExpr> args, Sort sort) implements SmtExpr {
        public UfApp {
            Objects.requireNonNull(function, "function");
            args = List.copyOf(Objects.requireNonNull(args, "args"));
            Objects.requireNonNull(sort, "sort");
        }
    }

    record UnsupportedExpr(String reason) implements SmtExpr {
        public UnsupportedExpr {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static IntConst intConst(long value) {
        return new IntConst(BigInteger.valueOf(value));
    }

    static IntConst intConst(BigInteger value) {
        return new IntConst(value);
    }

    static BoolConst boolConst(boolean value) {
        return new BoolConst(value);
    }

    static Var intVar(String name) {
        return new Var(name, Sort.INT);
    }

    static Var boolVar(String name) {
        return new Var(name, Sort.BOOL);
    }

    static And and(SmtExpr... exprs) {
        return new And(List.of(exprs));
    }

    static Or or(SmtExpr... exprs) {
        return new Or(List.of(exprs));
    }
}
