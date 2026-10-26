package com.nullguard.callgraph.model;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GlobalCallGraphTest {
    @Test
    void exposesImmutableGraphAndExternalClassifications() {
        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        LinkedHashSet<String> callees = new LinkedHashSet<>(Set.of("external.call"));
        outgoing.put("Service.run", callees);
        LinkedHashMap<String, LinkedHashSet<String>> incoming = new LinkedHashMap<>();
        incoming.put("external.call", new LinkedHashSet<>(Set.of("Service.run")));
        Set<String> external = new LinkedHashSet<>(Set.of("external.call"));
        GlobalCallGraph graph = new GlobalCallGraph(outgoing, incoming, external,
                Map.of("external.call", ExternalReason.THIRD_PARTY));

        callees.add("later.call");
        external.add("later.external");

        assertEquals(Set.of("external.call"), graph.getCallees("Service.run"));
        assertEquals(Set.of("Service.run"), graph.getCallers("external.call"));
        assertTrue(graph.getCallees("unknown").isEmpty());
        assertTrue(graph.getCallers("unknown").isEmpty());
        assertTrue(graph.isExternal("external.call"));
        assertFalse(graph.isExternal("Service.run"));
        assertEquals(ExternalReason.THIRD_PARTY, graph.getExternalReason("external.call"));
        assertEquals(ExternalReason.UNRESOLVED, graph.getExternalReason("unknown"));
        assertEquals(1, graph.countExternalByReason(ExternalReason.THIRD_PARTY));
        assertEquals(0, graph.countExternalByReason(ExternalReason.JDK));
        assertEquals("external.call", graph.getExternalMethodNodes().iterator().next().getMethodId());
        assertThrows(UnsupportedOperationException.class,
                () -> graph.getCallees("Service.run").add("mutate"));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.getExternalNodes().add("mutate"));
        assertThrows(UnsupportedOperationException.class,
                () -> graph.getExternalReasons().put("mutate", ExternalReason.JDK));
    }

    @Test
    void defaultsExternalReasonsForLegacyConstructor() {
        GlobalCallGraph graph = new GlobalCallGraph(new LinkedHashMap<>(),
                new LinkedHashMap<>(), Set.of("legacy.call"));

        assertEquals(ExternalReason.UNRESOLVED, graph.getExternalReason("legacy.call"));
        assertTrue(graph.getExternalReasons().isEmpty());
    }
}
