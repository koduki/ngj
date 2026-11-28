package ngj.typechecker.refinement;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Exact {@link BigInteger}-backed interval and difference-bound abstract domain
 * for NGJ Tier 1 refinement type checking.
 *
 * <p>Supports:
 * <ul>
 *   <li>32-bit {@code Int} ({@code [-2^31, 2^31 - 1]}) and 64-bit {@code Long}
 *       ({@code [-2^63, 2^63 - 1]}) bounds</li>
 *   <li>Open upper bounds ({@code [lo..]}), half-open bounds ({@code [lo..<hi]}),
 *       and unbounded intervals</li>
 *   <li>Exact interval arithmetic ({@code +}, {@code -}, {@code *}, {@code /},
 *       {@code %}, unary {@code -})</li>
 *   <li>Relational / difference bounds ({@code x - y <= c}, {@code x <= sym + c})</li>
 *   <li>Lattice operations: {@code join}, {@code meet}, and {@code widen}</li>
 * </ul>
 */
public final class IntervalDomain {

    public static final BigInteger INT_MIN = BigInteger.valueOf(Integer.MIN_VALUE);
    public static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    public static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    public static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    public enum BitWidth {
        INT32,
        LONG64,
        UNBOUNDED
    }

    /**
     * Closed, half-open, open-ended, or empty interval over {@link BigInteger}.
     * {@code lower == null} represents {@code -infinity}; {@code upper == null}
     * represents {@code +infinity}.
     */
    public static final class Interval {
        private static final Interval EMPTY = new Interval(true, null, null);
        private static final Interval TOP = new Interval(false, null, null);
        private static final Interval INT32_RANGE = new Interval(false, INT_MIN, INT_MAX);
        private static final Interval LONG64_RANGE = new Interval(false, LONG_MIN, LONG_MAX);

        private final boolean empty;
        private final BigInteger lower;
        private final BigInteger upper;

        private Interval(boolean empty, BigInteger lower, BigInteger upper) {
            this.empty = empty;
            this.lower = lower;
            this.upper = upper;
        }

        public static Interval empty() {
            return EMPTY;
        }

        public static Interval top() {
            return TOP;
        }

        public static Interval int32() {
            return INT32_RANGE;
        }

        public static Interval long64() {
            return LONG64_RANGE;
        }

        public static Interval forWidth(BitWidth width) {
            return switch (Objects.requireNonNull(width, "width")) {
                case INT32 -> INT32_RANGE;
                case LONG64 -> LONG64_RANGE;
                case UNBOUNDED -> TOP;
            };
        }

        public static Interval singleton(long value) {
            BigInteger v = BigInteger.valueOf(value);
            return new Interval(false, v, v);
        }

        public static Interval singleton(BigInteger value) {
            Objects.requireNonNull(value, "value");
            return new Interval(false, value, value);
        }

        public static Interval of(long lo, long hi) {
            return closed(BigInteger.valueOf(lo), BigInteger.valueOf(hi));
        }

        public static Interval of(BigInteger lo, BigInteger hi) {
            return closed(lo, hi);
        }

        public static Interval closed(BigInteger lo, BigInteger hi) {
            if (lo != null && hi != null && lo.compareTo(hi) > 0) {
                return EMPTY;
            }
            if (lo == null && hi == null) {
                return TOP;
            }
            return new Interval(false, lo, hi);
        }

        public static Interval halfOpen(long lo, long hiExclusive) {
            return halfOpen(BigInteger.valueOf(lo), BigInteger.valueOf(hiExclusive));
        }

        public static Interval halfOpen(BigInteger lo, BigInteger hiExclusive) {
            Objects.requireNonNull(hiExclusive, "hiExclusive");
            return closed(lo, hiExclusive.subtract(BigInteger.ONE));
        }

        public static Interval atLeast(long lo) {
            return atLeast(BigInteger.valueOf(lo));
        }

        public static Interval atLeast(BigInteger lo) {
            Objects.requireNonNull(lo, "lo");
            return new Interval(false, lo, null);
        }

        public static Interval atMost(long hi) {
            return atMost(BigInteger.valueOf(hi));
        }

        public static Interval atMost(BigInteger hi) {
            Objects.requireNonNull(hi, "hi");
            return new Interval(false, null, hi);
        }

