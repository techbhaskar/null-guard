package com.nullguard.scoring.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImpactChainTest {

    @Test
    void recordsValuesAndKeepsThePathListReadOnly() {
        var path = new ArrayList<>(List.of("api", "service", "repository"));
        var chain = new ImpactChain(path, "OrdersController#save", "HIGH");

        assertEquals(List.of("api", "service", "repository"), chain.path());
        assertEquals("OrdersController#save", chain.entryPoint());
        assertEquals("HIGH", chain.severity());
        assertThrows(UnsupportedOperationException.class, () -> chain.path().add("ignored"));
    }
}
