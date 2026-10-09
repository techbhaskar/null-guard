package com.nullguard.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nullguard.analysis.contract.ContractModel;
import com.nullguard.analysis.engine.NullAnalysisModel;
import com.nullguard.analysis.extractor.BasicInstructionExtractor;
import com.nullguard.analysis.ir.DereferenceInstruction;
import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.model.ArchitecturalHotspot;
import com.nullguard.core.model.*;
import com.nullguard.suggestions.model.Suggestion;
import java.nio.file.Path;
import java.util.*;

/** Builds a shared finding list and SARIF 2.1.0 without framework dependencies. */
public final class FindingExporter {
    public static final Map<String, String> RULES = Collections.unmodifiableMap(new TreeMap<>(Map.ofEntries(
            Map.entry("NG001", "Possible null dereference"), Map.entry("NG002", "Unchecked nullable parameter"),
            Map.entry("NG003", "Non-null return contract violation"), Map.entry("NG004", "Caller argument contract violation"),
            Map.entry("NG005", "Architectural hotspot"), Map.entry("NG101", "Add null guard"),
            Map.entry("NG102", "Strengthen contract"), Map.entry("NG103", "Validate external return"),
            Map.entry("NG104", "Refactor high blast radius"), Map.entry("NG105", "Break risk chain"))));
    public List<Finding> collect(ProjectModel project, List<ArchitecturalHotspot> hotspots, List<Suggestion> suggestions) {
        List<Finding> findings = new ArrayList<>();
        Map<String, MethodModel> methods = new TreeMap<>();
        project.getModules().values().forEach(mod -> mod.getPackages().values().forEach(pkg ->
                pkg.getClasses().values().forEach(cls -> cls.getMethods().values().forEach(m -> methods.put(MethodIds.of(pkg, cls, m), m)))));
        methods.forEach((id, method) -> {
            method.getNullAnalysisModel().filter(NullAnalysisModel.class::isInstance).map(NullAnalysisModel.class::cast).ifPresent(model ->
                    method.getControlFlowModel().ifPresent(cfg -> {
                        for (var inst : new BasicInstructionExtractor().extract(cfg)) {
                            if (inst instanceof DereferenceInstruction d && com.nullguard.analysis.engine.ExpressionNullability.evaluate(
                                    d.variableName(), model.getInStates().getOrDefault(d.id(), Map.of()),
                                    com.nullguard.analysis.summary.MethodSummaryEngine.callReturns(method, methods)) != NullState.NON_NULL)
                                findings.add(new Finding("NG001", "warning", id, "Receiver '" + d.variableName() + "' may be null",
                                        atNode(method, cfg.getNodes().get(inst.cfgNodeId()), inst.lineNumber())));
                        }
                    }));
            method.getContractModel().filter(ContractModel.class::isInstance).map(ContractModel.class::cast).ifPresent(contract ->
                    contract.getIssues().forEach(i -> findings.add(new Finding(i.ruleId(), "warning", id, i.message(), at(method, i.line())))));
        });
        hotspots.forEach(h -> findings.add(new Finding("NG005", h.getSeverity().equals("CRITICAL") ? "error" : "warning",
                h.getMethodRef(), "Architectural hotspot: " + h.getSeverity() + " (score " + h.getHotspotScore() + ")", at(methods.get(h.getMethodRef()), 0))));
        suggestions.forEach(s -> {
            String rule = switch (s.getSuggestionType()) {
                case ADD_NULL_GUARD -> "NG101";
                case STRENGTHEN_CONTRACT -> "NG102";
                case VALIDATE_EXTERNAL_RETURN -> "NG103";
                case REFACTOR_HIGH_BLAST_RADIUS -> "NG104";
                case BREAK_RISK_CHAIN -> "NG105";
            };
            findings.add(new Finding(rule, "note", s.getMethodId(), s.getMessage(), at(methods.get(s.getMethodId()), 0)));
        });
        return findings.stream().distinct().sorted(Comparator.comparing(Finding::methodId).thenComparing(Finding::ruleId)
                .thenComparingInt(f -> f.location() == null ? 0 : f.location().startLine()).thenComparing(Finding::message)).toList();
    }
    private static SourceLocation at(MethodModel method, int line) {
        if (method == null) return null;
        if (line > 0 && method.getControlFlowModel().isPresent()) {
            var node = method.getControlFlowModel().get().getNodes().values().stream().filter(n -> n.getLineNumber() == line && n.getSourceRange().isPresent()).findFirst();
            if (node.isPresent()) return atNode(method, node.get(), line);
        }
        return method.getSourceLocation().map(l -> line > 0 ? new SourceLocation(l.path(), line, 1, line, 1) : l).orElse(null);
    }
    private static SourceLocation atNode(MethodModel method, com.nullguard.core.cfg.ControlFlowNode node, int line) {
        if (node == null || node.getSourceRange().isEmpty())
            return method.getSourceLocation().map(l -> new SourceLocation(l.path(), line, 1, line, 1)).orElse(null);
        var span = node.getSourceRange().get();
        return method.getSourceLocation().map(l -> new SourceLocation(l.path(), span.startLine(), span.startColumn(), span.endLine(), span.endColumn())).orElse(null);
    }
    public String sarif(List<Finding> findings, Path root) {
        List<Object> rules = RULES.entrySet().stream().map(e -> (Object) Map.of("id", e.getKey(), "name", e.getValue().replace(" ", ""),
                "shortDescription", Map.of("text", e.getValue()))).toList();
        List<Object> results = new ArrayList<>();
        for (Finding finding : findings) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ruleId", finding.ruleId());
            result.put("level", finding.severity());
            result.put("message", Map.of("text", finding.message()));
            result.put("properties", Map.of("methodId", finding.methodId()));
            SourceLocation location = finding.location();
            if (location != null) {
                try {
                    String uri = new java.net.URI(null, null, location.path(), null).toASCIIString();
                    Map<String, Object> region = new LinkedHashMap<>();
                    region.put("startLine", location.startLine()); region.put("startColumn", location.startColumn());
                    region.put("endLine", location.endLine());
                    if (location.endColumn() > location.startColumn() || location.endLine() > location.startLine()) region.put("endColumn", location.endColumn() + 1);
                    result.put("locations", List.of(Map.of("physicalLocation", Map.of("artifactLocation", Map.of("uri", uri, "uriBaseId", "SOURCE_ROOT"), "region", region))));
                } catch (java.net.URISyntaxException invalidPath) { throw new IllegalArgumentException(invalidPath); }
            }
            results.add(result);
        }
        String base = root.toAbsolutePath().normalize().toUri().toASCIIString();
        if (!base.endsWith("/")) base += "/";
        var run = Map.of("tool", Map.of("driver", Map.of("name", "NullGuard", "version", "1.0-SNAPSHOT", "rules", rules)),
                "originalUriBaseIds", Map.of("SOURCE_ROOT", Map.of("uri", base)), "columnKind", "utf16CodeUnits", "results", results);
        try { return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "$schema", "https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/schemas/sarif-schema-2.1.0.json", "version", "2.1.0", "runs", List.of(run))); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot serialize SARIF", failure); }
    }
}
