package com.nullguard.core.model;

import java.util.Objects;

/**
 * MethodIds — the single source of truth for the canonical cross-module method identifier.
 *
 * <h3>Why this class exists</h3>
 * The method id is the join key between every module in the pipeline: the call graph keys
 * its adjacency sets by it, the risk propagation engine keys {@code riskMap} by it, and the
 * suggestion engine looks method summaries up by it. It used to be built by hand with string
 * concatenation in five different places across four modules — and one of them
 * ({@code DefaultSuggestionEngine}) prefixed the module name while the other four did not.
 * The resulting lookup could never hit, so the entire suggestions module silently returned an
 * empty list on every project. Nothing failed to compile and no test caught it.
 *
 * <p>Every id must now be produced here so that a format change is a single edit and a
 * format mismatch is impossible by construction.
 *
 * <h3>Format</h3>
 * <pre>
 *   internal:  packageName.ClassName#signature      e.g. com.acme.svc.UserService#findById(String)
 *   external:  ext#calleeName                       e.g. ext#findByEmail
 * </pre>
 *
 * <p>The module name is deliberately <em>not</em> part of the id. The parser currently
 * hardcodes a single {@code "root"} module, so including it adds no discrimination while
 * making the id impossible to reconstruct from a call site that only knows package + class.
 */
public final class MethodIds {

    /** Separator between the declaring type and the method signature. */
    public static final String SIGNATURE_SEPARATOR = "#";

    /** Prefix marking a callee that could not be resolved to a method in the analysed project. */
    public static final String EXTERNAL_PREFIX = "ext" + SIGNATURE_SEPARATOR;

    private MethodIds() {
        // utility
    }

    /**
     * Builds the canonical id from raw parts.
     *
     * @param packageName declaring package, e.g. {@code com.acme.svc}
     * @param className   simple class name, e.g. {@code UserService}
     * @param signature   method signature as stored on {@link MethodModel}
     * @return {@code packageName.ClassName#signature}
     */
    public static String of(String packageName, String className, String signature) {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(signature, "signature");
        return packageName + "." + className + SIGNATURE_SEPARATOR + signature;
    }

    /** Builds the canonical id from the model objects. Preferred over the raw-string overload. */
    public static String of(PackageModel pkg, ClassModel cls, MethodModel method) {
        Objects.requireNonNull(pkg, "pkg");
        Objects.requireNonNull(cls, "cls");
        Objects.requireNonNull(method, "method");
        return of(pkg.getPackageName(), cls.getClassName(), method.getSignature());
    }

    /** Builds the id for an unresolved / library callee. */
    public static String external(String calleeName) {
        Objects.requireNonNull(calleeName, "calleeName");
        return EXTERNAL_PREFIX + calleeName;
    }

    /** @return {@code true} if {@code methodId} denotes an unresolved / library callee. */
    public static boolean isExternal(String methodId) {
        return methodId != null && methodId.startsWith(EXTERNAL_PREFIX);
    }

    /**
     * Strips the {@link #EXTERNAL_PREFIX} from an external id.
     *
     * @return the bare callee name, or {@code methodId} unchanged if it is not external
     */
    public static String externalName(String methodId) {
        return isExternal(methodId) ? methodId.substring(EXTERNAL_PREFIX.length()) : methodId;
    }
}
