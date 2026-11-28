package ngj.verifier;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional external Z3 SMT-LIB2 solver wrapper ({@code z3 -smt2 -in}) with
 * timeout enforcement and clean {@code UNSUPPORTED} / fallback handling when
 * {@code z3} is unavailable on {@code PATH}.
 */
public final class ExternalZ3Solver {

    public static final long DEFAULT_TIMEOUT_MS = 2_000L;

    private static final Pattern DEFINE_FUN_PATTERN = Pattern.compile(
            "\\(define-fun\\s+(\\S+)\\s+\\(\\)\\s+(Int|Bool)\\s+([^)]+|\\(-\\s+\\d+\\))\\)");

    private final long timeoutMs;
    private final String z3Command;

    public ExternalZ3Solver() {
        this(DEFAULT_TIMEOUT_MS, "z3");
    }

    public ExternalZ3Solver(long timeoutMs) {
        this(timeoutMs, "z3");
    }

    public ExternalZ3Solver(long timeoutMs, String z3Command) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
        }
        this.timeoutMs = timeoutMs;
        this.z3Command = Objects.requireNonNull(z3Command, "z3Command");
    }

    public boolean isAvailable() {
        try {
            Process proc = new ProcessBuilder(z3Command, "--version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(1_000L, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return false;
            }
            return proc.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    public SmtOutcome prove(SmtExpr obligation) {
        Objects.requireNonNull(obligation, "obligation");
        if (containsUnsupported(obligation)) {
            return SmtOutcome.unsupported("Obligation contains unsupported SMT construct");
        }

        String smtLib = toSmtLib2(obligation);
        Process proc;
        try {
            proc = new ProcessBuilder(z3Command, "-smt2", "-in")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return SmtOutcome.unsupported("External z3 binary not available on PATH: " + e.getMessage());
        }

        try {
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(smtLib);
                writer.flush();
            }

            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return SmtOutcome.timeout("External z3 exceeded timeout of " + timeoutMs + " ms");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            return parseZ3Output(output.toString());
        } catch (IOException e) {
            return SmtOutcome.unsupported("Failed communicating with external z3: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            return SmtOutcome.timeout("External z3 interrupted");
        }
    }

    public SmtOutcome proveWithFallback(SmtExpr obligation, PureJvmSmtSolver fallback) {
        Objects.requireNonNull(fallback, "fallback");
        SmtOutcome z3Result = prove(obligation);
        if (z3Result.status() == SmtOutcome.Status.UNSUPPORTED) {
            return fallback.prove(obligation);
        }
        return z3Result;
    }

    static String toSmtLib2(SmtExpr obligation) {
        TreeMap<String, SmtExpr.Sort> vars = new TreeMap<>();
        TreeMap<String, UfSig> ufs = new TreeMap<>();
        collectDecls(obligation, vars, ufs);

        StringBuilder sb = new StringBuilder();
        sb.append("(set-logic QF_UFLIA)\n");
        for (Map.Entry<String, SmtExpr.Sort> v : vars.entrySet()) {
            sb.append("(declare-const ")
                    .append(quoteSymbol(v.getKey()))
                    .append(' ')
                    .append(sortName(v.getValue()))
                    .append(")\n");
        }
        for (Map.Entry<String, UfSig> u : ufs.entrySet()) {
            sb.append("(declare-fun ")
                    .append(quoteSymbol(u.getKey()))
                    .append(" (");
            List<String> argSorts = new ArrayList<>(u.getValue().argSorts.size());
            for (SmtExpr.Sort s : u.getValue().argSorts) {
                argSorts.add(sortName(s));
            }
            sb.append(String.join(" ", argSorts));
            sb.append(") ").append(sortName(u.getValue().retSort)).append(")\n");
        }
        sb.append("(assert (not ").append(renderExpr(obligation)).append("))\n");
        sb.append("(check-sat)\n");
        sb.append("(get-model)\n");
        return sb.toString();
    }

    private record UfSig(List<SmtExpr.Sort> argSorts, SmtExpr.Sort retSort) {}

    private static String sortName(SmtExpr.Sort sort) {
        return sort == SmtExpr.Sort.INT ? "Int" : "Bool";
    }

    private static String quoteSymbol(String name) {
        if (name.matches("[a-zA-Z_][a-zA-Z0-9_.$]*")) {
            return name;
        }
        return "|" + name.replace("|", "_") + "|";
    }

    private static void collectDecls(
            SmtExpr expr, Map<String, SmtExpr.Sort> vars, Map<String, UfSig> ufs) {
        switch (expr) {
            case SmtExpr.Var v -> vars.put(v.name(), v.sort());
            case SmtExpr.IntConst ignored -> {}
            case SmtExpr.BoolConst ignored -> {}
            case SmtExpr.UnsupportedExpr ignored -> {}
            case SmtExpr.Add a -> {
                collectDecls(a.left(), vars, ufs);
                collectDecls(a.right(), vars, ufs);
            }
            case SmtExpr.Sub s -> {
                collectDecls(s.left(), vars, ufs);
                collectDecls(s.right(), vars, ufs);
            }
            case SmtExpr.Mul m -> {
                collectDecls(m.left(), vars, ufs);
                collectDecls(m.right(), vars, ufs);
            }
            case SmtExpr.Eq e -> {
                collectDecls(e.left(), vars, ufs);
                collectDecls(e.right(), vars, ufs);
            }
            case SmtExpr.Lt e -> {
                collectDecls(e.left(), vars, ufs);
                collectDecls(e.right(), vars, ufs);
            }
            case SmtExpr.Le e -> {
                collectDecls(e.left(), vars, ufs);
                collectDecls(e.right(), vars, ufs);
            }
            case SmtExpr.Gt e -> {
                collectDecls(e.left(), vars, ufs);
                collectDecls(e.right(), vars, ufs);
            }
            case SmtExpr.Ge e -> {
                collectDecls(e.left(), vars, ufs);
                collectDecls(e.right(), vars, ufs);
            }
            case SmtExpr.Not n -> collectDecls(n.inner(), vars, ufs);
            case SmtExpr.Implies i -> {
                collectDecls(i.premise(), vars, ufs);
                collectDecls(i.conclusion(), vars, ufs);
            }
            case SmtExpr.Ite i -> {
                collectDecls(i.cond(), vars, ufs);
                collectDecls(i.thenExpr(), vars, ufs);
                collectDecls(i.elseExpr(), vars, ufs);
            }
            case SmtExpr.And a -> {
                for (SmtExpr c : a.conjuncts()) {
                    collectDecls(c, vars, ufs);
                }
            }
            case SmtExpr.Or o -> {
                for (SmtExpr d : o.disjuncts()) {
                    collectDecls(d, vars, ufs);
                }
            }
            case SmtExpr.UfApp u -> {
                List<SmtExpr.Sort> argSorts = new ArrayList<>(u.args().size());
                for (SmtExpr arg : u.args()) {
                    collectDecls(arg, vars, ufs);
                    argSorts.add(inferSort(arg));
                }
                ufs.putIfAbsent(u.function(), new UfSig(argSorts, u.sort()));
            }
        }
    }

    private static SmtExpr.Sort inferSort(SmtExpr expr) {
        return switch (expr) {
            case SmtExpr.IntConst ignored -> SmtExpr.Sort.INT;
            case SmtExpr.BoolConst ignored -> SmtExpr.Sort.BOOL;
            case SmtExpr.Var v -> v.sort();
            case SmtExpr.Add ignored -> SmtExpr.Sort.INT;
            case SmtExpr.Sub ignored -> SmtExpr.Sort.INT;
            case SmtExpr.Mul ignored -> SmtExpr.Sort.INT;
            case SmtExpr.UfApp u -> u.sort();
            case SmtExpr.Ite i -> inferSort(i.thenExpr());
            default -> SmtExpr.Sort.BOOL;
        };
    }

    private static String renderExpr(SmtExpr expr) {
        return switch (expr) {
            case SmtExpr.IntConst c -> c.value().signum() < 0
                    ? "(- " + c.value().abs() + ")"
                    : c.value().toString();
            case SmtExpr.BoolConst b -> b.value() ? "true" : "false";
            case SmtExpr.Var v -> quoteSymbol(v.name());
            case SmtExpr.Add a -> "(+ " + renderExpr(a.left()) + " " + renderExpr(a.right()) + ")";
            case SmtExpr.Sub s -> "(- " + renderExpr(s.left()) + " " + renderExpr(s.right()) + ")";
            case SmtExpr.Mul m -> "(* " + renderExpr(m.left()) + " " + renderExpr(m.right()) + ")";
            case SmtExpr.Eq e -> "(= " + renderExpr(e.left()) + " " + renderExpr(e.right()) + ")";
            case SmtExpr.Lt e -> "(< " + renderExpr(e.left()) + " " + renderExpr(e.right()) + ")";
            case SmtExpr.Le e -> "(<= " + renderExpr(e.left()) + " " + renderExpr(e.right()) + ")";
            case SmtExpr.Gt e -> "(> " + renderExpr(e.left()) + " " + renderExpr(e.right()) + ")";
            case SmtExpr.Ge e -> "(>= " + renderExpr(e.left()) + " " + renderExpr(e.right()) + ")";
            case SmtExpr.Not n -> "(not " + renderExpr(n.inner()) + ")";
            case SmtExpr.Implies i -> "(=> " + renderExpr(i.premise()) + " " + renderExpr(i.conclusion()) + ")";
            case SmtExpr.Ite i -> "(ite " + renderExpr(i.cond()) + " "
                    + renderExpr(i.thenExpr()) + " " + renderExpr(i.elseExpr()) + ")";
            case SmtExpr.And a -> {
                if (a.conjuncts().isEmpty()) {
                    yield "true";
                }
                List<String> parts = new ArrayList<>(a.conjuncts().size());
                for (SmtExpr c : a.conjuncts()) {
                    parts.add(renderExpr(c));
                }
                yield "(and " + String.join(" ", parts) + ")";
            }
            case SmtExpr.Or o -> {
                if (o.disjuncts().isEmpty()) {
                    yield "false";
                }
                List<String> parts = new ArrayList<>(o.disjuncts().size());
                for (SmtExpr d : o.disjuncts()) {
                    parts.add(renderExpr(d));
                }
                yield "(or " + String.join(" ", parts) + ")";
            }
            case SmtExpr.UfApp u -> {
                if (u.args().isEmpty()) {
                    yield quoteSymbol(u.function());
                }
                List<String> parts = new ArrayList<>(u.args().size());
                for (SmtExpr arg : u.args()) {
                    parts.add(renderExpr(arg));
                }
                yield "(" + quoteSymbol(u.function()) + " " + String.join(" ", parts) + ")";
            }
            case SmtExpr.UnsupportedExpr u -> "false";
        };
    }

    private static boolean containsUnsupported(SmtExpr expr) {
        return switch (expr) {
            case SmtExpr.UnsupportedExpr ignored -> true;
            case SmtExpr.IntConst ignored -> false;
            case SmtExpr.BoolConst ignored -> false;
            case SmtExpr.Var ignored -> false;
            case SmtExpr.Add a -> containsUnsupported(a.left()) || containsUnsupported(a.right());
            case SmtExpr.Sub s -> containsUnsupported(s.left()) || containsUnsupported(s.right());
            case SmtExpr.Mul m -> containsUnsupported(m.left()) || containsUnsupported(m.right());
            case SmtExpr.Eq e -> containsUnsupported(e.left()) || containsUnsupported(e.right());
            case SmtExpr.Lt e -> containsUnsupported(e.left()) || containsUnsupported(e.right());
            case SmtExpr.Le e -> containsUnsupported(e.left()) || containsUnsupported(e.right());
            case SmtExpr.Gt e -> containsUnsupported(e.left()) || containsUnsupported(e.right());
            case SmtExpr.Ge e -> containsUnsupported(e.left()) || containsUnsupported(e.right());
            case SmtExpr.Not n -> containsUnsupported(n.inner());
            case SmtExpr.Implies i -> containsUnsupported(i.premise()) || containsUnsupported(i.conclusion());
            case SmtExpr.Ite i -> containsUnsupported(i.cond())
                    || containsUnsupported(i.thenExpr())
                    || containsUnsupported(i.elseExpr());
            case SmtExpr.And a -> a.conjuncts().stream().anyMatch(ExternalZ3Solver::containsUnsupported);
            case SmtExpr.Or o -> o.disjuncts().stream().anyMatch(ExternalZ3Solver::containsUnsupported);
            case SmtExpr.UfApp u -> u.args().stream().anyMatch(ExternalZ3Solver::containsUnsupported);
        };
    }

    private static SmtOutcome parseZ3Output(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return SmtOutcome.unproven("Empty output from external z3");
        }
        String firstLine = trimmed.split("\\R", 2)[0].trim();
        return switch (firstLine) {
            case "unsat" -> SmtOutcome.proved("Valid under external z3");
            case "sat" -> {
                Map<String, String> model = new TreeMap<>();
                Matcher m = DEFINE_FUN_PATTERN.matcher(trimmed);
                while (m.find()) {
                    String name = m.group(1);
                    String val = m.group(3).trim();
                    if (val.startsWith("(- ") && val.endsWith(")")) {
                        val = "-" + val.substring(3, val.length() - 1).trim();
                    }
                    model.put(name, val);
                }
                yield SmtOutcome.counterexample(
                        Collections.unmodifiableMap(model),
                        "Counterexample found by external z3");
            }
            case "unknown" -> SmtOutcome.unproven("External z3 returned unknown");
            default -> SmtOutcome.unsupported("Unexpected z3 output: " + firstLine);
        };
    }
}
