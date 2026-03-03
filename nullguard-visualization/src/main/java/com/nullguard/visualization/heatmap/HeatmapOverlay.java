package com.nullguard.visualization.heatmap;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HeatmapOverlay {

    public Map<String, String> riskColorMapping() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("LOW", "green");
        map.put("MEDIUM", "yellow");
        map.put("HIGH", "orange");
        map.put("CRITICAL", "red");
        return map;
    }
}
