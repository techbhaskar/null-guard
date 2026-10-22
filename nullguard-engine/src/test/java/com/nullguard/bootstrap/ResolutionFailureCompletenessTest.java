package com.nullguard.bootstrap;

import com.nullguard.core.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ResolutionFailureCompletenessTest {
    @Test
    void resolutionFailureRetainsExpressionAndExactCallLineInCompleteness() {
        var method = MethodModel.builder().methodName("run").signature("run()")
                .sourceLocation(new SourceLocation("Sample.java", 1, 1, 8, 1))
                .semanticCallSites(List.of(new SemanticCallSite("broken", 0, null,
                        "receiver.broken()", 5, "StackOverflowError during symbol resolution"))).build();
        var type = ClassModel.builder().className("Sample").qualifiedName("Sample").addMethod(method).build();
        var project = ProjectModel.builder().projectName("sample")
                .parseDiagnostics(new ParseDiagnostics(1, 1, 0, 1, List.of()))
                .addModule(ModuleModel.builder().moduleName("root")
                        .addPackage(PackageModel.builder().packageName("default").addClass(type).build()).build()).build();
        var report = AnalysisCompleteness.describe(project);
        assertEquals("LIMITED", report.get("status"));
        var failures = (List<?>) report.get("unresolvedCallSites");
        var failure = (Map<?, ?>) failures.get(0);
        assertEquals(5, failure.get("line"));
        assertEquals("receiver.broken()", failure.get("expression"));
        assertEquals("StackOverflowError during symbol resolution", failure.get("resolutionFailure"));
        assertEquals(0, report.get("symbolResolvedCallSites"));
    }
}
