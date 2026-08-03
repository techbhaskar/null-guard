package com.nullguard.visualization.heatmap;

import com.nullguard.core.risk.RiskLevel;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps risk bands to graph colours.
 *
 * <p>Previously a {@code Map<String, String>} rebuilt on every call, keyed by
 * {@code riskLevel.name()}. String keys meant a new {@link RiskLevel} constant silently
 * rendered as {@code "white"} instead of failing, and {@code DotGraphExporter} constructed a
 * fresh overlay per export. The mapping is now a static {@link EnumMap}, so adding a constant
 * without a colour is a visible gap rather than a silent default.
 */
public final class HeatmapOverlay {

    private static final Map<RiskLevel, String> COLORS;

    static {
        EnumMap<RiskLevel, String> colors = new EnumMap<>(RiskLevel.class);
        colors.put(RiskLevel.LOW, "green");
        colors.put(RiskLevel.MEDIUM, "yellow");
        colors.put(RiskLevel.HIGH, "orange");
        colors.put(RiskLevel.CRITICAL, "red");
        COLORS = Collections.unmodifiableMap(colors);
    }

    /** Fallback for a band with no colour assigned. */
    public static final String DEFAULT_COLOR = "white";

    /** @return the colour for a band, or {@link #DEFAULT_COLOR} if unmapped or null */
    public String colorFor(RiskLevel level) {
        return level == null ? DEFAULT_COLOR : COLORS.getOrDefault(level, DEFAULT_COLOR);
    }

    /**
     * @return band name to colour, for callers that serialise the legend
     * @deprecated prefer {@link #colorFor(RiskLevel)}; string keys reintroduce the silent
     *             "unknown band renders white" behaviour this class was changed to avoid.
     */
    @Deprecated
    public Map<String, String> riskColorMapping() {
        Map<String, String> byName = new LinkedHashMap<>();
        COLORS.forEach((level, color) -> byName.put(level.name(), color));
        return Collections.unmodifiableMap(byName);
    }
}
