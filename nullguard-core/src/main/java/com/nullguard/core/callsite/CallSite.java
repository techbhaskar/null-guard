package com.nullguard.core.callsite;

/**
 * One call site found in a method body.
 *
 * @param calleeName the callee as written, e.g. {@code "userRepository.findByEmail"} or
 *                   {@code "compute"}
 * @param receiver   the receiver identifier, or {@code null} for an unqualified call
 * @param argCount   number of arguments, or {@link #UNKNOWN_ARG_COUNT} if it could not be
 *                   counted reliably
 * @param cfgNodeId  the CFG node this call was found in
 * @param lineNumber source line
 */
public record CallSite(String calleeName,
                       String receiver,
                       int argCount,
                       String cfgNodeId,
                       int lineNumber) {

    /** Sentinel meaning "the argument list could not be parsed". */
    public static final int UNKNOWN_ARG_COUNT = -1;

    /** @return the method name without any receiver prefix */
    public String simpleName() {
        int lastDot = calleeName.lastIndexOf('.');
        return lastDot < 0 ? calleeName : calleeName.substring(lastDot + 1);
    }
}
