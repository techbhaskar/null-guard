package com.nullguard.scoring.model;

/**
 * @deprecated SAFE TO DELETE — this file has no remaining references anywhere in the build.
 *
 * <p>Superseded by {@link com.nullguard.core.risk.RiskLevel}, which carries these same bands.
 * Its presence in {@code nullguard-scoring} forced {@code nullguard-visualization} to depend on
 * the scoring module purely to name an enum, and forced {@code ScoringTest} to fully-qualify one
 * of the two {@code RiskLevel} types just to compile. Note also that {@code from} re-hardcoded
 * the boundaries instead of reading {@code min}/{@code max}, so the declared band data and the
 * classification logic were free to drift apart.
 *
 * <p>Left in place only because files could not be deleted from the environment this change was
 * made in. Please {@code git rm} it.
 */
@Deprecated(forRemoval = true)
public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}
