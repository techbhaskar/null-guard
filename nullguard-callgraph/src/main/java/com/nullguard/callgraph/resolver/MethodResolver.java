package com.nullguard.callgraph.resolver;

import com.nullguard.callgraph.model.ExternalReason;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodIds;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.ResolvedCallTarget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a call-site name to the method ids it may target within a {@link ProjectModel}.
 *
 * <h3>What this improves on</h3>
 * The original resolver matched with
 * {@code mth.getMethodName().equals(name) || mth.getSignature().contains(name)}. The second
 * clause is a substring test against the whole signature, so a call to {@code get()} resolved
 * to {@code getUserById(String)}, {@code add()} resolved to {@code addUser(...)}, and a call
 * named after a type matched any method that merely took that type as a parameter. It also
 * re-walked every module × package × class × method for <em>every</em> call site.
 *
 * <p>This version:
 * <ul>
 *   <li>matches on exact simple name only — the substring clause is gone;</li>
 *   <li>discriminates overloads by argument count when the call site supplied one;</li>
 *   <li>indexes methods by simple name once per project instead of an O(call sites × methods)
 *       scan;</li>
 *   <li>keeps the Spring receiver-name convention scoping ({@code accountingEntryService} →
 *       {@code AccountingEntryService*}), so a controller calling a service interface can be
 *       narrowed to that service's implementations;</li>
 *   <li>keeps the concrete-before-abstract ordering, so an edge lands on the implementation
 *       that actually has a body rather than dead-ending on the interface declaration.</li>
 * </ul>
 *
 * <p>When the parser supplies a {@link ResolvedCallTarget}, resolution uses its declaring type,
 * exact source signature, and the resolved class hierarchy. The name/arity path remains as a
 * conservative fallback for calls that JavaParser cannot solve because source or bytecode is
 * unavailable.
 *
 * <p>Not thread-safe: the name index is memoised against the last project seen.
 */
public final class MethodResolver {

    /** One candidate target method. */
    private record Candidate(String id,
                             String className,
                             String qualifiedClassName,
                             String signature,
                             int arity,
                             boolean hasBody,
                             boolean interfaceType,
                             Set<String> assignableTypes) { }

    /** Simple JDK types that commonly appear as static-call receivers. */
    private static final Set<String> JDK_RECEIVERS = Set.of(
            "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short",
            "Character", "Math", "Objects", "Optional", "Arrays", "Collections", "List",
            "Map", "Set", "Stream", "Collectors", "System", "Thread", "Files", "Paths",
            "LocalDate", "LocalDateTime", "Instant", "Duration", "BigDecimal", "BigInteger",
            "UUID", "Pattern", "StringBuilder", "Comparator", "Executors", "TimeUnit");

    private ProjectModel indexedProject;
    private Map<String, List<Candidate>> index;

    // ── Public API ────────────────────────────────────────────────────────────────