        public static Interval lessThan(BigInteger hiExclusive) {
            Objects.requireNonNull(hiExclusive, "hiExclusive");
            return new Interval(false, null, hiExclusive.subtract(BigInteger.ONE));
        }

        public boolean isEmpty() {
            return empty;
        }

        public boolean isUnbounded() {
            return !empty && lower == null && upper == null;
        }

        public BigInteger lower() {
            return lower;
        }

        public BigInteger upper() {
            return upper;
        }

        public boolean isSingleton() {
            return !empty && lower != null && upper != null && lower.equals(upper);
        }

        public Optional<BigInteger> singletonValue() {
            return isSingleton() ? Optional.of(lower) : Optional.empty();
        }

        public boolean contains(long value) {
            return contains(BigInteger.valueOf(value));
        }

        public boolean contains(BigInteger value) {
            Objects.requireNonNull(value, "value");
            if (empty) {
                return false;
            }
            if (lower != null && value.compareTo(lower) < 0) {
                return false;
            }
            if (upper != null && value.compareTo(upper) > 0) {
                return false;
            }
            return true;
        }

        public boolean isSubsetOf(Interval other) {
            Objects.requireNonNull(other, "other");
            if (this.empty) {
                return true;
            }
            if (other.empty) {
                return false;
            }
            boolean loOk = other.lower == null
                    || (this.lower != null && this.lower.compareTo(other.lower) >= 0);
            boolean hiOk = other.upper == null
                    || (this.upper != null && this.upper.compareTo(other.upper) <= 0);
            return loOk && hiOk;
        }

        public boolean intersects(Interval other) {
            return !meet(other).isEmpty();
        }

        public boolean fitsIn(BitWidth width) {
            if (empty) {
                return false;
            }
            return isSubsetOf(forWidth(width));
        }

        public Interval clamp(BitWidth width) {
            return meet(forWidth(width));
        }

        public Interval join(Interval other) {
            Objects.requireNonNull(other, "other");
            if (this.empty) {
                return other;
            }
            if (other.empty) {
                return this;
            }
            BigInteger newLo = minLower(this.lower, other.lower);
            BigInteger newHi = maxUpper(this.upper, other.upper);
            return closed(newLo, newHi);
        }

        public Interval meet(Interval other) {
            Objects.requireNonNull(other, "other");
            if (this.empty || other.empty) {
                return EMPTY;
            }
            BigInteger newLo = maxLower(this.lower, other.lower);
            BigInteger newHi = minUpper(this.upper, other.upper);
            return closed(newLo, newHi);
        }

        public Interval widen(Interval next) {
            Objects.requireNonNull(next, "next");
            if (this.empty) {
                return next;
            }
            if (next.empty) {
                return this;
            }
            BigInteger newLo = (this.lower == null || next.lower == null
                    || next.lower.compareTo(this.lower) < 0) ? null : this.lower;
            BigInteger newHi = (this.upper == null || next.upper == null
                    || next.upper.compareTo(this.upper) > 0) ? null : this.upper;
            return closed(newLo, newHi);
        }

        public Interval widenBounded(Interval next, BitWidth width) {
            return widen(next).clamp(width);
        }

        public Interval negate() {
            if (empty) {
                return EMPTY;
            }
            BigInteger newLo = upper == null ? null : upper.negate();
            BigInteger newHi = lower == null ? null : lower.negate();
            return closed(newLo, newHi);
        }

        public Interval add(Interval other) {
            Objects.requireNonNull(other, "other");
            if (this.empty || other.empty) {
                return EMPTY;
            }
            BigInteger newLo = (this.lower == null || other.lower == null)
                    ? null : this.lower.add(other.lower);
            BigInteger newHi = (this.upper == null || other.upper == null)
                    ? null : this.upper.add(other.upper);
            return closed(newLo, newHi);
        }

        public Interval sub(Interval other) {
            return add(other.negate());
        }

        public Interval mul(Interval other) {
            Objects.requireNonNull(other, "other");
            if (this.empty || other.empty) {
                return EMPTY;
            }
            if ((this.isSingleton() && BigInteger.ZERO.equals(this.lower))
                    || (other.isSingleton() && BigInteger.ZERO.equals(other.lower))) {
                return singleton(BigInteger.ZERO);
            }
            ExtVal[] lhs = {ExtVal.lower(this.lower), ExtVal.upper(this.upper)};
            ExtVal[] rhs = {ExtVal.lower(other.lower), ExtVal.upper(other.upper)};
            ExtVal min = null;
            ExtVal max = null;
            for (ExtVal a : lhs) {
                for (ExtVal b : rhs) {
                    ExtVal prod = a.mul(b);
                    if (min == null || prod.compareTo(min) < 0) {
                        min = prod;
                    }
                    if (max == null || prod.compareTo(max) > 0) {
                        max = prod;
                    }
                }
            }
            return closed(min.toFiniteOrNull(), max.toFiniteOrNull());
        }

