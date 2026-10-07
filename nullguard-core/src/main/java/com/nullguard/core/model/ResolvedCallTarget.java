package com.nullguard.core.model;

import java.util.Objects;

/**
 * Symbol-solver result for one method call.
 *
 * @param declaringType fully-qualified type that declares the selected method
 * @param signature     source-compatible method signature, for example {@code save(Order)}
 * @param methodName    simple method name
 * @param parameterCount resolved declaration arity
 */
public record ResolvedCallTarget(
        String declaringType,
        String signature,
        String methodName,
        int parameterCount) {

    public ResolvedCallTarget {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(methodName, "methodName");
        if (parameterCount < 0) {
            throw new IllegalArgumentException("parameterCount must be >= 0");
        }
    }

    public String qualifiedSignature() {
        return declaringType + MethodIds.SIGNATURE_SEPARATOR + signature;
    }
}
