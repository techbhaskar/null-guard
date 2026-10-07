package com.nullguard.callgraph.builder;

import com.nullguard.callgraph.model.ExternalReason;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.callgraph.resolver.MethodResolver;
import com.nullguard.core.callsite.CallSite;
import com.nullguard.core.callsite.CallSiteExtractor;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodIds;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.ResolvedCallTarget;
import com.nullguard.core.model.SemanticCallSite;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Builds the project call graph from the CFG of each method.
 *
 * <h3>Layering</h3>
 * This used to import {@code BasicInstructionExtractor} and {@code MethodCallInstruction} from
 * {@code nullguard-analysis}, so the call graph — a structural fact about the code — was derived
 * from the dataflow IR, a later and more specialised representation. That inverted the intended
 * layering and re-ran the IR lowering a second time for every method. It now reads call sites
 * straight off the CFG via {@link CallSiteExtractor}, and {@code nullguard-callgraph} depends
 * only on {@code nullguard-core}.
 */
public final class BasicCallGraphBuilder implements CallGraphBuilder {

    private final MethodResolver resolver;
    private final CallSiteExtractor callSiteExtractor;

    public BasicCallGraphBuilder() {
        this.resolver = new MethodResolver();
        this.callSiteExtractor = new CallSiteExtractor();
    }

    @Override
    public GlobalCallGraph build(ProjectModel project) {
        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> incoming = new LinkedHashMap<>();
        LinkedHashSet<String> externalNodes = new LinkedHashSet<>();
        LinkedHashMap<String, ExternalReason> externalReasons = new LinkedHashMap<>();

        for (ModuleModel module : project.getModules().values()) {
            for (PackageModel pkg : module.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel mth : cls.getMethods().values()) {
                        String callerId = MethodIds.of(pkg, cls, mth);
                        outgoing.putIfAbsent(callerId, new LinkedHashSet<>());
                        incoming.putIfAbsent(callerId, new LinkedHashSet<>());

                        if (!mth.getSemanticCallSites().isEmpty()) {
                            for (SemanticCallSite site : mth.getSemanticCallSites()) {
                                String calledName = site.getWrittenName();
                                if (isGetterOrSetter(calledName)) continue;

                                ResolvedCallTarget resolvedTarget =
                                        site.getResolvedTarget().orElse(null);
                                List<String> targets = resolvedTarget == null
                                        ? resolver.resolveAll(
                                                project, calledName, site.getArgumentCount())
                                        : resolver.resolveAll(project, resolvedTarget);
                                String externalName = resolvedTarget == null
                                        ? calledName
                                        : resolvedTarget.qualifiedSignature();
                                ExternalReason externalReason = resolvedTarget == null
                                        ? resolver.classifyExternal(calledName)
                                        : resolver.classifyExternal(resolvedTarget);
                                connect(callerId, targets, externalName, externalReason,
                                        outgoing, incoming, externalNodes, externalReasons);
                            }
                        } else if (mth.getControlFlowModel().isPresent()) {
                            // Models built by clients or older parsers have no semantic metadata.
                            // Preserve the name/arity fallback for backward compatibility.
                            for (CallSite site : callSiteExtractor.extract(
                                    mth.getControlFlowModel().get())) {
                                String calledName = site.calleeName();
                                if (isGetterOrSetter(calledName)) continue;
                                List<String> targets = resolver.resolveAll(
                                        project, calledName, site.argCount());
                                connect(callerId, targets, calledName,
                                        resolver.classifyExternal(calledName),
                                        outgoing, incoming, externalNodes, externalReasons);
                            }
                        }
                    }
                }
            }
        }

        return new GlobalCallGraph(outgoing, incoming, externalNodes, externalReasons);
    }

    private static void connect(
            String callerId,
            List<String> targets,
            String externalName,
            ExternalReason externalReason,
            LinkedHashMap<String, LinkedHashSet<String>> outgoing,
            LinkedHashMap<String, LinkedHashSet<String>> incoming,
            LinkedHashSet<String> externalNodes,
            LinkedHashMap<String, ExternalReason> externalReasons) {
        if (!targets.isEmpty()) {
            for (String calleeId : targets) {
                outgoing.get(callerId).add(calleeId);
                incoming.computeIfAbsent(calleeId, ignored -> new LinkedHashSet<>())
                        .add(callerId);
            }
            return;
        }

        String externalId = MethodIds.external(externalName);
        externalNodes.add(externalId);
        externalReasons.putIfAbsent(externalId, externalReason);
        outgoing.get(callerId).add(externalId);
        incoming.computeIfAbsent(externalId, ignored -> new LinkedHashSet<>()).add(callerId);
    }

    /**
     * Returns true for trivial accessor calls like {@code dataVO.getInvoiceNumber},
     * {@code br.setAm} or {@code isActive} that add noise without architectural meaning.
     */
    private static boolean isGetterOrSetter(String calledName) {
        String simple = calledName.contains(".")
                ? calledName.substring(calledName.lastIndexOf('.') + 1)
                : calledName;
        return simple.length() > 3
                && (simple.startsWith("get") || simple.startsWith("set") || simple.startsWith("is"))
                && Character.isUpperCase(simple.charAt(simple.startsWith("is") ? 2 : 3));
    }
}