        public Interval div(Interval other) {
            Objects.requireNonNull(other, "other");
            if (this.empty || other.empty) {
                return EMPTY;
            }
            if (other.isSingleton() && BigInteger.ZERO.equals(other.lower)) {
                return EMPTY;
            }
            if (other.contains(BigInteger.ZERO)) {
                Interval negPart = other.meet(atMost(BigInteger.valueOf(-1)));
                Interval posPart = other.meet(atLeast(BigInteger.ONE));
                return divNonZero(negPart).join(divNonZero(posPart));
            }
            return divNonZero(other);
        }

        private Interval divNonZero(Interval divisor) {
            if (this.empty || divisor.empty) {
                return EMPTY;
            }
            ExtVal[] lhs = {ExtVal.lower(this.lower), ExtVal.upper(this.upper)};
            ExtVal[] rhs = {ExtVal.lower(divisor.lower), ExtVal.upper(divisor.upper)};
            ExtVal min = null;
            ExtVal max = null;
            for (ExtVal a : lhs) {
                for (ExtVal b : rhs) {
                    ExtVal quot = a.div(b);
                    if (min == null || quot.compareTo(min) < 0) {
                        min = quot;
                    }
                    if (max == null || quot.compareTo(max) > 0) {
                        max = quot;
                    }
                }
            }
            return closed(min.toFiniteOrNull(), max.toFiniteOrNull());
        }

        /**
         * Java/NGJ truncating remainder {@code this % other} (sign follows dividend).
         */
        public Interval rem(Interval other) {
            Objects.requireNonNull(other, "other");
            if (this.empty || other.empty) {
                return EMPTY;
            }
            if (other.isSingleton() && BigInteger.ZERO.equals(other.lower)) {
                return EMPTY;
            }
            if (this.isSingleton() && other.isSingleton()) {
                return singleton(this.lower.remainder(other.lower));
            }
            Interval nonZeroOther = other;
            if (other.contains(BigInteger.ZERO)) {
                Interval neg = other.meet(atMost(BigInteger.valueOf(-1)));
                Interval pos = other.meet(atLeast(BigInteger.ONE));
                return rem(neg).join(rem(pos));
            }
            BigInteger maxDivisorAbs = maxAbs(nonZeroOther);
            BigInteger maxRem = maxDivisorAbs == null ? null : maxDivisorAbs.subtract(BigInteger.ONE);

            BigInteger resLo;
            BigInteger resHi;
            if (this.lower != null && this.lower.signum() >= 0) {
                resLo = BigInteger.ZERO;
                resHi = minUpper(this.upper, maxRem);
            } else if (this.upper != null && this.upper.signum() <= 0) {
                BigInteger negMaxRem = maxRem == null ? null : maxRem.negate();
                resLo = maxLower(this.lower, negMaxRem);
                resHi = BigInteger.ZERO;
            } else {
                BigInteger negMaxRem = maxRem == null ? null : maxRem.negate();
                resLo = maxLower(this.lower, negMaxRem);
                resHi = minUpper(this.upper, maxRem);
            }
            return closed(resLo, resHi);
        }

        private static BigInteger maxAbs(Interval iv) {
            if (iv.lower == null || iv.upper == null) {
                return null;
            }
            return iv.lower.abs().max(iv.upper.abs());
        }

        private static BigInteger minLower(BigInteger a, BigInteger b) {
            if (a == null || b == null) {
                return null;
            }
            return a.min(b);
        }

        private static BigInteger maxLower(BigInteger a, BigInteger b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.max(b);
        }

        private static BigInteger maxUpper(BigInteger a, BigInteger b) {
            if (a == null || b == null) {
                return null;
            }
            return a.max(b);
        }

