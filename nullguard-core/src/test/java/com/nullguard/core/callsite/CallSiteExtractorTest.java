package com.nullguard.core.callsite;

import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.NodeType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class CallSiteExtractorTest {

    @Test void argumentCountingHandlesNestedCallsLiteralsAndIncompleteInput() {
        assertEquals(0, CallSiteExtractor.countArguments("send( )", "send"));
        assertEquals(4, CallSiteExtractor.countArguments("send(nested(a,b), array[i], \"a,b\\\"c\", '\\'')", "send"));
        for (String source : List.of("absent()", "send", "send(a", "send([a)")) {
            assertEquals(CallSite.UNKNOWN_ARG_COUNT, CallSiteExtractor.countArguments(source, "send"), source);
        }
        assertEquals(CallSite.UNKNOWN_ARG_COUNT, CallSiteExtractor.countArguments(null, "send"));
        assertEquals(CallSite.UNKNOWN_ARG_COUNT, CallSiteExtractor.countArguments("send()", null));
        assertEquals(CallSite.UNKNOWN_ARG_COUNT, CallSiteExtractor.countArguments("send()", ""));
    }

    @Test void assignmentDetectionExcludesComparisonOperators() {
        for (String source : List.of("a == b", "a != b", "a <= b", "a >= b", "value")) {
            assertEquals(-1, CallSiteExtractor.indexOfAssignmentOperator(source));
            assertEquals(null, CallSiteExtractor.rightHandSide(source));
        }
        assertEquals(-1, CallSiteExtractor.indexOfAssignmentOperator(null));
        assertEquals(true, CallSiteExtractor.isAssignment("a = call()"));
        assertEquals("call()", CallSiteExtractor.rightHandSide("a = call()"));
        assertEquals(0, CallSiteExtractor.indexOfAssignmentOperator("=value"));
        assertEquals(false, CallSiteExtractor.isAssignment("a == b"));
    }

    @Test void skipsInvalidNodesAndPreservesComplexReceivers() {
        assertEquals(List.of(), new CallSiteExtractor().extract(cfg(NodeType.ENTRY, "ignored()")));
        assertEquals(List.of(), new CallSiteExtractor().extract(cfg(NodeType.STATEMENT, "value")));
        assertEquals(List.of(), new CallSiteExtractor().extract(cfg(NodeType.STATEMENT, "broken(;")));
        var calls = new CallSiteExtractor().extract(cfg(NodeType.THROW, "throw failure(factory().load());"));
        assertIterableEquals(List.of("failure", "factory", "load"), calls.stream().map(CallSite::calleeName).toList());
        assertEquals("factory()", calls.get(2).receiver());
    }

    @Test
    void extractsNestedCallsAndTheirExactArities() {
        ControlFlowModel cfg = cfg(NodeType.STATEMENT, "client.send(mapper.map(value), audit.id());");

        List<CallSite> calls = new CallSiteExtractor().extract(cfg);

        assertIterableEquals(
                List.of("client.send", "mapper.map", "audit.id"),
                calls.stream().map(CallSite::calleeName).toList());
        assertIterableEquals(
                List.of(2, 1, 0),
                calls.stream().map(CallSite::argCount).toList());
    }

    @Test
    void extractsCallsFromReturnAndConditionNodes() {
        LinkedHashMap<String, ControlFlowNode> nodes = new LinkedHashMap<>();
        nodes.put("return", new ControlFlowNode(
                "return", NodeType.RETURN, "return service.process(req);", 10));
        nodes.put("condition", new ControlFlowNode(
                "condition", NodeType.CONDITION, "repository.find(id) != null", 11));
        ControlFlowModel cfg = new ControlFlowModel(
                "sample", nodes, new LinkedHashSet<>(), "return", "condition");

        List<CallSite> calls = new CallSiteExtractor().extract(cfg);

        assertEquals(2, calls.size());
        assertIterableEquals(
                List.of("service.process", "repository.find"),
                calls.stream().map(CallSite::calleeName).toList());
    }

    private static ControlFlowModel cfg(NodeType type, String source) {
        LinkedHashMap<String, ControlFlowNode> nodes = new LinkedHashMap<>();
        nodes.put("n1", new ControlFlowNode("n1", type, source, 1));
        return new ControlFlowModel("sample", nodes, new LinkedHashSet<>(), "n1", "n1");
    }
}
