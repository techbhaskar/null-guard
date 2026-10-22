package com.nullguard.bootstrap;

import com.nullguard.core.model.*;
import com.nullguard.callgraph.model.GlobalCallGraph;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StaticCodeAnalyzerTest {
    @Test void singletonStateIsReportedEvenWhenClassHasNoMethods() {
        var bean = ClassModel.builder().className("State").addAnnotation("Service")
                .sourceLocation(new SourceLocation("State.java", 1, 1, 12, 1))
                .fields(List.of(field("counter", "int", true), field("logger", "Logger", true),
                        field("dependency", "PaymentService", false), field("mapper", "Mapper", false))).build();
        var findings = analyze(bean);
        assertEquals(1, findings.size());
        assertEquals("NG203", findings.get(0).ruleId());
        assertEquals("demo.State#<class>()", findings.get(0).methodId());
        assertEquals(1, findings.get(0).location().startLine());
    }

    @Test void interfacesAndUnlocatedStateDoNotInventSourceFindings() {
        assertTrue(analyze(ClassModel.builder().className("State").addAnnotation("Service")
                .fields(List.of(field("counter", "int", true))).build()).isEmpty());
        assertTrue(analyze(ClassModel.builder().className("State").addAnnotation("Service").interfaceType(true)
                .sourceLocation(new SourceLocation("State.java", 1, 1, 12, 1))
                .fields(List.of(field("counter", "int", true))).build()).isEmpty());
    }

    private static FieldModel field(String name, String type, boolean mutated) {
        return new FieldModel(name, type, type.equals("int"), false, false, false, false, false, mutated, List.of(), 0);
    }

    private static List<Finding> analyze(ClassModel bean) {
        var project = ProjectModel.builder().projectName("test").addModule(ModuleModel.builder().moduleName("main")
                .addPackage(PackageModel.builder().packageName("demo").addClass(bean).build()).build()).build();
        return StaticCodeAnalyzer.analyze(project,
                new GlobalCallGraph(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>()), List.of());
    }
}
