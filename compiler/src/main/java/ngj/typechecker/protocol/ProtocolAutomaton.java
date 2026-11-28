package ngj.typechecker.protocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * AST-independent finite-state protocol automaton for NGJ Phase 3 protocol
 * verification, refinement trace-inclusion checking, and Mermaid export.
 */
public final class ProtocolAutomaton {

    public record Transition(String from, String action, String to, Set<String> effects) {
        public Transition {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(to, "to");
            effects = Collections.unmodifiableSet(
                    new TreeSet<>(Objects.requireNonNull(effects, "effects")));
        }

        public Transition(String from, String action, String to) {
            this(from, action, to, Set.of());
        }
    }

    private static final Comparator<Transition> TRANSITION_ORDER = Comparator
            .comparing(Transition::from)
            .thenComparing(Transition::action)
            .thenComparing(Transition::to)
            .thenComparing(t -> String.join(",", t.effects()));

    private final String initialState;
    private final Set<String> states;
    private final Set<String> finalStates;
    private final List<Transition> transitions;

    public ProtocolAutomaton(
            String initialState,
            Set<String> states,
            Set<String> finalStates,
            List<Transition> transitions) {
        this.initialState = Objects.requireNonNull(initialState, "initialState");
        this.states = Collections.unmodifiableSet(
                new TreeSet<>(Objects.requireNonNull(states, "states")));
        this.finalStates = Collections.unmodifiableSet(
                new TreeSet<>(Objects.requireNonNull(finalStates, "finalStates")));
        List<Transition> sorted = new ArrayList<>(Objects.requireNonNull(transitions, "transitions"));
        sorted.sort(TRANSITION_ORDER);
        this.transitions = Collections.unmodifiableList(sorted);
    }

    public String initialState() {
        return initialState;
    }

    public Set<String> states() {
        return states;
    }

    public Set<String> finalStates() {
        return finalStates;
    }

    public List<Transition> transitions() {
        return transitions;
    }

