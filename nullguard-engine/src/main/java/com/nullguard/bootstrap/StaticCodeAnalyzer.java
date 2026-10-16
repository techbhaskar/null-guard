package com.nullguard.bootstrap;

import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.FieldModel;
import com.nullguard.core.model.MethodFinding;
import com.nullguard.core.model.MethodIds;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.SourceLocation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Phase 2: Architecture & Reliability Static Code Analyzer (NG201–NG206) and
 * OpenAPI Contract Drift aggregator (NG301–NG302).
 */
public final class StaticCodeAnalyzer {

    private static final Set<String> SINGLETON_STEREOTYPES = Set.of(
            "RestController", "Controller", "Service", "Repository", "Component"
    );

    private StaticCodeAnalyzer() {}

    public static List<Finding> analyze(
            ProjectModel project,
            GlobalCallGraph callGraph,
            List<ApiEndpointModel> endpoints) {

        List<Finding> findings = new ArrayList<>();
        Map<String, MethodModel> methodById = new LinkedHashMap<>();
        Map<String, ClassModel> ownerClassByMethodId = new LinkedHashMap<>();
        Map<String, PackageModel> ownerPkgByClass = new LinkedHashMap<>();

        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    ownerPkgByClass.put(cls.getClassName(), pkg);
                    for (MethodModel m : cls.getMethods().values()) {
                        String id = MethodIds.of(pkg, cls, m);
                        methodById.put(id, m);
                        ownerClassByMethodId.put(id, cls);

                        // 1. AST-extracted method findings (NG201, NG202, NG206)
                        for (MethodFinding mf : m.getMethodFindings()) {
                            SourceLocation loc = locationAtLine(m.getSourceLocation().orElse(cls.getSourceLocation().orElse(null)), mf.line());
                            if (loc != null) {
                                findings.add(new Finding(mf.ruleId(), mf.severity(), id, mf.message(), loc));
                            }
                        }
                    }

                    // 2. NG203: Mutable state in Spring singleton beans
                    if (isSingletonBean(cls)) {
                        String representativeId = cls.getMethods().values().stream()
                                .findFirst()
                                .map(m -> MethodIds.of(pkg, cls, m))
                                .orElse(pkg.getPackageName() + "." + cls.getClassName() + "#<class>()");
                        SourceLocation baseLoc = cls.getSourceLocation().orElse(
                                cls.getMethods().values().stream()
                                        .flatMap(m -> m.getSourceLocation().stream())
                                        .findFirst()
                                        .orElse(null));
                        for (FieldModel field : cls.getFields()) {
                            if (field.staticField() || field.finalField() || field.injected()) continue;
                            if (field.name().equalsIgnoreCase("log") || field.name().equalsIgnoreCase("logger")) continue;
                            if (!field.mutated() && isDependencyBeanFieldType(field.typeName())) continue;
                            SourceLocation fieldLoc = locationAtLine(baseLoc, field.line());
                            if (fieldLoc != null) {
                                findings.add(new Finding(
                                        "NG203",
                                        "warning",
                                        representativeId,
                                        "Stateful mutable field '" + field.name() + "' (" + field.typeName()
                                                + ") in singleton bean '" + cls.getClassName()
                                                + "' is shared across threads and may cause race conditions",
                                        fieldLoc
                                ));
                            }
                        }
                    }
                }
            }
        }

        // 3. NG204: Architectural layer bypass (Controller directly invoking Repository)
        Map<String, Set<String>> outgoing = callGraph.getOutgoing();
        for (Map.Entry<String, Set<String>> entry : outgoing.entrySet()) {
            String callerId = entry.getKey();
            ClassModel callerClass = ownerClassByMethodId.get(callerId);
            MethodModel callerMethod = methodById.get(callerId);
            if (callerClass == null || callerMethod == null || !isControllerBean(callerClass)) {
                continue;
            }
            SourceLocation callerLoc = callerMethod.getSourceLocation().orElse(callerClass.getSourceLocation().orElse(null));
            if (callerLoc == null) continue;

            for (String calleeId : entry.getValue()) {
                ClassModel calleeClass = ownerClassByMethodId.get(calleeId);
                if (calleeClass != null && isRepositoryBean(calleeClass)) {
                    findings.add(new Finding(
                            "NG204",
                            "warning",
                            callerId,
                            "Architectural layer bypass: controller '" + callerClass.getClassName()
                                    + "' directly calls repository '" + calleeClass.getClassName()
                                    + "' instead of delegating through a @Service layer",
                            callerLoc
                    ));
                }
            }
        }

        // 4. NG205: Cyclic call/class dependencies in internal call graph
        Set<String> cycleMethods = detectMultiNodeCycles(callGraph);
        for (String cycleMethodId : cycleMethods) {
            MethodModel m = methodById.get(cycleMethodId);
            if (m != null && m.getSourceLocation().isPresent()) {
                findings.add(new Finding(
                        "NG205",
                        "warning",
                        cycleMethodId,
                        "Cyclic call dependency detected in call graph involving '" + cycleMethodId + "'",
                        m.getSourceLocation().get()
                ));
            }
        }

        // 5. NG301 & NG302: OpenAPI Contract Drift rules
        findings.addAll(OpenApiSpecGenerator.collectContractDriftFindings(project, endpoints));

        return findings.stream()
                .distinct()
                .sorted(Comparator.comparing(Finding::methodId)
                        .thenComparing(Finding::ruleId)
                        .thenComparingInt(f -> f.location() == null ? 0 : f.location().startLine())
                        .thenComparing(Finding::message))
                .toList();
    }

    public static Map<String, Object> buildSummary(List<Finding> staticFindings) {
        Map<String, Integer> ruleCounts = new TreeMap<>();
        for (String ruleId : List.of("NG201", "NG202", "NG203", "NG204", "NG205", "NG206", "NG301", "NG302")) {
            ruleCounts.put(ruleId, 0);
        }
        double penalty = 0.0;
        for (Finding f : staticFindings) {
            ruleCounts.compute(f.ruleId(), (k, v) -> (v == null ? 0 : v) + 1);
            penalty += switch (f.ruleId()) {
                case "NG201", "NG203", "NG206" -> 6.0;
                case "NG202", "NG204", "NG205" -> 4.0;
                case "NG301", "NG302" -> 3.0;
                default -> 2.0;
            };
        }
        double score = Math.max(0.0, Math.round((100.0 - penalty) * 100.0) / 100.0);
        String grade = score >= 90.0 ? "A" : score >= 75.0 ? "B" : score >= 60.0 ? "C" : score >= 40.0 ? "D" : "F";

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("architectureScore", score);
        summary.put("architectureGrade", grade);
        summary.put("totalViolations", staticFindings.size());
        summary.put("ruleCounts", ruleCounts);
        summary.put("findings", staticFindings);
        return summary;
    }

    private static Set<String> detectMultiNodeCycles(GlobalCallGraph callGraph) {
        Set<String> internalNodes = new HashSet<>(callGraph.getOutgoing().keySet());
        internalNodes.removeAll(callGraph.getExternalNodes());

        Map<String, Integer> colour = new HashMap<>();
        for (String node : internalNodes) {
            colour.put(node, 0);
        }

        Set<String> cycleNodes = new LinkedHashSet<>();
        List<String> sortedStarts = new ArrayList<>(internalNodes);
        Collections.sort(sortedStarts);
        for (String start : sortedStarts) {
            if (colour.getOrDefault(start, 0) == 0) {
                collectCycleNodes(start, callGraph, colour, internalNodes, cycleNodes);
            }
        }
        return cycleNodes;
    }

    private static void collectCycleNodes(
            String start,
            GlobalCallGraph callGraph,
            Map<String, Integer> colour,
            Set<String> internalNodes,
            Set<String> cycleNodes) {

        Deque<String> stack = new ArrayDeque<>();
        Deque<Boolean> returning = new ArrayDeque<>();
        stack.push(start);
        returning.push(false);

        while (!stack.isEmpty()) {
            String node = stack.peek();
            boolean isReturn = returning.peek();
            if (isReturn) {
                stack.pop();
                returning.pop();
                colour.put(node, 2);
                continue;
            }
            int c = colour.getOrDefault(node, 0);
            if (c == 2) {
                stack.pop();
                returning.pop();
                continue;
            }
            if (c == 1) {
                cycleNodes.add(node);
                stack.pop();
                returning.pop();
                continue;
            }
            colour.put(node, 1);
            returning.pop();
            returning.push(true);

            Set<String> callees = callGraph.getCallees(node);
            if (callees != null) {
                List<String> sortedCallees = new ArrayList<>(callees);
                Collections.sort(sortedCallees);
                for (String callee : sortedCallees) {
                    if (!callee.equals(node) && internalNodes.contains(callee)) {
                        stack.push(callee);
                        returning.push(false);
                    }
                }
            }
        }
    }

    private static boolean isSingletonBean(ClassModel cls) {
        if (cls.isInterfaceType()) return false;
        for (String ann : cls.getAnnotations()) {
            if (SINGLETON_STEREOTYPES.contains(ann)) return true;
        }
        return false;
    }

    private static boolean isControllerBean(ClassModel cls) {
        Set<String> anns = cls.getAnnotations();
        return anns.contains("RestController") || anns.contains("Controller");
    }

    private static boolean isRepositoryBean(ClassModel cls) {
        if (cls.getAnnotations().contains("Repository")) return true;
        String name = cls.getClassName();
        return name.endsWith("Repository") || name.endsWith("Dao");
    }

    private static boolean isDependencyBeanFieldType(String typeName) {
        if (typeName == null) return false;
        return typeName.endsWith("Service") || typeName.endsWith("Repository")
                || typeName.endsWith("Dao") || typeName.endsWith("Client")
                || typeName.endsWith("Mapper") || typeName.endsWith("Template");
    }

    private static SourceLocation locationAtLine(SourceLocation base, int line) {
        if (base == null) return null;
        int validLine = line > 0 ? line : Math.max(1, base.startLine());
        return new SourceLocation(base.path(), validLine, 1, validLine, 1);
    }
}
