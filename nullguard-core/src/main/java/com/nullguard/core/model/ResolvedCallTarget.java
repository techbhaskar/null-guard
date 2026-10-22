package com.nullguard.core.model;

import java.util.Objects;

/**
 * Symbol-solver result for one method call.
 *
 * @param declaringType fully-qualified type that declares the selected method
 * @param signature     source-compatible method signature, for example {@code save(Order)}
 * @param methodName    simple method name
 * @param parameterCount resolved declaration arity
 * @param staticMethod true only when symbol resolution proves the selected method is static
 */
public record ResolvedCallTarget(
        String declaringType,
        String signature,
        String methodName,
        int parameterCount,
        String canonicalSignature,
        boolean virtualDispatch,
        boolean staticMethod) {

    /** Older/manual targets do not establish that a call is static. */
    public ResolvedCallTarget(String declaringType, String signature, String methodName, int parameterCount,
                              String canonicalSignature, boolean virtualDispatch) {
        this(declaringType, signature, methodName, parameterCount, canonicalSignature, virtualDispatch, false);
    }

    public ResolvedCallTarget(String declaringType, String signature, String methodName, int parameterCount, String canonicalSignature) {
        this(declaringType, signature, methodName, parameterCount, canonicalSignature, true);
    }

    public ResolvedCallTarget(String declaringType, String signature, String methodName, int parameterCount) {
        this(declaringType, signature, methodName, parameterCount, signature);
    }

    public ResolvedCallTarget {
        Objects.requireNonNull(declaringType, "declaringType");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(canonicalSignature, "canonicalSignature");
        if (parameterCount < 0) {
            throw new IllegalArgumentException("parameterCount must be >= 0");
        }
    }

    public String qualifiedSignature() {
        return declaringType + MethodIds.SIGNATURE_SEPARATOR + signature;
    }
}
