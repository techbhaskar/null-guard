package com.nullguard.visualization.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.SerializableString;
import com.fasterxml.jackson.core.io.CharacterEscapes;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nullguard.scoring.model.ProjectRiskSummary;
import com.nullguard.visualization.model.PropagationGraph;
import com.nullguard.visualization.exception.VisualizationException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serialises the report model. Large projects must use the streaming {@code write*} methods:
 * building the whole report as one indented String exhausts the heap on real services.
 */
public class JsonGraphExporter {
    private final ObjectMapper mapper;
    private final ObjectMapper compact;

    public JsonGraphExporter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        // ProjectRiskSummary uses Double.NaN as the "analysis produced no data" sentinel.
        // Bare NaN is not legal JSON and would make the exported report unparseable by every
        // standard consumer, so emit it as the quoted token "NaN" instead.
        this.mapper.enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature());
        this.compact = new ObjectMapper();
        this.compact.enable(JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature());
        this.compact.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
    }

    /** Indented in-memory export. Kept for small graphs and tests; prefer {@link #write}. */
    public String export(PropagationGraph graph, ProjectRiskSummary summary) {
        try {
            return mapper.writeValueAsString(report(graph, summary));
        } catch (Exception e) {
            throw new VisualizationException("Failed to export JSON", e);
        }
    }

    /** The ordered top-level report object; callers may add sections before writing. */
    public static Map<String, Object> report(PropagationGraph graph, ProjectRiskSummary summary) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("summary", summary);
        output.put("graph", graph);
        return output;
    }

    /** Streams compact JSON without materialising the document. The stream is left open. */
    public void write(Object report, OutputStream out) {
        try { compact.writeValue(out, report); }
        catch (IOException e) { throw new VisualizationException("Failed to export JSON", e); }
    }

    /** Compact JSON as a String; only for callers that genuinely need the whole document. */
    public String toCompactString(Object report) {
        try { return compact.writeValueAsString(report); }
        catch (IOException e) { throw new VisualizationException("Failed to export JSON", e); }
    }

    /**
     * Streams JSON that is safe to embed inside an HTML {@code <script>} element:
     * {@code <}, {@code >}, {@code &}, U+2028 and U+2029 are written as unicode escapes,
     * so project strings such as {@code </script>} cannot terminate the script block.
     */
    public void writeForScript(Object report, Writer out) {
        try { compact.writer().with(new ScriptEscapes()).writeValue(out, report); }
        catch (IOException e) { throw new VisualizationException("Failed to export JSON", e); }
    }

    private static final class ScriptEscapes extends CharacterEscapes {
        private static final SerializableString LS = new com.fasterxml.jackson.core.io.SerializedString("\\u2028");
        private static final SerializableString PS = new com.fasterxml.jackson.core.io.SerializedString("\\u2029");
        private final int[] table = standardAsciiEscapesForJSON();
        ScriptEscapes() {
            table['<'] = ESCAPE_STANDARD;
            table['>'] = ESCAPE_STANDARD;
            table['&'] = ESCAPE_STANDARD;
        }
        @Override public int[] getEscapeCodesForAscii() { return table; }
        @Override public SerializableString getEscapeSequence(int ch) {
            // Pre-ES2019 engines treat these as line terminators inside string literals.
            if (ch == 0x2028) return LS;
            if (ch == 0x2029) return PS;
            return null;
        }
    }
}