        private static BigInteger minUpper(BigInteger a, BigInteger b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.min(b);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Interval other)) {
                return false;
            }
            return empty == other.empty
                    && Objects.equals(lower, other.lower)
                    && Objects.equals(upper, other.upper);
        }

        @Override
        public int hashCode() {
            return Objects.hash(empty, lower, upper);
        }

        @Override
        public String toString() {
            if (empty) {
                return "EMPTY";
            }
            String loStr = lower == null ? "-inf" : lower.toString();
            String hiStr = upper == null ? "+inf" : upper.toString();
            return "[" + loStr + ", " + hiStr + "]";
        }
    }

    private record ExtVal(int infSign, BigInteger finite) implements Comparable<ExtVal> {
        static ExtVal lower(BigInteger v) {
            return v == null ? new ExtVal(-1, null) : new ExtVal(0, v);
        }

        static ExtVal upper(BigInteger v) {
            return v == null ? new ExtVal(1, null) : new ExtVal(0, v);
        }

        static ExtVal of(BigInteger v) {
            return new ExtVal(0, v);
        }

        int signum() {
            return infSign != 0 ? infSign : finite.signum();
        }

        BigInteger toFiniteOrNull() {
            return infSign == 0 ? finite : null;
        }

        ExtVal mul(ExtVal other) {
            if (this.signum() == 0 || other.signum() == 0) {
                return of(BigInteger.ZERO);
            }
            if (this.infSign != 0 || other.infSign != 0) {
                return new ExtVal(this.signum() * other.signum(), null);
            }
            return of(this.finite.multiply(other.finite));
        }

        ExtVal div(ExtVal divisor) {
            if (divisor.infSign != 0) {
                if (this.infSign != 0) {
                    return new ExtVal(this.infSign * divisor.infSign, null);
                }
                return of(BigInteger.ZERO);
            }
            if (this.infSign != 0) {
                return new ExtVal(this.infSign * divisor.finite.signum(), null);
            }
            return of(this.finite.divide(divisor.finite));
        }

        @Override
        public int compareTo(ExtVal other) {
            if (this.infSign != other.infSign) {
                if (this.infSign == -1 || other.infSign == 1) {
                    return -1;
                }
                if (this.infSign == 1 || other.infSign == -1) {
                    return 1;
                }
            }
            if (this.infSign != 0) {
                return 0;
            }
            return this.finite.compareTo(other.finite);
        }
    }

    private final TreeMap<String, Interval> intervals;
    private final TreeMap<String, TreeMap<String, BigInteger>> diffBounds;
    private boolean bottom;

    public IntervalDomain() {
        this.intervals = new TreeMap<>();
        this.diffBounds = new TreeMap<>();
        this.bottom = false;
    }

    private IntervalDomain(
            boolean bottom,
            TreeMap<String, Interval> intervals,
            TreeMap<String, TreeMap<String, BigInteger>> diffBounds) {
        this.bottom = bottom;
        this.intervals = intervals;
        this.diffBounds = diffBounds;
    }

    public static IntervalDomain bottomDomain() {
        return new IntervalDomain(true, new TreeMap<>(), new TreeMap<>());
    }

    public boolean isBottom() {
        return bottom;
    }

    public boolean isFeasible() {
        return !bottom;
    }

    public IntervalDomain copy() {
        TreeMap<String, TreeMap<String, BigInteger>> diffCopy = new TreeMap<>();
        for (Map.Entry<String, TreeMap<String, BigInteger>> entry : diffBounds.entrySet()) {
            diffCopy.put(entry.getKey(), new TreeMap<>(entry.getValue()));
        }
        return new IntervalDomain(bottom, new TreeMap<>(intervals), diffCopy);
    }

    public Interval getInterval(String var) {
        Objects.requireNonNull(var, "var");
        if (bottom) {
            return Interval.empty();
        }
        return intervals.getOrDefault(var, Interval.top());
    }

    public Map<String, Interval> allIntervals() {
        return Collections.unmodifiableMap(intervals);
    }

    public void assignInterval(String var, Interval interval) {
        Objects.requireNonNull(var, "var");
        Objects.requireNonNull(interval, "interval");
        if (bottom) {
            return;
        }
        removeDifferenceConstraintsFor(var);
        if (interval.isEmpty()) {
            setBottom();
            return;
        }
        if (interval.isUnbounded()) {
            intervals.remove(var);
        } else {
            intervals.put(var, interval);
        }
    }

    public void constrainInterval(String var, Interval constraint) {
        Objects.requireNonNull(var, "var");
        Objects.requireNonNull(constraint, "constraint");
        if (bottom) {
            return;
        }
        Interval tightened = getInterval(var).meet(constraint);
        if (tightened.isEmpty()) {
            setBottom();
            return;
        }
        if (tightened.isUnbounded()) {
            intervals.remove(var);
        } else {
            intervals.put(var, tightened);
        }
        propagateUnaryToDiff(var);
    }

    /**
     * Adds relational constraint {@code x - y <= c} (equivalently {@code x <= y + c}).
     */
    public void addDifferenceBound(String x, String y, BigInteger c) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        Objects.requireNonNull(c, "c");
        if (bottom) {
            return;
        }
        if (x.equals(y)) {
            if (c.signum() < 0) {
                setBottom();
            }
            return;
        }
        TreeMap<String, BigInteger> row = diffBounds.computeIfAbsent(x, k -> new TreeMap<>());
        BigInteger existing = row.get(y);
        if (existing == null || c.compareTo(existing) < 0) {
            row.put(y, c);
        }
        closeAndPropagate();
    }

    /**
     * Adds {@code x <= sym + c}.
     */
    public void addSymbolicUpperBound(String x, String sym, BigInteger c) {
        addDifferenceBound(x, sym, c);
    }

    /**
     * Adds {@code sym + c <= x} (equivalently {@code sym - x <= -c}).
     */
    public void addSymbolicLowerBound(String x, String sym, BigInteger c) {
        addDifferenceBound(sym, x, c.negate());
    }

    public Optional<BigInteger> getDifferenceBound(String x, String y) {
        Objects.requireNonNull(x, "x");
        Objects.requireNonNull(y, "y");
        if (bottom) {
            return Optional.empty();
        }
        if (x.equals(y)) {
            return Optional.of(BigInteger.ZERO);
        }
        TreeMap<String, BigInteger> row = diffBounds.get(x);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(row.get(y));
    }

    private void removeDifferenceConstraintsFor(String var) {
        diffBounds.remove(var);
        for (TreeMap<String, BigInteger> row : diffBounds.values()) {
            row.remove(var);
        }
    }

    private void propagateUnaryToDiff(String var) {
        if (bottom) {
            return;
        }
        Interval iv = getInterval(var);
        if (iv.isEmpty()) {
            setBottom();
            return;
        }
        TreeMap<String, BigInteger> outRow = diffBounds.get(var);
        if (outRow != null) {
            for (Map.Entry<String, BigInteger> entry : new ArrayList<>(outRow.entrySet())) {
                String y = entry.getKey();
                BigInteger c = entry.getValue();
                Interval yIv = getInterval(y);
                if (yIv.upper() != null) {
                    Interval upperBound = Interval.atMost(yIv.upper().add(c));
                    Interval nextX = getInterval(var).meet(upperBound);
                    if (nextX.isEmpty()) {
                        setBottom();
                        return;
                    }
                    if (!nextX.isUnbounded()) {
                        intervals.put(var, nextX);
                    }
                }
                if (iv.lower() != null) {
                    Interval lowerBoundY = Interval.atLeast(iv.lower().subtract(c));
                    Interval nextY = yIv.meet(lowerBoundY);
                    if (nextY.isEmpty()) {
                        setBottom();
                        return;
                    }
                    if (!nextY.isUnbounded()) {
                        intervals.put(y, nextY);
                    }
                }
            }
        }
    }

    private void closeAndPropagate() {
        if (bottom || diffBounds.isEmpty()) {
            return;
        }
        List<String> nodes = collectDiffNodes();
        // Floyd-Warshall transitive closure over difference bounds
        for (String k : nodes) {
            TreeMap<String, BigInteger> kRow = diffBounds.get(k);
            if (kRow == null) {
                continue;
            }
            for (String i : nodes) {
                TreeMap<String, BigInteger> iRow = diffBounds.get(i);
                if (iRow == null) {
                    continue;
                }
                BigInteger dik = iRow.get(k);
                if (dik == null) {
                    continue;
                }
                for (String j : nodes) {
                    BigInteger dkj = kRow.get(j);
                    if (dkj == null) {
                        continue;
                    }
                    BigInteger cand = dik.add(dkj);
                    if (i.equals(j)) {
                        if (cand.signum() < 0) {
                            setBottom();
                            return;
                        }
                        continue;
                    }
                    BigInteger cur = iRow.get(j);
                    if (cur == null || cand.compareTo(cur) < 0) {
                        iRow.put(j, cand);
                    }
                }
            }
        }
        for (String v : nodes) {
            propagateUnaryToDiff(v);
            if (bottom) {
                return;
            }
        }
    }

    private List<String> collectDiffNodes() {
        TreeMap<String, Boolean> seen = new TreeMap<>();
        for (Map.Entry<String, TreeMap<String, BigInteger>> e : diffBounds.entrySet()) {
            seen.put(e.getKey(), Boolean.TRUE);
            for (String target : e.getValue().keySet()) {
                seen.put(target, Boolean.TRUE);
            }
        }
        return new ArrayList<>(seen.keySet());
    }

    public IntervalDomain join(IntervalDomain other) {
        Objects.requireNonNull(other, "other");
        if (this.bottom) {
            return other.copy();
        }
        if (other.bottom) {
            return this.copy();
        }
        TreeMap<String, Interval> joinedIntervals = new TreeMap<>();
        for (String var : this.intervals.keySet()) {
            if (other.intervals.containsKey(var)) {
                Interval j = this.intervals.get(var).join(other.intervals.get(var));
                if (!j.isUnbounded()) {
                    joinedIntervals.put(var, j);
                }
            }
        }
        TreeMap<String, TreeMap<String, BigInteger>> joinedDiffs = new TreeMap<>();
        for (Map.Entry<String, TreeMap<String, BigInteger>> entry : this.diffBounds.entrySet()) {
            String x = entry.getKey();
            TreeMap<String, BigInteger> otherRow = other.diffBounds.get(x);
            if (otherRow == null) {
                continue;
            }
            for (Map.Entry<String, BigInteger> col : entry.getValue().entrySet()) {
                String y = col.getKey();
                BigInteger otherC = otherRow.get(y);
                if (otherC != null) {
                    joinedDiffs.computeIfAbsent(x, k -> new TreeMap<>())
                            .put(y, col.getValue().max(otherC));
                }
            }
        }
        return new IntervalDomain(false, joinedIntervals, joinedDiffs);
    }

    public IntervalDomain meet(IntervalDomain other) {
        Objects.requireNonNull(other, "other");
        if (this.bottom || other.bottom) {
            return bottomDomain();
        }
        IntervalDomain result = this.copy();
        for (Map.Entry<String, Interval> entry : other.intervals.entrySet()) {
            result.constrainInterval(entry.getKey(), entry.getValue());
            if (result.isBottom()) {
                return result;
            }
        }
        for (Map.Entry<String, TreeMap<String, BigInteger>> entry : other.diffBounds.entrySet()) {
            String x = entry.getKey();
            for (Map.Entry<String, BigInteger> col : entry.getValue().entrySet()) {
                result.addDifferenceBound(x, col.getKey(), col.getValue());
                if (result.isBottom()) {
                    return result;
                }
            }
        }
        return result;
    }

    public IntervalDomain widen(IntervalDomain next) {
        Objects.requireNonNull(next, "next");
        if (this.bottom) {
            return next.copy();
        }
        if (next.bottom) {
            return this.copy();
        }
        TreeMap<String, Interval> widenedIntervals = new TreeMap<>();
        for (String var : this.intervals.keySet()) {
            if (next.intervals.containsKey(var)) {
                Interval w = this.intervals.get(var).widen(next.intervals.get(var));
                if (!w.isUnbounded()) {
                    widenedIntervals.put(var, w);
                }
            }
        }
        TreeMap<String, TreeMap<String, BigInteger>> widenedDiffs = new TreeMap<>();
        for (Map.Entry<String, TreeMap<String, BigInteger>> entry : this.diffBounds.entrySet()) {
            String x = entry.getKey();
            TreeMap<String, BigInteger> nextRow = next.diffBounds.get(x);
            if (nextRow == null) {
                continue;
            }
            for (Map.Entry<String, BigInteger> col : entry.getValue().entrySet()) {
                String y = col.getKey();
                BigInteger nextC = nextRow.get(y);
                if (nextC != null && nextC.compareTo(col.getValue()) <= 0) {
                    widenedDiffs.computeIfAbsent(x, k -> new TreeMap<>()).put(y, col.getValue());
                }
            }
        }
        return new IntervalDomain(false, widenedIntervals, widenedDiffs);
    }

    private void setBottom() {
        this.bottom = true;
        this.intervals.clear();
        this.diffBounds.clear();
    }
}