    /** @return the best single target, or empty when the callee is not a project method */
    public Optional<String> resolve(ProjectModel project, String callerMethodId, String calledMethodName) {
        List<String> all = resolveAll(project, calledMethodName);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** @see #resolveAll(ProjectModel, String, int) */
    public List<String> resolveAll(ProjectModel project, String calledMethodName) {
        return resolveAll(project, calledMethodName, MethodCallArity.UNKNOWN);
    }

    /**
     * Returns every plausible target, concrete (body-bearing) matches first.
     *
     * @param calledMethodName raw callee text, optionally receiver-qualified
     *                         (e.g. {@code "userService.findByEmail"})
     * @param argCount         number of arguments at the call site, or
     *                         {@link MethodCallArity#UNKNOWN} to skip arity filtering
     */
    public List<String> resolveAll(ProjectModel project, String calledMethodName, int argCount) {
        if (project == null || calledMethodName == null || calledMethodName.isBlank()) {
            return List.of();
        }
        ensureIndexed(project);

        String receiver = receiverOf(calledMethodName);
        String simpleName = simpleNameOf(calledMethodName);
        if (simpleName.isEmpty()) return List.of();

        List<Candidate> byName = index.getOrDefault(simpleName, List.of());
        if (byName.isEmpty()) return List.of();

        // 1. Narrow by receiver naming convention, if the receiver looks like a bean name.
        String classHint = classHintFor(receiver);
        List<Candidate> scoped = classHint == null
                ? byName
                : filter(byName, c -> classNameMatchesHint(c.className(), classHint));
        if (scoped.isEmpty()) scoped = byName;   // hint was wrong; do not lose the edge

        // 2. Narrow by arity. Argument counting is textual and can be imprecise, so if it
        //    eliminates everything we fall back rather than silently drop a real edge.
        List<Candidate> arityMatched = argCount == MethodCallArity.UNKNOWN
                ? scoped
                : filter(scoped, c -> c.arity() == argCount);
        if (arityMatched.isEmpty()) arityMatched = scoped;

        List<String> concrete = new ArrayList<>();
        List<String> abstracts = new ArrayList<>();
        for (Candidate c : arityMatched) {
            (c.hasBody() ? concrete : abstracts).add(c.id());
        }
        return Collections.unmodifiableList(concrete.isEmpty() ? abstracts : concrete);
    }

    /**
     * Resolves a symbol-solver target to its exact project method and possible runtime
     * implementations. Interface calls prefer body-bearing implementations; calls declared on
     * concrete base classes retain the base implementation and any overriding subclasses.
     */
    public List<String> resolveAll(ProjectModel project, ResolvedCallTarget target) {
        if (project == null || target == null) return List.of();
        ensureIndexed(project);

        List<Candidate> byName = index.getOrDefault(target.methodName(), List.of());
        if (byName.isEmpty()) return List.of();

        List<Candidate> exactOwner = filter(byName, candidate ->
                candidate.qualifiedClassName().equals(target.declaringType())
                        && candidate.arity() == target.parameterCount());
        List<Candidate> runtimeImplementations = filter(byName, candidate ->
                !candidate.interfaceType()
                        && candidate.hasBody()
                        && !candidate.qualifiedClassName().equals(target.declaringType())
                        && candidate.assignableTypes().contains(target.declaringType())
                        && candidate.arity() == target.parameterCount());

        exactOwner = preferSignature(exactOwner, target.signature());
        runtimeImplementations = preferSignature(runtimeImplementations, target.signature());

        boolean ownerIsInterface = exactOwner.stream().anyMatch(Candidate::interfaceType);
        List<String> resolved = new ArrayList<>();
        if (!ownerIsInterface) {
            exactOwner.stream().filter(Candidate::hasBody).map(Candidate::id).forEach(resolved::add);
        }
        runtimeImplementations.stream().map(Candidate::id).forEach(resolved::add);

        if (resolved.isEmpty()) {
            exactOwner.stream().map(Candidate::id).forEach(resolved::add);
        }
        return Collections.unmodifiableList(resolved);
    }

    /**
     * Best-effort classification of a callee that did not resolve to a project method, so that
     * {@link ExternalReason} and {@code ExternalMethodNode} stop being dead declarations.
     *
     * <p>This is a naming heuristic, not type resolution: an upper-case receiver is treated as
     * a type name, and known JDK type names are separated from everything else. A lower-case
     * receiver is a variable whose type we cannot determine without the symbol solver, so it
     * is reported as {@link ExternalReason#UNRESOLVED} rather than guessed at.
     */
    public ExternalReason classifyExternal(String calledMethodName) {
        String receiver = receiverOf(calledMethodName);
        if (receiver == null || receiver.isEmpty()) return ExternalReason.UNRESOLVED;
        if (JDK_RECEIVERS.contains(receiver)) return ExternalReason.JDK;
        if (Character.isUpperCase(receiver.charAt(0))) return ExternalReason.THIRD_PARTY;
        return ExternalReason.UNRESOLVED;
    }

    /** Classifies a successfully resolved method whose declaring type is outside the project. */
    public ExternalReason classifyExternal(ResolvedCallTarget target) {
        if (target == null) return ExternalReason.UNRESOLVED;
        String declaringType = target.declaringType();
        if (declaringType.startsWith("java.")
                || declaringType.startsWith("javax.")
                || declaringType.startsWith("jdk.")) {
            return ExternalReason.JDK;
        }
        return ExternalReason.THIRD_PARTY;
    }

    // ── Indexing ──────────────────────────────────────────────────────────────────

    private void ensureIndexed(ProjectModel project) {
        if (project == indexedProject && index != null) return;

        Map<String, List<Candidate>> built = new LinkedHashMap<>();
        for (ModuleModel module : project.getModules().values()) {
            for (PackageModel pkg : module.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel mth : cls.getMethods().values()) {
                        built.computeIfAbsent(mth.getMethodName(), k -> new ArrayList<>())
                             .add(new Candidate(
                                     MethodIds.of(pkg, cls, mth),
                                     cls.getClassName(),
                                     qualifiedName(pkg, cls),
                                     mth.getSignature(),
                                     arityOf(mth.getSignature()),
                                     mth.getControlFlowModel().isPresent(),
                                     cls.isInterfaceType(),
                                     cls.getAssignableTypes()));
                    }
                }
            }
        }
        this.index = built;
        this.indexedProject = project;
    }

    // ── Name / signature parsing ──────────────────────────────────────────────────

    /** {@code "a.b.service.findX"} → {@code "service"}; unqualified call → null. */
    private static String receiverOf(String calledMethodName) {
        String name = stripArgs(calledMethodName);
        int lastDot = name.lastIndexOf('.');
        if (lastDot < 0) return null;
        String raw = name.substring(0, lastDot);
        int prevDot = raw.lastIndexOf('.');
        return prevDot < 0 ? raw : raw.substring(prevDot + 1);
    }

    /** {@code "userService.findByEmail(x)"} → {@code "findByEmail"}. */
    private static String simpleNameOf(String calledMethodName) {
        String name = stripArgs(calledMethodName);
        int lastDot = name.lastIndexOf('.');
        return (lastDot < 0 ? name : name.substring(lastDot + 1)).trim();
    }

    private static String stripArgs(String s) {
        int paren = s.indexOf('(');
        return (paren < 0 ? s : s.substring(0, paren)).trim();
    }

    /** {@code "accountingEntryService"} → {@code "AccountingEntryService"}; types → null. */
    private static String classHintFor(String receiver) {
        if (receiver == null || receiver.isEmpty()) return null;
        // An upper-case receiver is already a type name, not a bean name.
        if (Character.isUpperCase(receiver.charAt(0))) return receiver;
        if ("this".equals(receiver) || "super".equals(receiver)) return null;
        return Character.toUpperCase(receiver.charAt(0)) + receiver.substring(1);
    }

    private static boolean classNameMatchesHint(String className, String hint) {
        String lc = className.toLowerCase(Locale.ROOT);
        String lh = hint.toLowerCase(Locale.ROOT);
        return lc.contains(lh) || lh.contains(lc);
    }

    /**
     * Counts declared parameters in a signature such as {@code findBy(String, Map<K, V>)}.
     * Commas nested inside generics, arrays or parentheses do not separate parameters.
     */
    static int arityOf(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open) return 0;
        String params = signature.substring(open + 1, close).trim();
        if (params.isEmpty()) return 0;

        int count = 1;
        int depth = 0;
        for (int i = 0; i < params.length(); i++) {
            char c = params.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    private static List<Candidate> filter(List<Candidate> in, java.util.function.Predicate<Candidate> p) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : in) {
            if (p.test(c)) out.add(c);
        }
        return out;
    }

    private static List<Candidate> preferSignature(List<Candidate> candidates, String signature) {
        List<Candidate> exact = filter(candidates,
                candidate -> normalizeSignature(candidate.signature())
                        .equals(normalizeSignature(signature)));
        return exact.isEmpty() ? candidates : exact;
    }

    private static String normalizeSignature(String signature) {
        return signature == null ? "" : signature.replaceAll("\\s+", "");
    }

    private static String qualifiedName(PackageModel pkg, ClassModel cls) {
        String qualified = cls.getQualifiedName();
        return qualified == null || qualified.isBlank()
                ? pkg.getPackageName() + "." + cls.getClassName()
                : qualified;
    }

    /** Namespace for the arity sentinel, kept out of the public surface of this class. */
    public static final class MethodCallArity {
        /** Skip arity filtering. */
        public static final int UNKNOWN = -1;

        private MethodCallArity() { }
    }
}
