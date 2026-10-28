package com.nullguard.callgraph.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExternalMethodNodeTest {
    @Test
    void retainsMethodIdentityAndReason() {
        ExternalMethodNode node = new ExternalMethodNode("java.util.List.size", ExternalReason.JDK);

        assertEquals("java.util.List.size", node.getMethodId());
        assertEquals(ExternalReason.JDK, node.getReason());
        assertThrows(NullPointerException.class, () -> new ExternalMethodNode(null, ExternalReason.JDK));
        assertThrows(NullPointerException.class, () -> new ExternalMethodNode("method", null));
    }
}
