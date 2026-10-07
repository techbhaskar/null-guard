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
