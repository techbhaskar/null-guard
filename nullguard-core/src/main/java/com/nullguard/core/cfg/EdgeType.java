package com.nullguard.core.cfg;
/**
 * The kind of control transfer an edge represents.
 *
 * <p>Until the CFG rewrite only {@link #NORMAL} was ever constructed: the builder produced a
 * flat linear chain, so the analyser could not tell which branch a statement belonged to and
 * could not refine a variable's null state on a guard. The remaining constants existed only
 * as declarations.
 */
public enum EdgeType {

    /** Unconditional fall-through. */
    NORMAL,

    /** Taken when the source CONDITION node evaluates true. */
    TRUE_BRANCH,

    /** Taken when the source CONDITION node evaluates false. */
    FALSE_BRANCH,

    /**
     * Loop back-edge: from the end of a loop body (or the update clause) to the loop head.
     * Distinguished from NORMAL so that consumers can detect loops without a dominator
     * computation, and so a worklist analysis can report which edges forced re-iteration.
     */
    BACK_EDGE,

    /** Abrupt transfer caused by a thrown exception — into a catch clause, or out to EXIT. */
    EXCEPTION
}
