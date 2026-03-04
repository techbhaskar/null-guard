package com.nullguard.core.builder;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.nullguard.core.cfg.ControlFlowEdge;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.EdgeType;
import com.nullguard.core.cfg.NodeType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

public final class BasicControlFlowBuilder implements ControlFlowBuilder {

    @Override
    public ControlFlowModel build(MethodDeclaration method) {
        String methodName = method.getNameAsString();
        String signature = method.getSignature().asString();
        
        LinkedHashMap<String, ControlFlowNode> nodes = new LinkedHashMap<>();
        LinkedHashSet<ControlFlowEdge> edges = new LinkedHashSet<>();

        // Entry node source = annotation lines + access modifier prefix.
        // FlowPathExtractor reads this to detect REST annotations (@GetMapping etc.)
        // and to determine access visibility (exclude private/protected methods from API detection).
        StringBuilder entrySource = new StringBuilder();
        method.getAnnotations().forEach(ann -> entrySource.append(ann.toString()).append(" "));
        method.getModifiers().forEach(mod -> entrySource.append(mod.toString()).append(" "));
        entrySource.append(method.getNameAsString());

        String entryId = methodName + "_0_0";
        ControlFlowNode entryNode = new ControlFlowNode(entryId, NodeType.ENTRY, entrySource.toString().trim(), 0);
        nodes.put(entryId, entryNode);

        String exitId = methodName + "_-1_0";
        ControlFlowNode exitNode = new ControlFlowNode(exitId, NodeType.EXIT, "EXIT", -1);

        String prevId = entryId;
        int index = 1;

        if (method.getBody().isPresent()) {
            BlockStmt body = method.getBody().get();
            for (Statement stmt : body.getStatements()) {
                int line = stmt.getBegin().map(p -> p.line).orElse(0);
                String id = methodName + "_" + line + "_" + (index++);
                
                NodeType type = stmt instanceof ReturnStmt ? NodeType.RETURN : NodeType.STATEMENT;
                ControlFlowNode node = new ControlFlowNode(id, type, stmt.toString(), line);
                nodes.put(id, node);
                
                edges.add(new ControlFlowEdge(prevId, id, EdgeType.NORMAL));
                prevId = id;
                
                if (type == NodeType.RETURN) {
                    edges.add(new ControlFlowEdge(id, exitId, EdgeType.NORMAL));
                    break;
                }
            }
        }

        if (!nodes.containsKey(prevId) || nodes.get(prevId).getType() != NodeType.RETURN) {
            edges.add(new ControlFlowEdge(prevId, exitId, EdgeType.NORMAL));
        }

        nodes.put(exitId, exitNode);

        return new ControlFlowModel(signature, nodes, edges, entryId, exitId);
    }
}
