package com.nullguard.analysis.risk;

/**
 * @deprecated SAFE TO DELETE — this file has no remaining references anywhere in the build.
 *
 * <p>Superseded by {@link com.nullguard.core.risk.RiskLevel}. This was one of two enums with the
 * same simple name and four identical constants; its bands lived separately in
 * {@code IntrinsicRiskCalculator} as 20 / 50 / 80 while the scoring module's copy used
 * 40 / 60 / 80, so the same score meant different things in different modules. Everything now
 * uses the single core enum.
 *
 * <p>Left in place only because files could not be deleted from the environment this change was
 * made in. Please {@code git rm} it.
 */
@Deprecated(forRemoval = true)
public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}
