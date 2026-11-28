package ngj.typechecker.refinement;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, acyclic hash-consed union-find congruence closure over pure
 * normalized terms for NGJ Tier 1 refinement checking and branch-scope reasoning.
 */
public final class EGraph {

    /**
     * Canonical E-node with operator name and canonicalized child e-class IDs.
     */
    public record ENode(String op, List<Integer> children) {
        public ENode {
            Objects.requireNonNull(op, "op");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }
    }

    private final List<Integer> parent;
    private final Map<ENode, Integer> hashCons;
    private final Map<Integer, Set<Integer>> parentsOfClass;
    private final Map<Integer, Set<ENode>> classNodes;

    public EGraph() {
        this.parent = new ArrayList<>();
        this.hashCons = new LinkedHashMap<>();
        this.parentsOfClass = new LinkedHashMap<>();
        this.classNodes = new LinkedHashMap<>();
    }

    private EGraph(
            List<Integer> parent,
            Map<ENode, Integer> hashCons,
            Map<Integer, Set<Integer>> parentsOfClass,
            Map<Integer, Set<ENode>> classNodes) {
        this.parent = parent;
        this.hashCons = hashCons;
        this.parentsOfClass = parentsOfClass;
        this.classNodes = classNodes;
    }

    /**
     * Creates an independent deep copy for branch scopes.
     */
    public EGraph copy() {
        List<Integer> parentCopy = new ArrayList<>(this.parent);
        Map<ENode, Integer> hashConsCopy = new LinkedHashMap<>(this.hashCons);
        Map<Integer, Set<Integer>> parentsCopy = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<Integer>> e : this.parentsOfClass.entrySet()) {
            parentsCopy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        Map<Integer, Set<ENode>> classNodesCopy = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<ENode>> e : this.classNodes.entrySet()) {
            classNodesCopy.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        return new EGraph(parentCopy, hashConsCopy, parentsCopy, classNodesCopy);
    }

    public int addVar(String name) {
        Objects.requireNonNull(name, "name");
        return add("var:" + name, List.of());
    }

    public int addConst(BigInteger value) {
        Objects.requireNonNull(value, "value");
        return add("const:" + value, List.of());
    }

    public int addConst(long value) {
        return addConst(BigInteger.valueOf(value));
    }

    public int addConst(boolean value) {
        return add("bool:" + value, List.of());
    }

    public int addApp(String op, int... childIds) {
        List<Integer> list = new ArrayList<>(childIds.length);
        for (int c : childIds) {
            list.add(c);
        }
        return add(op, list);
    }

    public int addCommutativeApp(String op, int left, int right) {
        int rLeft = representative(left);
        int rRight = representative(right);
        if (rLeft <= rRight) {
            return add(op, List.of(rLeft, rRight));
        }
        return add(op, List.of(rRight, rLeft));
    }

    public int add(String op, List<Integer> children) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(children, "children");
        List<Integer> canonChildren = new ArrayList<>(children.size());
        for (int child : children) {
            checkValidId(child);
            canonChildren.add(find(child));
        }
        ENode node = new ENode(op, canonChildren);
        Integer existing = hashCons.get(node);
        if (existing != null) {
            return find(existing);
        }

        int newId = parent.size();
        parent.add(newId);
        hashCons.put(node, newId);

        Set<ENode> nodes = new LinkedHashSet<>();
        nodes.add(node);
        classNodes.put(newId, nodes);
        parentsOfClass.put(newId, new LinkedHashSet<>());

        for (int childRep : canonChildren) {
            parentsOfClass.computeIfAbsent(childRep, k -> new LinkedHashSet<>()).add(newId);
        }
        return newId;
    }

    public int representative(int id) {
        checkValidId(id);
        return find(id);
    }

    public boolean areEqual(int id1, int id2) {
        checkValidId(id1);
        checkValidId(id2);
        return find(id1) == find(id2);
    }

    public Set<ENode> nodesInClass(int id) {
        checkValidId(id);
        int rep = find(id);
        Set<ENode> nodes = classNodes.get(rep);
        return nodes == null ? Set.of() : Collections.unmodifiableSet(nodes);
    }

    /**
     * Merges the equivalence classes of {@code id1} and {@code id2} and propagates
     * congruence closure deterministically.
     *
     * @return the canonical representative ID after merging
     */
    public int union(int id1, int id2) {
        checkValidId(id1);
        checkValidId(id2);
        ArrayDeque<int[]> worklist = new ArrayDeque<>();
        worklist.add(new int[]{id1, id2});

        while (!worklist.isEmpty()) {
            int[] pair = worklist.removeFirst();
            int r1 = find(pair[0]);
            int r2 = find(pair[1]);
            if (r1 == r2) {
                continue;
            }
            // Deterministic representative: smaller ID always becomes root (acyclic parent[x] <= x)
            int keep = Math.min(r1, r2);
            int merge = Math.max(r1, r2);
            parent.set(merge, keep);

            Set<ENode> mergeNodes = classNodes.remove(merge);
            Set<ENode> keepNodes = classNodes.computeIfAbsent(keep, k -> new LinkedHashSet<>());
            if (mergeNodes != null) {
                keepNodes.addAll(mergeNodes);
            }

            Set<Integer> affectedParents = new LinkedHashSet<>();
            Set<Integer> mergeParents = parentsOfClass.remove(merge);
            if (mergeParents != null) {
                affectedParents.addAll(mergeParents);
            }
            Set<Integer> keepParents = parentsOfClass.computeIfAbsent(keep, k -> new LinkedHashSet<>());
            affectedParents.addAll(keepParents);

            for (int parentClassId : affectedParents) {
                int pRep = find(parentClassId);
                Set<ENode> pNodes = classNodes.get(pRep);
                if (pNodes == null) {
                    continue;
                }
                List<ENode> snapshot = new ArrayList<>(pNodes);
                for (ENode oldNode : snapshot) {
                    ENode canonNode = canonicalize(oldNode);
                    if (!canonNode.equals(oldNode)) {
                        Integer existingClass = hashCons.get(canonNode);
                        hashCons.remove(oldNode);
                        pNodes.remove(oldNode);
                        pNodes.add(canonNode);
                        if (existingClass != null && find(existingClass) != pRep) {
                            worklist.addLast(new int[]{pRep, find(existingClass)});
                        } else {
                            hashCons.put(canonNode, pRep);
                        }
                        for (int childRep : canonNode.children()) {
                            parentsOfClass.computeIfAbsent(childRep, k -> new LinkedHashSet<>()).add(pRep);
                        }
                    }
                }
            }
        }
        return find(id1);
    }

    public int classCount() {
        return classNodes.size();
    }

    public int nodeCount() {
        return parent.size();
    }

    private ENode canonicalize(ENode node) {
        if (node.children().isEmpty()) {
            return node;
        }
        List<Integer> canon = new ArrayList<>(node.children().size());
        for (int c : node.children()) {
            canon.add(find(c));
        }
        return new ENode(node.op(), canon);
    }

    private int find(int id) {
        int cur = id;
        while (parent.get(cur) != cur) {
            cur = parent.get(cur);
        }
        int root = cur;
        cur = id;
        while (parent.get(cur) != root) {
            int next = parent.get(cur);
            parent.set(cur, root);
            cur = next;
        }
        return root;
    }

    private void checkValidId(int id) {
        if (id < 0 || id >= parent.size()) {
            throw new IllegalArgumentException("Invalid e-class ID: " + id);
        }
    }
}
