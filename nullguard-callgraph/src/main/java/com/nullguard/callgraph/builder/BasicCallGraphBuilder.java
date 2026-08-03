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

                        if (mth.getControlFlowModel().isEmpty()) continue;

                        for (CallSite site : callSiteExtractor.extract(mth.getControlFlowModel().get())) {
                            String calledName = site.calleeName();
                            if (isGetterOrSetter(calledName)) continue;

                            // resolveAll returns concrete implementations first, so Spring's
                            // controller → serviceInterface → serviceImpl pattern is handled:
                            // edges are added to ALL concrete implementations, not just the
                            // interface. argCount discriminates overloads.
                            List<String> targets =
                                    resolver.resolveAll(project, calledName, site.argCount());

                            if (!targets.isEmpty()) {
                                for (String calleeId : targets) {
                                    outgoing.get(callerId).add(calleeId);
                                    incoming.computeIfAbsent(calleeId, k -> new LinkedHashSet<>())
                                            .add(callerId);
                                }
                            } else {
                                String extId = MethodIds.external(calledName);
                                externalNodes.add(extId);
                                externalReasons.putIfAbsent(extId, resolver.classifyExternal(calledName));
                                outgoing.get(callerId).add(extId);
                                incoming.computeIfAbsent(extId, k -> new LinkedHashSet<>())
                                        .add(callerId);
                            }
                        }
                    }
                }
            }
        }

        return new GlobalCallGraph(outgoing, incoming, externalNodes, externalReasons);
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
