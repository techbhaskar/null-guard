package com.nullguard.suggestions.engine;

import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.risk.RiskModel;
import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodIds;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.scoring.model.AdjustedRiskModel;
import com.nullguard.scoring.model.RiskLevel;
import com.nullguard.suggestions.model.Suggestion;
import com.nullguard.suggestions.model.SuggestionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The suggestions module used to return an empty list for every project, because the engine
 * keyed its summary map on {@code moduleName.package.Class#sig} while the risk map — built by
 * the propagation engine — was keyed on {@code package.Class#sig}. The lookup could never hit,
 * so every iteration hit {@code continue}. It compiled cleanly and had no tests.
 */
class DefaultSuggestionEngineTest {

    private static final String PKG = "com.acme.svc";
    private static final String CLS = "UserService";
    private static final String SIG = "findByEmail(String)";

    private static final String METHOD_ID = MethodIds.of(PKG, CLS, SIG);

    private static ProjectModel projectWithSummary(NullState returnNullability) {
        MethodModel method = MethodModel.builder()
                .methodName("findByEmail")
                .signature(SIG)
                .build();
        method.setMethodSummary(MethodSummary.builder()
                .returnNullability(returnNullability)
                .propagatesNullFromCallee(true)
                .intrinsicRiskProfile(new RiskModel(70, com.nullguard.analysis.risk.RiskLevel.HIGH))
                .build());

        return ProjectModel.builder()
                .projectName("acme")
                .addModule(ModuleModel.builder()
                        .moduleName("root")
                        .addPackage(PackageModel.builder()
                                .packageName(PKG)
                                .addClass(ClassModel.builder()
                                        .className(CLS)
                                        .addMethod(method)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static Map<String, AdjustedRiskModel> riskMap(String methodId, double adjusted) {
        Map<String, AdjustedRiskModel> map = new LinkedHashMap<>();
        map.put(methodId, new AdjustedRiskModel(
                adjusted, 0.0, 0.0, 0.0, adjusted, RiskLevel.from(adjusted)));
        return map;
    }

    private static GlobalCallGraph callGraphWithExternal(String externalId) {
        LinkedHashSet<String> external = new LinkedHashSet<>();
        if (externalId != null) external.add(externalId);
        return new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), external);
    }

    @Test
    @DisplayName("engine keys summaries by the canonical id, so lookups hit and rules run")
    void producesSuggestionsForAnalysedMethod() {
        List<Suggestion> suggestions = new DefaultSuggestionEngine().generate(
                projectWithSummary(NullState.NULL),
                riskMap(METHOD_ID, 75.0),
                callGraphWithExternal(null));

        assertFalse(suggestions.isEmpty(),
                "a high-risk method with a null-capable return must produce at least one suggestion");
    }

    @Test
    @DisplayName("NullGuardRule fires on a null-capable return (was dead: compared to \"NULLABLE\")")
    void nullGuardRuleFires() {
        List<Suggestion> suggestions = new DefaultSuggestionEngine().generate(
                projectWithSummary(NullState.NULL),
                riskMap(METHOD_ID, 75.0),
                callGraphWithExternal(null));

        assertTrue(suggestions.stream()
                        .anyMatch(s -> s.getSuggestionType() == SuggestionType.ADD_NULL_GUARD),
                "NullState has no NULLABLE constant; the rule must compare against NULL");
    }

    @Test
    @DisplayName("NullGuardRule stays quiet when the return is provably non-null")
    void nullGuardRuleDoesNotFireOnNonNullReturn() {
        List<Suggestion> suggestions = new DefaultSuggestionEngine().generate(
                projectWithSummary(NullState.NON_NULL),
                riskMap(METHOD_ID, 75.0),
                callGraphWithExternal(null));

        assertTrue(suggestions.stream()
                        .noneMatch(s -> s.getSuggestionType() == SuggestionType.ADD_NULL_GUARD));
    }

    @Test
    @DisplayName("ExternalValidationRule reaches external nodes, which carry no summary")
    void externalRuleFiresWithoutASummary() {
        String externalId = MethodIds.external("findByEmail");

        List<Suggestion> suggestions = new DefaultSuggestionEngine().generate(
                projectWithSummary(NullState.NULL),
                riskMap(externalId, 80.0),
                callGraphWithExternal(externalId));

        assertTrue(suggestions.stream()
                        .anyMatch(s -> s.getSuggestionType() == SuggestionType.VALIDATE_EXTERNAL_RETURN),
                "summary-free rules must still run when a method id has no MethodSummary");
    }
}
