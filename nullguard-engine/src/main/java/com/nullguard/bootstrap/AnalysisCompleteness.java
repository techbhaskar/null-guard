package com.nullguard.bootstrap;

import com.nullguard.core.model.*;
import java.util.*;

/** Honest source-analysis accounting. Resolution is not runtime execution coverage. */
final class AnalysisCompleteness {
    static Map<String, Object> describe(ProjectModel project) {
        Map<String, MethodModel> methods = new TreeMap<>();
        Set<String> owners = new HashSet<>();
        project.getModules().values().forEach(mod -> mod.getPackages().values().forEach(pkg ->
                pkg.getClasses().values().forEach(cls -> {
                    owners.add(cls.getQualifiedName() == null ? pkg.getPackageName() + "." + cls.getClassName() : cls.getQualifiedName());
                    cls.getMethods().values().forEach(m -> methods.put(MethodIds.of(pkg, cls, m), m));
                })));
        List<Map<String, Object>> unresolved = new ArrayList<>();
        List<Map<String, Object>> assumptions = new ArrayList<>();
        List<Map<String, Object>> missingDispatch = new ArrayList<>();
        List<Map<String, Object>> lifecycleLimits = new ArrayList<>();
        List<Map<String, Object>> generated = new ArrayList<>();
        List<Map<String, Object>> constructionLimits = new ArrayList<>();
        project.getModules().values().forEach(mod -> mod.getPackages().values().forEach(pkg -> pkg.getClasses().values().forEach(cls ->
                cls.getConstructionLimitations().forEach(limit -> constructionLimits.add(Map.of("type", Objects.toString(cls.getQualifiedName(), cls.getClassName()), "limitation", limit))))));
        var resolver = new com.nullguard.callgraph.resolver.MethodResolver();
        List<String> failedCfg = new ArrayList<>(), failedAnalysis = new ArrayList<>();
        int[] calls = new int[2];
        int[] bodies = new int[2];
        int[] boundaries = new int[2]; // resolved dependency calls, excluded accessor calls
        methods.forEach((id, method) -> {
            if (!method.getOrigin().equals("source")) generated.add(Map.of("methodId", id, "origin", method.getOrigin()));
            method.getLifecycleLimitations().forEach(limit -> lifecycleLimits.add(Map.of("methodId", id, "limitation", limit)));
            if (method.hasDeclaredBody()) {
                bodies[0]++;
                if (method.getControlFlowModel().isEmpty()) failedCfg.add(id);
                else if (method.getNullAnalysisModel().isEmpty()) failedAnalysis.add(id);
                else bodies[1]++;
            }
            for (var site : method.getSemanticCallSites()) {
                calls[0]++;
                if (com.nullguard.callgraph.builder.BasicCallGraphBuilder.isGetterOrSetter(site.getWrittenName())) boundaries[1]++;
                if (site.getResolvedTarget().isPresent()) calls[1]++;
                else unresolved.add(Map.of("methodId", id, "call", site.getWrittenName(), "argumentCount", site.getArgumentCount(),
                        "location", method.getSourceLocation().map(l -> (Object) l).orElse(Map.of())));
                site.getResolvedTarget().ifPresent(target -> {
                    if (!owners.contains(target.declaringType())) boundaries[0]++;
                    else if (resolver.resolveAll(project, target).stream().map(methods::get).filter(Objects::nonNull)
                            .noneMatch(m -> m.getControlFlowModel().isPresent()))
                        missingDispatch.add(Map.of("methodId", id, "call", site.getWrittenName(), "target", target.qualifiedSignature()));
                });
            }
            for (String assumption : method.getFrameworkAssumptions()) assumptions.add(Map.of(
                    "methodId", id, "assumption", assumption,
                    "location", method.getSourceLocation().map(l -> (Object) l).orElse(Map.of())));
        });
        var parse = project.getParseDiagnostics();
        int unmodeled = Math.max(0, parse.discoveredExecutables() - (methods.size() - generated.size()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", parse.discoveredFiles() == 0 ? methods.isEmpty() ? "NO_SOURCE" : "UNAVAILABLE"
                : parse.problemFiles() > 0 || unmodeled > 0 || !failedCfg.isEmpty() || !failedAnalysis.isEmpty()
                ? "INCOMPLETE" : calls[0] != calls[1] || !assumptions.isEmpty() || !missingDispatch.isEmpty() || !lifecycleLimits.isEmpty() || !constructionLimits.isEmpty() || boundaries[1] > 0 ? "LIMITED" : "SOURCE_ANALYZED");
        result.put("scope", "Supplied Java sources only; not test, dependency-body or runtime execution coverage");
        result.put("sourceFilesDiscovered", parse.discoveredFiles());
        result.put("sourceFilesParsed", parse.parsedFiles());
        result.put("sourceFilesWithProblems", parse.problemFiles());
        result.put("sourceProblemPaths", parse.problemPaths());
        result.put("modeledExecutables", methods.size());
        result.put("unmodeledExecutables", unmodeled);
        result.put("declaredBodies", bodies[0]);
        result.put("analyzedBodies", bodies[1]);
        result.put("cfgFailures", failedCfg);
        result.put("analysisFailures", failedAnalysis);
        result.put("totalCallSites", calls[0]);
        result.put("symbolResolvedCallSites", calls[1]);
        result.put("unresolvedCallSites", unresolved);
        result.put("resolvedDependencyCallSites", boundaries[0]);
        result.put("excludedAccessorCallSites", boundaries[1]);
        result.put("sourceDispatchWithoutBody", missingDispatch);
        result.put("frameworkAssumptions", assumptions);
        result.put("generatedExecutables", generated);
        result.put("localLifecycleLimitations", lifecycleLimits);
        result.put("constructionLimitations", constructionLimits);
        result.put("runtimeObserved", false);
        result.put("limitations", List.of(
                "Symbol resolution does not imply that a dependency body was analyzed or a runtime target was observed.",
                "Reflection, generated methods/proxies and runtime profile/bean selection are not fully modeled.",
                "Framework non-null assumptions apply to managed execution; uninitialized field-injected instances remain a limitation.",
                "Common Lombok/implicit constructors and source initializers are lowered statically; builders, records and custom generators remain unsupported.",
                "Local object tracking is bounded; inheritance, nested heap graphs, loops, exceptional and asynchronous effects may invalidate field facts.",
                "Only supplied sources are analyzed; independent module reports do not establish cross-module or cross-service flow."));
        return result;
    }
}
