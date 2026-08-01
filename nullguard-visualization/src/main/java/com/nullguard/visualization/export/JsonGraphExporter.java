package com.nullguard.visualization.export;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.visualization.model.PropagationGraph;
import com.nullguard.visualization.exception.VisualizationException;

import java.util.LinkedHashMap;
import java.util.Map;

public class JsonGraphExporter {
    private final ObjectMapper mapper;

    public JsonGraphExporter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        // ProjectRiskSummary uses Double.NaN as the "analysis produced no data" sentinel.
        // Bare NaN is not legal JSON and would make the exported report unparseable by every
        // standard consumer, so emit it as the quoted token "NaN" instead.
        this.mapper.enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature());
    }

    public String export(PropagationGraph graph, ProjectRiskSummary summary) {
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("summary", summary);
            output.put("graph", graph);
            return mapper.writeValueAsString(output);
        } catch (Exception e) {
            throw new VisualizationException("Failed to export JSON", e);
        }
    }
}
