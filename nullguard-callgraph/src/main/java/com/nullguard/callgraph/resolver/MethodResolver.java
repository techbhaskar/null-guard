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
        String searchName = calledMethodName;
        if (searchName.contains(".")) {
            searchName = searchName.substring(searchName.lastIndexOf(".") + 1);
        }
        // Strip trailing parentheses/args if present
        if (searchName.contains("(")) {
            searchName = searchName.substring(0, searchName.indexOf("("));
        }
        searchName = searchName.trim();

        List<String> concrete = new ArrayList<>();
        List<String> abstracts = new ArrayList<>();

        for (ModuleModel module : project.getModules().values()) {
            for (PackageModel pkg : module.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
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

        // Prefer concrete implementations; fall back to abstract if nothing else found
        if (!concrete.isEmpty()) return Collections.unmodifiableList(concrete);
        return Collections.unmodifiableList(abstracts);
    }
}
