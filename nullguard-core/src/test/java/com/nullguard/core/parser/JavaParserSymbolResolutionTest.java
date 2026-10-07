package com.nullguard.core.parser;

import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.ResolvedCallTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaParserSymbolResolutionTest {

    @TempDir
    Path sourceRoot;

    @Test
    void resolvesInjectedInterfaceCallAndExactOverload() throws Exception {
        write("PaymentService.java", """
                package com.acme;
                interface PaymentService {
                    String charge(String id);
                    String charge(int id);
                }
                """);
        write("PaymentServiceImpl.java", """
                package com.acme;
                class PaymentServiceImpl implements PaymentService {
                    public String charge(String id) { return id; }
                    public String charge(int id) { return Integer.toString(id); }
                }
                """);
        write("PaymentController.java", """
                package com.acme;
                class PaymentController {
                    private PaymentService service;
                    String submit() { return service.charge("order-1"); }
                }
                """);

        ProjectModel project = new JavaParserAstParser().parse(sourceRoot);
        MethodModel submit = method(project, "PaymentController", "submit()");
        ResolvedCallTarget target = submit.getSemanticCallSites().get(0)
                .getResolvedTarget().orElseThrow();

        assertEquals("com.acme.PaymentService", target.declaringType());
        assertEquals("charge(String)", target.signature());
        ClassModel implementation = type(project, "PaymentServiceImpl");
        assertTrue(implementation.getAssignableTypes().contains("com.acme.PaymentService"));
    }

    @Test
    void resolvesMethodsFromDependencyJars() throws Exception {
        write("Validation.java", """
                package com.acme;
                import org.junit.jupiter.api.Assertions;
                class Validation {
                    void validate(Object value) { Assertions.assertNotNull(value); }
                }
                """);

        Path junitJar = junitApiJar();
        ProjectModel project = new JavaParserAstParser(List.of(sourceRoot), List.of(junitJar))
                .parse(sourceRoot);
        ResolvedCallTarget target = method(project, "Validation", "validate(Object)")
                .getSemanticCallSites().get(0)
                .getResolvedTarget().orElseThrow();

        assertEquals("org.junit.jupiter.api.Assertions", target.declaringType());
        assertEquals("assertNotNull(java.lang.Object)", target.signature());
    }

    private void write(String fileName, String source) throws Exception {
        Path packageDirectory = sourceRoot.resolve(Path.of("com", "acme"));
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve(fileName), source);
    }

    private static ClassModel type(ProjectModel project, String name) {
        return project.getModules().get("root")
                .getPackages().get("com.acme")
                .getClasses().get(name);
    }

    private static MethodModel method(ProjectModel project, String type, String signature) {
        return type(project, type).getMethods().get(signature);
    }

    private static Path junitApiJar() throws URISyntaxException {
        return Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
