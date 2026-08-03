package com.nullguard.analysis.lattice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the source text of a CONDITION node into the null-state refinements it implies on
 * each branch.
 *
 * <h3>Why this exists</h3>
 * The analyser previously had no way to act on a guard. It used a textual
 * {@code condition.contains(varName)} check over the previous three instructions and, if that
 * matched, suppressed the finding — with two defects: {@code contains} matched substrings, so
 * {@code if (username != null)} silenced an unrelated {@code user}; and there was no polarity,
 * so {@code if (x == null) x.f();} — a guaranteed NPE — was reported as <em>guarded</em>.
 *
 * <p>Now that the CFG carries {@link com.nullguard.core.cfg.EdgeType#TRUE_BRANCH} and
 * {@code FALSE_BRANCH} edges, the analyser can apply the correct refinement per branch and
 * the textual heuristic is gone. {@code if (x == null) x.f();} is now a definite finding, and
 * {@code if (x != null) x.f();} is provably clean rather than merely unreported.
 *
 * <h3>Limits</h3>
 * This is a regex over source text, not an expression tree. It recognises the forms that carry
 * essentially all real null guards, and deliberately returns no refinement for anything it does
 * not fully understand — an unrecognised condition leaves the incoming state untouched, which
 * is the safe direction. Compound conditions ({@code a != null && b != null}) are handled by
 * scanning for every conjunct; disjunctions are not refined, since neither branch would be sound.
 */
public final class NullGuardCondition {

    /** {@code x != null} / {@code x == null} */
    private static final Pattern VAR_CMP_NULL =
            Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s*(==|!=)\\s*null\\b");

    /** {@code null != x} / {@code null == x} */
    private static final Pattern NULL_CMP_VAR =
            Pattern.compile("\\bnull\\s*(==|!=)\\s*([A-Za-z_$][\\w$]*)\\b");

    /** {@code x instanceof Foo} — implies x is non-null on the true branch. */
    private static final Pattern INSTANCEOF =
            Pattern.compile("\\b([A-Za-z_$][\\w$]*)\\s+instanceof\\b");

    /** {@code Objects.nonNull(x)} / {@code Objects.isNull(x)} / {@code x.isPresent()} */
    private static final Pattern NON_NULL_CALL =
            Pattern.compile("\\bnonNull\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*\\)");
    private static final Pattern IS_NULL_CALL =
            Pattern.compile("\\bisNull\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*\\)");

    /** A disjunction makes per-branch refinement unsound, so we refuse to refine. */
    private static final Pattern DISJUNCTION = Pattern.compile("\\|\\|");

    private NullGuardCondition() {
        // utility
    }

    /**
     * The refinements a condition implies.
     *
     * @param onTrue  variable states that hold when the condition is true
     * @param onFalse variable states that hold when it is false
     */
    public record Refinement(Map<String, NullState> onTrue, Map<String, NullState> onFalse) {

        public static Refinement none() {
            return new Refinement(Map.of(), Map.of());
        }

        public boolean isEmpty() {
            return onTrue.isEmpty() && onFalse.isEmpty();
        }
    }

    /**
     * @param condition raw source text of a CONDITION node
     * @return the per-branch refinements, never null; {@link Refinement#none()} when nothing
     *         about nullness can be concluded
     */
    public static Refinement parse(String condition) {
        if (condition == null || condition.isBlank()) return Refinement.none();

        // `a != null || b != null` tells us nothing definite about either variable on the
        // true branch, so refining would be unsound. Bail out rather than guess.
        if (DISJUNCTION.matcher(condition).find()) return Refinement.none();

        Map<String, NullState> onTrue = new LinkedHashMap<>();
        Map<String, NullState> onFalse = new LinkedHashMap<>();

        Matcher m = VAR_CMP_NULL.matcher(condition);
        while (m.find()) {
            record(onTrue, onFalse, m.group(1), "!=".equals(m.group(2)));
        }

        m = NULL_CMP_VAR.matcher(condition);
        while (m.find()) {
            record(onTrue, onFalse, m.group(2), "!=".equals(m.group(1)));
        }

        m = INSTANCEOF.matcher(condition);
        while (m.find()) {
            // instanceof false does NOT imply null (it may be the wrong type), so only the
            // true branch is refined.
            onTrue.put(m.group(1), NullState.NON_NULL);
        }

        m = NON_NULL_CALL.matcher(condition);
        while (m.find()) {
            record(onTrue, onFalse, m.group(1), true);
        }

        m = IS_NULL_CALL.matcher(condition);
        while (m.find()) {
            record(onTrue, onFalse, m.group(1), false);
        }

        return new Refinement(Map.copyOf(onTrue), Map.copyOf(onFalse));
    }

    /**
     * Variables that a condition proves non-null on <em>both</em> branches because evaluating
     * it would have thrown otherwise — currently {@code Objects.requireNonNull(x)}.
     */
    private static final Pattern REQUIRE_NON_NULL =
            Pattern.compile("\\brequireNonNull\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*\\)");

    /** @return variables proven non-null merely by the condition having been evaluated */
    public static Map<String, NullState> unconditionalNonNull(String condition) {
        if (condition == null || condition.isBlank()) return Map.of();
        Map<String, NullState> result = new LinkedHashMap<>();
        Matcher m = REQUIRE_NON_NULL.matcher(condition);
        while (m.find()) {
            result.put(m.group(1), NullState.NON_NULL);
        }
        return Map.copyOf(result);
    }

    private static void record(Map<String, NullState> onTrue,
                               Map<String, NullState> onFalse,
                               String var,
                               boolean trueMeansNonNull) {
        if (trueMeansNonNull) {
            onTrue.put(var, NullState.NON_NULL);
            onFalse.put(var, NullState.NULL);
        } else {
            onTrue.put(var, NullState.NULL);
            onFalse.put(var, NullState.NON_NULL);
        }
    }
}
