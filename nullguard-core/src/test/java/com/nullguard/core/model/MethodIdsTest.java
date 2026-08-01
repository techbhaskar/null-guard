package com.nullguard.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the canonical method-id format.
 *
 * <p>This format is the join key between the call graph, the risk map and the suggestion
 * engine. It was previously built by hand in five places, one of which disagreed with the
 * other four — which silently disabled the whole suggestions module. Any change to the
 * format must break this test.
 */
class MethodIdsTest {

    @Test
    @DisplayName("canonical id is packageName.ClassName#signature — no module prefix")
    void canonicalFormat() {
        assertEquals("com.acme.svc.UserService#findById(String)",
                MethodIds.of("com.acme.svc", "UserService", "findById(String)"));
    }

    @Test
    @DisplayName("the model overload agrees with the raw-string overload")
    void modelOverloadMatchesStringOverload() {
        MethodModel method = MethodModel.builder()
                .methodName("findById")
                .signature("findById(String)")
                .build();
        ClassModel cls = ClassModel.builder().className("UserService").addMethod(method).build();
        PackageModel pkg = PackageModel.builder().packageName("com.acme.svc").addClass(cls).build();

        assertEquals(MethodIds.of("com.acme.svc", "UserService", "findById(String)"),
                MethodIds.of(pkg, cls, method));
    }

    @Test
    @DisplayName("external ids round-trip and are distinguishable from internal ones")
    void externalIds() {
        String ext = MethodIds.external("findByEmail");

        assertEquals("ext#findByEmail", ext);
        assertTrue(MethodIds.isExternal(ext));
        assertEquals("findByEmail", MethodIds.externalName(ext));

        String internal = MethodIds.of("com.acme", "Foo", "bar()");
        assertFalse(MethodIds.isExternal(internal));
        assertEquals(internal, MethodIds.externalName(internal));
    }
}
