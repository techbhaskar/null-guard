package com.nullguard.callgraph.resolver;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Resolves method call names to fully-qualified method IDs within a ProjectModel.
 *
 * <p>In a Spring microservice architecture controllers typically call <em>service
 * interfaces</em>, not the concrete implementation class directly.  Resolving to
 * the interface method gives a dead-end in the call graph (no CFG body → no
 * outgoing edges).  This resolver therefore:
 * <ol>
 *   <li>Collects <strong>all</strong> methods whose simple name matches the callee.</li>
 *   <li>Partitions them into <em>concrete</em> (has a CFG body) and
 *       <em>abstract/interface</em> (no body).</li>
 *   <li>{@link #resolve} returns the first concrete match (for single-target APIs that
 *       still expect an {@link Optional}).</li>
 *   <li>{@link #resolveAll} returns all concrete matches so that
 *       {@link com.nullguard.callgraph.builder.BasicCallGraphBuilder} can add an
 *       edge to every live implementation, enabling full
 *       controller → serviceImpl → repository → external tracing.</li>
 * </ol>
 */
public final class MethodResolver {

    /**
     * Returns the first concrete (body-bearing) method that matches
     * {@code calledMethodName}, falling back to the first abstract match if no
     * concrete one exists.
     */
    public Optional<String> resolve(ProjectModel project, String callerMethodId, String calledMethodName) {
        List<String> all = resolveAll(project, calledMethodName);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Returns all concrete implementations that match {@code calledMethodName},
     * ordered so that concrete (CFG-bearing) methods come before abstract ones.
     * If no concrete method is found the abstract matches are returned as
     * fallback so external callers can still mark the call as internal.
     *
     * @param project           the fully-parsed project model
     * @param calledMethodName  raw callee string (may include receiver prefix,
     *                          e.g. {@code "userService.findByEmail"})
     */
    public List<String> resolveAll(ProjectModel project, String calledMethodName) {
        // Extract receiver and simple method name.
        // e.g. "accountingEntryService.getAllCurrency" → receiver="accountingEntryService", search="getAllCurrency"
        String receiver = null;
        String searchName = calledMethodName;
        if (searchName.contains(".")) {
            int lastDot = searchName.lastIndexOf(".");
            String rawReceiver = searchName.substring(0, lastDot);
            // For chained receivers like "a.b.service" take only the last segment
            receiver = rawReceiver.contains(".")
                    ? rawReceiver.substring(rawReceiver.lastIndexOf(".") + 1)
                    : rawReceiver;
            searchName = searchName.substring(lastDot + 1);
        }
        if (searchName.contains("(")) {
            searchName = searchName.substring(0, searchName.indexOf("("));
        }
        searchName = searchName.trim();

        // Derive a class-name hint from the receiver using Spring naming conventions.
        // "accountingEntryService" → "AccountingEntryService"
        // "clearingReportsRepo"    → "ClearingReportsRepo"
        String classHint = null;
        if (receiver != null && !receiver.isEmpty()) {
            classHint = Character.toUpperCase(receiver.charAt(0)) + receiver.substring(1);
        }

        List<String> concrete  = new ArrayList<>();
        List<String> abstracts = new ArrayList<>();

        collectMatches(project, searchName, classHint, concrete, abstracts);

        // If receiver-scoped search found nothing, fall back to unscoped search
        if (concrete.isEmpty() && abstracts.isEmpty() && classHint != null) {
            collectMatches(project, searchName, null, concrete, abstracts);
        }

        if (!concrete.isEmpty()) return Collections.unmodifiableList(concrete);
        return Collections.unmodifiableList(abstracts);
    }

    private void collectMatches(ProjectModel project, String searchName, String classHint,
                                List<String> concrete, List<String> abstracts) {
        for (ModuleModel module : project.getModules().values()) {
            for (PackageModel pkg : module.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    if (classHint != null && !classNameMatchesHint(cls.getClassName(), classHint)) {
                        continue;
                    }
                    for (MethodModel mth : cls.getMethods().values()) {
                        if (mth.getMethodName().equals(searchName)
                                || mth.getSignature().startsWith(searchName + "(")) {
                            String id = pkg.getPackageName() + "." + cls.getClassName()
                                    + "#" + mth.getSignature();
                            if (mth.getControlFlowModel().isPresent()) {
                                concrete.add(id);
                            } else {
                                abstracts.add(id);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns true when the class name is plausibly the target type indicated by the receiver hint.
     * Examples:
     *   className="AccountingEntryServiceImpl", hint="AccountingEntryService" → true (impl contains hint)
     *   className="AccountingEntryService",     hint="AccountingEntryService" → true (exact match)
     *   className="HelperController",           hint="AccountingEntryService" → false
     */
    private static boolean classNameMatchesHint(String className, String hint) {
        String lc = className.toLowerCase();
        String lh = hint.toLowerCase();
        return lc.contains(lh) || lh.contains(lc);
    }
}
