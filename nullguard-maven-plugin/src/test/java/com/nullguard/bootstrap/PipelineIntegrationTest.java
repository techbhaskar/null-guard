package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PipelineIntegrationTest {
    static final Path SOURCES = Path.of("..", "examples", "orders-service", "src", "main", "java");
    @Test void endpointsRiskHotspotsContractsAndSarifAgree() throws Exception {
        var result = new EngineBootstrap(NullGuardConfig.defaults().build()).run(SOURCES);
        assertEquals(5, result.getApiEndpoints().size());
        assertTrue(result.getApiEndpoints().stream().allMatch(e -> e.getHttpMethod().equals("GET") && e.getPath().startsWith("/orders/")));
        assertTrue(result.getApiEndpoints().stream().allMatch(e -> e.getPropagationChain().contains("sample.Services.Risky#load()") && e.getApiRiskScore() >= 70));
        assertTrue(result.getApiEndpoints().stream().allMatch(e -> e.getHotspotIndicators().contains("ARCHITECTURAL_HOTSPOT")));
        assertTrue(result.getHotspots().stream().anyMatch(h -> h.getMethodRef().equals("sample.OrderRepository#find()")));
        assertTrue(result.getHotspots().stream().anyMatch(h -> h.getMethodRef().equals("sample.Services.Risky#load()")));
        assertTrue(result.hasHotspotsAtOrAboveSeverity("CRITICAL"));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG003") && f.methodId().endsWith("#brokenReturn()")));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && f.methodId().endsWith("#badArgument()")));
        assertFalse(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && (f.methodId().endsWith("#goodArgument()") || f.methodId().contains("#guardedArgument("))));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG002") && f.methodId().contains("#unsafeParameter(")));
        assertFalse(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG002") && f.methodId().contains("#guardedParameter(")));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001") && f.methodId().endsWith("#badConsumption()")));
        assertFalse(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001") && f.methodId().endsWith("#goodConsumption()")));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001") && f.methodId().endsWith("#chainedBadConsumption()")));
        assertFalse(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001") && f.methodId().endsWith("#chainedGoodConsumption()")));
        assertFalse(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && f.methodId().contains("#requireNonNullGuard(")));
        assertFalse(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001") && f.methodId().contains("#requireNonNullGuard(")));
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG004") && f.methodId().endsWith("#aliasBadArgument()")));
        assertFalse(result.getHotspots().stream().anyMatch(h -> h.getMethodRef().equals("sample.ShadowServices.Risky#load()")));
        var sarif = new ObjectMapper().readTree(result.getVisualizations().getSarif());
        assertEquals("2.1.0", sarif.path("version").asText());
        assertEquals(result.getFindings().size(), sarif.path("runs").get(0).path("results").size());
        for (var finding : result.getFindings()) {
            assertTrue(FindingExporter.RULES.containsKey(finding.ruleId()));
            assertNotNull(finding.location());
            assertTrue(finding.location().startLine() > 0);
            assertTrue(java.nio.file.Files.exists(SOURCES.resolve(finding.location().path())));
        }
        assertTrue(result.getFindings().stream().anyMatch(f -> f.ruleId().equals("NG001") && f.location().startColumn() > 1));
        var report = new ObjectMapper().readTree(result.getVisualizations().getJsonGraph());
        assertEquals("1.0", report.path("schemaVersion").asText());
        assertEquals(5, report.path("apiEndpoints").size());
    }
    @Test void thresholdsAreOnPercentageScaleAndRejectInvalidConfiguration() {
        var config = NullGuardConfig.defaults().build().toAnalysisConfig();
        var detector = new com.nullguard.analysis.hotspot.HotspotDetector(config);
        assertFalse(detector.isHotspotCandidate(0.8, 5));
        assertFalse(detector.isHotspotCandidate(69, 5));
        assertTrue(detector.isHotspotCandidate(70, 5));
        assertFalse(detector.isHotspotCandidate(70, 4));
        assertThrows(IllegalArgumentException.class, () -> NullGuardConfig.defaults().hotspotRiskThreshold(101).build());
        assertThrows(IllegalArgumentException.class, () -> NullGuardConfig.defaults().failThreshold("TYPO").build());
    }
    @Test void parameterMetadataAndSummariesIncludeAnnotationsAndPrimitiveStates() {
        var project = new com.nullguard.core.parser.JavaParserAstParser().parse(SOURCES);
        new com.nullguard.analysis.summary.MethodSummaryEngine(NullGuardConfig.defaults().build().toAnalysisConfig()).run(project);
        var methods = project.getModules().get("root").getPackages().get("sample").getClasses().get("ContractExamples").getMethods();
        var required = (com.nullguard.analysis.summary.MethodSummary) methods.get("required(String)").getMethodSummary().orElseThrow();
        assertEquals(com.nullguard.analysis.lattice.NullState.NON_NULL, required.getParameterNullability().get("value"));
        var unchecked = (com.nullguard.analysis.summary.MethodSummary) methods.get("unsafeParameter(String)").getMethodSummary().orElseThrow();
        assertEquals(com.nullguard.analysis.lattice.NullState.UNKNOWN, unchecked.getParameterNullability().get("value"));
        var primitive = (com.nullguard.analysis.summary.MethodSummary) methods.get("primitive(int)").getMethodSummary().orElseThrow();
        assertEquals(com.nullguard.analysis.lattice.NullState.NON_NULL, primitive.getParameterNullability().get("value"));
        assertEquals(com.nullguard.analysis.lattice.NullState.NON_NULL, primitive.getReturnNullability());
        var primitiveExternal = (com.nullguard.analysis.summary.MethodSummary) methods.get("primitiveExternal()").getMethodSummary().orElseThrow();
        assertEquals(com.nullguard.analysis.lattice.NullState.NON_NULL, primitiveExternal.getReturnNullability());
    }
    @Test void safeJaxRsServiceHasNoHotspotsAndBodylessMethodsHaveNoProvenSummary() {
        Path sources = Path.of("../examples/catalog-service/src/main/java");
        var result = new EngineBootstrap(NullGuardConfig.defaults().build()).run(sources);
        assertEquals(1, result.getApiEndpoints().size());
        assertEquals("GET", result.getApiEndpoints().get(0).getHttpMethod());
        assertEquals("/catalog/items", result.getApiEndpoints().get(0).getPath());
        assertEquals(0.0, result.getApiEndpoints().get(0).getApiRiskScore());
        assertFalse(result.hasHotspotsAtOrAboveSeverity("LOW"));
        var project = new com.nullguard.core.parser.JavaParserAstParser().parse(sources);
        new com.nullguard.analysis.summary.MethodSummaryEngine(NullGuardConfig.defaults().build().toAnalysisConfig()).run(project);
        var declaration = project.getModules().get("root").getPackages().get("catalog").getClasses().get("UnknownProvider").getMethods().get("value()");
        assertTrue(declaration.getControlFlowModel().isEmpty());
        assertTrue(declaration.getMethodSummary().isEmpty());
    }
}
