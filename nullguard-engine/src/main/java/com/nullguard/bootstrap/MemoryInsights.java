package com.nullguard.bootstrap;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.stmt.*;
import com.nullguard.core.model.*;
import java.util.*;

/** Syntax inventory for memory reviews. Counts source sites, never runtime objects or bytes. */
final class MemoryInsights {
    private MemoryInsights() {}

    static Map<String, Object> describe(ProjectModel project) {
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        var sites = new ArrayList<Map<String, Object>>();
        int objects = 0, arrays = 0, loopAllocations = 0, parsed = 0, unavailable = 0;
        for (var module : project.getModules().values())
            for (var pkg : module.getPackages().values())
                for (var cls : pkg.getClasses().values()) {
                    for (var field : cls.getFields()) {
                        String type = field.typeName().replaceAll("<.*>", "");
                        String simple = type.substring(type.lastIndexOf('.') + 1);
                        if (simple.equals("ThreadLocal") || simple.equals("InheritableThreadLocal"))
                            sites.add(site("Thread-local retention", pkg.getPackageName() + "." + cls.getClassName() + "#" + field.name(),
                                    cls.getSourceLocation().map(SourceLocation::path).orElse(""), field.line(),
                                    "Review remove() in finally for pooled threads; stored values may outlive a request."));
                        else if (field.staticField() && Set.of("Map", "List", "Set", "Collection", "HashMap", "ConcurrentHashMap", "ArrayList", "HashSet", "Queue", "Deque").contains(simple))
                            sites.add(site("Static collection retention", pkg.getPackageName() + "." + cls.getClassName() + "#" + field.name(),
                                    cls.getSourceLocation().map(SourceLocation::path).orElse(""), field.line(),
                                    "Review size bounds, eviction and cleanup. A static collection alone does not prove a leak."));
                    }
                    for (var method : cls.getMethods().values()) {
                        String body = method.getBodySource();
                        if (body == null || body.isBlank()) { unavailable++; continue; }
                        var result = parser.parseBlock(body);
                        if (!result.isSuccessful() || result.getResult().isEmpty()) { unavailable++; continue; }
                        parsed++;
                        var block = result.getResult().orElseThrow();
                        objects += block.findAll(ObjectCreationExpr.class).size();
                        arrays += block.findAll(ArrayCreationExpr.class).size();
                        var allocations = new ArrayList<com.github.javaparser.ast.Node>();
                        allocations.addAll(block.findAll(ObjectCreationExpr.class));
                        allocations.addAll(block.findAll(ArrayCreationExpr.class));
                        for (var allocation : allocations) {
                            boolean loop = allocation.findAncestor(ForStmt.class).isPresent()
                                    || allocation.findAncestor(ForEachStmt.class).isPresent()
                                    || allocation.findAncestor(WhileStmt.class).isPresent()
                                    || allocation.findAncestor(DoStmt.class).isPresent();
                            if (!loop) continue;
                            loopAllocations++;
                            var loc = method.getSourceLocation().orElse(null);
                            // Body text has independent coordinates; report the enclosing method's real source line.
                            sites.add(site("Allocation inside loop", MethodIds.of(pkg, cls, method), loc == null ? "" : loc.path(),
                                    loc == null ? 0 : loc.startLine(),
                                    "Review allocation frequency and object lifetime. This source site does not measure allocation rate or prove retention."));
                        }
                    }
                }
        sites.sort(Comparator.comparing(s -> s.get("location").toString() + s.get("symbol") + s.get("kind")));
        var summary = new LinkedHashMap<String, Object>();
        summary.put("runtimeObserved", false);
        summary.put("objectAllocationSites", objects);
        summary.put("arrayAllocationSites", arrays);
        summary.put("loopAllocationSites", loopAllocations);
        summary.put("bodiesInspected", parsed);
        summary.put("bodiesUnavailable", unavailable);
        summary.put("reviewSites", sites);
        return summary;
    }

    private static Map<String, Object> site(String kind, String symbol, String path, int line, String guidance) {
        return Map.of("kind", kind, "symbol", symbol, "location", path + (line > 0 ? ":" + line : ""), "guidance", guidance);
    }
}
