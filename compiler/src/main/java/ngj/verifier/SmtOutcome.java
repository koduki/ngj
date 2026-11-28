package ngj.verifier;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Outcome of an SMT verification or satisfiability query.
 */
public record SmtOutcome(Status status, Map<String, String> model, String detail) {

    public enum Status {
        PROVED,
        COUNTEREXAMPLE,
        UNPROVEN,
        TIMEOUT,
        UNSUPPORTED
    }

    public SmtOutcome {
        Objects.requireNonNull(status, "status");
        model = model == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(model));
        detail = detail == null ? "" : detail;
    }

    public static SmtOutcome proved(String detail) {
        return new SmtOutcome(Status.PROVED, Map.of(), detail);
    }

    public static SmtOutcome counterexample(Map<String, String> model, String detail) {
        return new SmtOutcome(Status.COUNTEREXAMPLE, model, detail);
    }

    public static SmtOutcome unproven(String detail) {
        return new SmtOutcome(Status.UNPROVEN, Map.of(), detail);
    }

    public static SmtOutcome timeout(String detail) {
        return new SmtOutcome(Status.TIMEOUT, Map.of(), detail);
    }

    public static SmtOutcome unsupported(String detail) {
        return new SmtOutcome(Status.UNSUPPORTED, Map.of(), detail);
    }
}