    /**
     * Validates structural and liveness invariants:
     * <ol type="a">
     *   <li>{@code initialState}, all {@code finalStates}, and all transition
     *       {@code from}/{@code to} endpoints belong to {@code states}</li>
     *   <li>{@code finalStates} is non-empty</li>
     *   <li>Every state in {@code states} is forward-reachable from {@code initialState}</li>
     *   <li>Every non-final state can reach at least one final state (reverse BFS non-deadend)</li>
     *   <li>No outgoing transitions leave any state in {@code finalStates}</li>
     * </ol>
     *
     * @return deterministic list of validation error messages (empty if valid)
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        // (a) Membership checks
        if (!states.contains(initialState)) {
            errors.add("Initial state '" + initialState + "' is not in declared states");
        }
        for (String fin : finalStates) {
            if (!states.contains(fin)) {
                errors.add("Final state '" + fin + "' is not in declared states");
            }
        }
        for (Transition t : transitions) {
            if (!states.contains(t.from())) {
                errors.add("Transition source '" + t.from() + "' is not in declared states");
            }
            if (!states.contains(t.to())) {
                errors.add("Transition target '" + t.to() + "' is not in declared states");
            }
        }

        // (b) Non-empty finalStates
        if (finalStates.isEmpty()) {
            errors.add("Protocol must declare at least one final state");
        }

        // (e) No outgoing transitions from final states
        for (Transition t : transitions) {
            if (finalStates.contains(t.from())) {
                errors.add("Final state '" + t.from()
                        + "' cannot have outgoing transition '" + t.action() + "'");
            }
        }

        // Build adjacency lists for forward and reverse BFS
        Map<String, List<String>> forwardAdj = new HashMap<>();
        Map<String, List<String>> reverseAdj = new HashMap<>();
        for (String s : states) {
            forwardAdj.put(s, new ArrayList<>());
            reverseAdj.put(s, new ArrayList<>());
        }
        for (Transition t : transitions) {
            if (forwardAdj.containsKey(t.from()) && states.contains(t.to())) {
                forwardAdj.get(t.from()).add(t.to());
            }
            if (reverseAdj.containsKey(t.to()) && states.contains(t.from())) {
                reverseAdj.get(t.to()).add(t.from());
            }
        }

        // (c) Forward BFS reachability from initialState
        if (states.contains(initialState)) {
            Set<String> reachable = bfs(List.of(initialState), forwardAdj);
            for (String s : states) {
                if (!reachable.contains(s)) {
                    errors.add("State '" + s + "' is unreachable from initial state '"
                            + initialState + "'");
                }
            }
        }

        // (d) Reverse BFS non-deadend check from finalStates
        if (!finalStates.isEmpty()) {
            List<String> validFinals = new ArrayList<>();
            for (String fin : finalStates) {
                if (states.contains(fin)) {
                    validFinals.add(fin);
                }
            }
            Set<String> canReachFinal = bfs(validFinals, reverseAdj);
            for (String s : states) {
                if (!finalStates.contains(s) && !canReachFinal.contains(s)) {
                    errors.add("Dead-end state '" + s + "' cannot reach any final state");
                }
            }
        }

        return Collections.unmodifiableList(errors);
    }

    public boolean isValid() {
        return validate().isEmpty();
    }

    /**
     * Checks trace inclusion {@code L(sub) <= L(sup)} with effect non-widening via
     * subset-construction product BFS.
     *
     * @return {@link Optional#empty()} if {@code sub} refines {@code sup}, or the
     *         shortest counterexample action trace if {@code sub} can reach an
     *         invalid transition, effect widening, or early-final state not
     *         permitted by {@code sup}.
     */
    public static Optional<List<String>> checkRefines(
            ProtocolAutomaton sub, ProtocolAutomaton sup) {
        Objects.requireNonNull(sub, "sub");
        Objects.requireNonNull(sup, "sup");

        Map<String, List<Transition>> subOut = groupByFrom(sub.transitions);
        Map<String, List<Transition>> supOut = groupByFrom(sup.transitions);

        record ProductState(String subState, Set<String> supStates) {}
        record QueueEntry(ProductState state, List<String> trace) {}

        ProductState start = new ProductState(
                sub.initialState,
                Collections.unmodifiableSet(new TreeSet<>(Set.of(sup.initialState))));
        Set<ProductState> visited = new HashSet<>();
        ArrayDeque<QueueEntry> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(new QueueEntry(start, List.of()));

        while (!queue.isEmpty()) {
            QueueEntry current = queue.removeFirst();
            ProductState ps = current.state();
            List<String> trace = current.trace();

            if (sub.finalStates.contains(ps.subState())) {
                boolean supAccepts = false;
                for (String q : ps.supStates()) {
                    if (sup.finalStates.contains(q)) {
                        supAccepts = true;
                        break;
                    }
                }
                if (!supAccepts) {
                    return Optional.of(trace);
                }
            }

            List<Transition> outgoing = subOut.getOrDefault(ps.subState(), List.of());
            for (Transition subT : outgoing) {
                TreeSet<String> nextSupStates = new TreeSet<>();
                for (String q : ps.supStates()) {
                    for (Transition supT : supOut.getOrDefault(q, List.of())) {
                        if (supT.action().equals(subT.action())
                                && supT.effects().containsAll(subT.effects())) {
                            nextSupStates.add(supT.to());
                        }
                    }
                }
                List<String> nextTrace = new ArrayList<>(trace.size() + 1);
                nextTrace.addAll(trace);
                nextTrace.add(subT.action());
                List<String> immutableNextTrace = Collections.unmodifiableList(nextTrace);

                if (nextSupStates.isEmpty()) {
                    return Optional.of(immutableNextTrace);
                }

                ProductState nextPs = new ProductState(
                        subT.to(), Collections.unmodifiableSet(nextSupStates));
                if (visited.add(nextPs)) {
                    queue.addLast(new QueueEntry(nextPs, immutableNextTrace));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Renders this automaton as a deterministic Mermaid {@code stateDiagram-v2}.
     */
    public String toMermaid() {
        StringBuilder sb = new StringBuilder();
        sb.append("stateDiagram-v2\n");
        sb.append("    [*] --> ").append(initialState).append('\n');
        for (Transition t : transitions) {
            sb.append("    ").append(t.from()).append(" --> ").append(t.to())
                    .append(" : ").append(t.action());
            if (!t.effects().isEmpty()) {
                sb.append(" [").append(String.join(", ", t.effects())).append(']');
            }
            sb.append('\n');
        }
        for (String fin : finalStates) {
            sb.append("    ").append(fin).append(" --> [*]\n");
        }
        return sb.toString();
    }

    private static Map<String, List<Transition>> groupByFrom(List<Transition> transitions) {
        Map<String, List<Transition>> grouped = new HashMap<>();
        for (Transition t : transitions) {
            grouped.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t);
        }
        return grouped;
    }

    private static Set<String> bfs(List<String> seeds, Map<String, List<String>> adj) {
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String seed : seeds) {
            if (visited.add(seed)) {
                queue.addLast(seed);
            }
        }
        while (!queue.isEmpty()) {
            String cur = queue.removeFirst();
            for (String next : adj.getOrDefault(cur, List.of())) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return visited;
    }
}
