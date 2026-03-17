package com.nullguard.callgraph.builder;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.analysis.ir.Instruction;
import com.nullguard.analysis.ir.MethodCallInstruction;
import com.nullguard.analysis.extractor.BasicInstructionExtractor;
import com.nullguard.callgraph.model.GlobalCallGraph;
import com.nullguard.callgraph.resolver.MethodResolver;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

public final class BasicCallGraphBuilder implements CallGraphBuilder {
    private final MethodResolver resolver;
    private final BasicInstructionExtractor extractor;
    
    public BasicCallGraphBuilder() {
        this.resolver = new MethodResolver();
        this.extractor = new BasicInstructionExtractor();
    }
    
    @Override
    public GlobalCallGraph build(ProjectModel project) {
        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashSet<String>> incoming = new LinkedHashMap<>();
        LinkedHashSet<String> externalNodes = new LinkedHashSet<>();
        
        for (ModuleModel module : project.getModules().values()) {
            for (PackageModel pkg : module.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel mth : cls.getMethods().values()) {
                        String callerId = pkg.getPackageName() + "." + cls.getClassName() + "#" + mth.getSignature();
                        outgoing.putIfAbsent(callerId, new LinkedHashSet<>());
                        incoming.putIfAbsent(callerId, new LinkedHashSet<>());
                        
                        if (mth.getControlFlowModel().isPresent()) {
                            List<Instruction> instructions = extractor.extract(mth.getControlFlowModel().get());
                            for (Instruction inst : instructions) {
                                if (inst instanceof MethodCallInstruction callInst) {
                                    String calledName = extractCalledName(callInst.methodCall());
                                    if (isGetterOrSetter(calledName)) continue;
                                    // resolveAll returns concrete implementations first, so Spring's
                                    // controller → serviceInterface → serviceImpl pattern is handled:
                                    // edges are added to ALL concrete implementations, not just the interface.
                                    List<String> targets = resolver.resolveAll(project, calledName);

                                    if (!targets.isEmpty()) {
                                        for (String calleeId : targets) {
                                            outgoing.get(callerId).add(calleeId);
                                            incoming.computeIfAbsent(calleeId, k -> new LinkedHashSet<>()).add(callerId);
                                        }
                                    } else {
                                        String extId = "ext#" + calledName;
                                        externalNodes.add(extId);
                                        outgoing.get(callerId).add(extId);
                                        incoming.computeIfAbsent(extId, k -> new LinkedHashSet<>()).add(callerId);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return new GlobalCallGraph(outgoing, incoming, externalNodes);
    }
    
    private String extractCalledName(String methodCallString) {
        int parenIndex = methodCallString.indexOf('(');
        if (parenIndex == -1) return methodCallString.trim();
        return methodCallString.substring(0, parenIndex).trim();
    }

    /**
     * Returns true for trivial accessor calls like "dataVO.getInvoiceNumber",
     * "br.setAm", or "isActive" that add noise without architectural meaning.
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
