package com.nullguard.callgraph;

import com.nullguard.callgraph.builder.BasicCallGraphBuilder;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.parser.JavaParserAstParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticCallGraphTest {

    @TempDir
    Path sourceRoot;

    @Test void unresolvedCallsDoNotConnectUnrelatedMethods() throws Exception {
        write("Caller.java", """
                package com.acme;
                class Caller { Missing service; void run() { service.process("x"); } }
                class Unrelated { void process(String value) {} }
                """);
        Set<String> callees = graph().getCallees("com.acme.Caller#run()");
        assertFalse(callees.contains("com.acme.Unrelated#process(String)"));
        assertTrue(callees.stream().allMatch(id -> id.startsWith("ext#")));
        assertFalse(callees.isEmpty());
    }

    @Test void qualifiedParameterSpellingNeverBroadensToAnotherOverload() throws Exception {
        write("Worker.java", """
                package com.acme;
                interface Worker { String process(String value); }
                class WorkerImpl implements Worker {
                    public String process(java.lang.String value) { return value; }
                    public String process(Integer value) { return value.toString(); }
                }
                class Client { Worker worker; String run() { return worker.process("ok"); } }
                """);
        assertEquals(Set.of("com.acme.WorkerImpl#process(java.lang.String)"), graph().getCallees("com.acme.Client#run()"));
    }

    @Test void staticMethodsDoNotDispatchToSubclassHidingMethods() throws Exception {
        write("Base.java", """
                package com.acme;
                class Base { static String process(String value) { return value; } }
                class Child extends Base { static String process(String value) { return null; } }
                class Client { String run() { return Base.process("ok"); } }
                """);
        assertEquals(Set.of("com.acme.Base#process(String)"), graph().getCallees("com.acme.Client#run()"));
    }

    @Test void genericInterfaceDispatchUsesSubstitutionNotUnrelatedOverloads() throws Exception {
        write("Worker.java", """
                package com.acme;
                interface Worker<T> { T process(T value); }
                class WorkerImpl implements Worker<String> {
                    public String process(String value) { return value; }
                    public String process(Integer value) { return value.toString(); }
                }
                class Client { Worker<String> worker; String run() { return worker.process("ok"); } }
                """);
        assertEquals(Set.of("com.acme.WorkerImpl#process(String)"), graph().getCallees("com.acme.Client#run()"));
    }

    @Test void superCallsDoNotDispatchBackToOverrides() throws Exception {
        write("Base.java", """
                package com.acme;
                class Base { String process(String value) { return value; } }
                class Child extends Base {
                    @Override String process(String value) { return super.process(value); }
                }
                """);
        assertEquals(Set.of("com.acme.Base#process(String)"), graph().getCallees("com.acme.Child#process(String)"));
    }

    @Test
    void injectedInterfaceCallTargetsConcreteImplementationAndExactOverload() throws Exception {
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

        GlobalCallGraph graph = graph();
        Set<String> targets = graph.getCallees("com.acme.PaymentController#submit()");

        assertEquals(Set.of("com.acme.PaymentServiceImpl#charge(String)"), targets);
        assertFalse(graph.getExternalNodes().stream().anyMatch(id -> id.contains("charge")));
    }

    @Test
    void baseTypedCallIncludesBaseMethodAndOverridingSubclass() throws Exception {
        write("BaseWorker.java", """
                package com.acme;
                class BaseWorker {
                    String execute(String value) { return value; }
                }
                """);
        write("SpecialWorker.java", """
                package com.acme;
                class SpecialWorker extends BaseWorker {
                    @Override String execute(String value) { return value.trim(); }
                }
                """);
        write("WorkerClient.java", """
                package com.acme;
                class WorkerClient {
                    private BaseWorker worker;
                    String run() { return worker.execute("job"); }
                }
                """);

        Set<String> targets = graph().getCallees("com.acme.WorkerClient#run()");

        assertTrue(targets.contains("com.acme.BaseWorker#execute(String)"));
        assertTrue(targets.contains("com.acme.SpecialWorker#execute(String)"));
        assertEquals(2, targets.size());
    }

    private GlobalCallGraph graph() {
        ProjectModel project = new JavaParserAstParser().parse(sourceRoot);
        return new BasicCallGraphBuilder().build(project);
    }

    private void write(String fileName, String source) throws Exception {
        Path packageDirectory = sourceRoot.resolve(Path.of("com", "acme"));
        Files.createDirectories(packageDirectory);
        Files.writeString(packageDirectory.resolve(fileName), source);
    }
}
