package com.nullguard.analysis.extractor;

import com.nullguard.analysis.ir.AssignmentInstruction;
import com.nullguard.analysis.ir.ConditionalInstruction;
import com.nullguard.analysis.ir.DereferenceInstruction;
import com.nullguard.analysis.ir.Instruction;
import com.nullguard.analysis.ir.MethodCallInstruction;
import com.nullguard.analysis.ir.ReturnInstruction;
import com.nullguard.analysis.ir.ThrowInstruction;
import com.nullguard.core.callsite.CallSite;
import com.nullguard.core.callsite.CallSiteExtractor;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Maps CFG nodes to the typed instruction stream consumed by null-state analysis.
 *
 * <p>Structural call discovery is delegated to core's AST-backed {@link CallSiteExtractor} so
 * the call graph and data-flow analysis see the same nested, returned and conditional calls.
 */
public final class BasicInstructionExtractor implements InstructionExtractor {

    /** {@link AssignmentInstruction#source()} marker for the literal {@code null}. */
    public static final String NULL_LITERAL = "NULL_LITERAL";
    /** Marker for a method-call result whose nullability is not yet known. */
    public static final String CALL_RESULT = "CALL_RESULT";
    /** Marker for a literal, constructor or expression known not to be a call result. */
    public static final String NON_NULL = "NON_NULL";

    private static final Pattern OR_ELSE_NULL_PATTERN =
            Pattern.compile("\\.orElse\\(\\s*null\\s*\\)");

    private static final Pattern ASSIGNMENT_TARGET_PATTERN =
            Pattern.compile("(?:[\\w<>\\[\\],\\s]+\\s+)?([\\w$]+)\\s*(?:[+\\-*/%&|^]?=)(?!=)");

    @Override
    public List<Instruction> extract(ControlFlowModel cfg) {
        List<Instruction> instructions = new ArrayList<>();
        int instructionIndex = 0;

        Map<String, List<CallSite>> callsByNode = new CallSiteExtractor().extract(cfg).stream()
                .collect(Collectors.groupingBy(
                        CallSite::cfgNodeId,
                        LinkedHashMap::new,
                        Collectors.toList()));

        for (ControlFlowNode node : cfg.getNodes().values()) {
            String source = node.getSourceCode().trim();
            String cfgNodeId = node.getId();
            String baseId = cfg.getMethodSignature() + "_" + cfgNodeId + "_";
            int line = node.getLineNumber();
            List<CallSite> calls = callsByNode.getOrDefault(cfgNodeId, List.of());

            switch (node.getType()) {
                case STATEMENT -> {
                    if (CallSiteExtractor.isAssignment(source)) {
                        instructionIndex = emitDereferences(
                                calls, instructions, baseId, cfgNodeId, line, instructionIndex);

                        String assignmentSource;
                        if (isNullLiteralRhs(source)) {
                            assignmentSource = NULL_LITERAL;
                        } else if (!calls.isEmpty()) {
                            assignmentSource = CALL_RESULT;
                        } else {
                            assignmentSource = NON_NULL;
                        }
                        instructions.add(new AssignmentInstruction(
                                baseId + (instructionIndex++), cfgNodeId, line,
                                extractTarget(source), assignmentSource));

                        instructionIndex = emitMethodCalls(
                                calls, instructions, baseId, cfgNodeId, line, instructionIndex);
                    } else {
                        instructionIndex = emitDereferences(
                                calls, instructions, baseId, cfgNodeId, line, instructionIndex);
                        instructionIndex = emitMethodCalls(
                                calls, instructions, baseId, cfgNodeId, line, instructionIndex);
                    }
                }
                case RETURN -> {
                    instructionIndex = emitDereferences(
                            calls, instructions, baseId, cfgNodeId, line, instructionIndex);
                    instructionIndex = emitMethodCalls(
                            calls, instructions, baseId, cfgNodeId, line, instructionIndex);

                    String returnValue = source.replaceFirst("(?i)^return\\s*", "")
                            .replace(";", "")
                            .trim();
                    instructions.add(new ReturnInstruction(
                            baseId + (instructionIndex++), cfgNodeId, line,
                            "null".equals(returnValue) ? NULL_LITERAL : returnValue));
                }
                case CONDITION -> {
                    instructionIndex = emitDereferences(
                            calls, instructions, baseId, cfgNodeId, line, instructionIndex);
                    instructionIndex = emitMethodCalls(
                            calls, instructions, baseId, cfgNodeId, line, instructionIndex);
                    instructions.add(new ConditionalInstruction(
                            baseId + (instructionIndex++), cfgNodeId, line, source));
                }
                case THROW -> {
                    instructionIndex = emitDereferences(
                            calls, instructions, baseId, cfgNodeId, line, instructionIndex);
                    instructionIndex = emitMethodCalls(
                            calls, instructions, baseId, cfgNodeId, line, instructionIndex);
                    instructions.add(new ThrowInstruction(
                            baseId + (instructionIndex++), cfgNodeId, line, source));
                }
                default -> {
                    // ENTRY and EXIT nodes do not represent executable instructions.
                }
            }
        }

        return Collections.unmodifiableList(instructions);
    }

    private static int emitDereferences(List<CallSite> calls,
                                        List<Instruction> instructions,
                                        String baseId,
                                        String cfgNodeId,
                                        int line,
                                        int instructionIndex) {
        for (CallSite call : calls) {
            String receiver = call.receiver();
            if (!isDereferenceReceiver(receiver)) continue;
            instructions.add(new DereferenceInstruction(
                    baseId + (instructionIndex++), cfgNodeId, line, receiver));
        }
        return instructionIndex;
    }

    private static int emitMethodCalls(List<CallSite> calls,
                                       List<Instruction> instructions,
                                       String baseId,
                                       String cfgNodeId,
                                       int line,
                                       int instructionIndex) {
        for (CallSite call : calls) {
            instructions.add(new MethodCallInstruction(
                    baseId + (instructionIndex++), cfgNodeId, line,
                    call.calleeName(), call.argCount()));
        }
        return instructionIndex;
    }

    private static boolean isDereferenceReceiver(String receiver) {
        return receiver != null
                && !receiver.isEmpty()
                && !"this".equals(receiver)
                && !"super".equals(receiver)
                && !Character.isUpperCase(receiver.charAt(0));
    }

    private static boolean isNullLiteralRhs(String source) {
        if (OR_ELSE_NULL_PATTERN.matcher(source).find()) return true;
        int assignment = CallSiteExtractor.indexOfAssignmentOperator(source);
        if (assignment < 0) return false;
        String rhs = source.substring(assignment + 1).trim();
        return rhs.equals("null")
                || rhs.equals("null;")
                || rhs.startsWith("null ")
                || rhs.startsWith("null,")
                || rhs.startsWith("null)");
    }

    private static String extractTarget(String source) {
        Matcher matcher = ASSIGNMENT_TARGET_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1) : "target";
    }

    /** Backward-compatible raw-text argument counter. */
    static int countArguments(String source, String callee) {
        return CallSiteExtractor.countArguments(source, callee);
    }
}
