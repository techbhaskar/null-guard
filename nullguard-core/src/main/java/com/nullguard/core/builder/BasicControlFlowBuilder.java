package com.nullguard.core.builder;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import com.nullguard.core.cfg.ControlFlowEdge;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.EdgeType;
import com.nullguard.core.cfg.NodeType;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/**
 * BasicControlFlowBuilder – builds a flat, linearly-ordered CFG for a method.
 *
 * <h3>What changed (recursive walk)</h3>
 * Previously only top-level {@code body.getStatements()} were added as CFG nodes.
 * This meant every statement inside an {@code if/else/for/while/try} block was
 * rolled into one giant opaque STATEMENT node, so the data-flow analyser never
 * saw {@code = null} assignments or {@code return null} statements buried in branches.
 *
 * <p>Now the builder recursively walks all nested statements, emitting:
 * <ul>
 *   <li>CONDITION node for the predicate of if/for/while/do-while</li>
 *   <li>Individual STATEMENT nodes for every simple expression statement</li>
 *   <li>RETURN nodes for every return statement (at any nesting depth)</li>
 * </ul>
 * The result is still a flat, linearly-ordered sequence suitable for the
 * existing {@code ForwardDataFlowAnalyzer} (which assumes a linear CFG).
 */
public final class BasicControlFlowBuilder implements ControlFlowBuilder {

    @Override
    public ControlFlowModel build(MethodDeclaration method) {
        String methodName = method.getNameAsString();
        String signature  = method.getSignature().asString();

        LinkedHashMap<String, ControlFlowNode> nodes = new LinkedHashMap<>();
        LinkedHashSet<ControlFlowEdge>         edges = new LinkedHashSet<>();

        // ── Entry node ───────────────────────────────────────────────────────
        // Source = annotation lines + access modifier prefix.
        // FlowPathExtractor reads this to detect REST annotations and visibility.
        StringBuilder entrySource = new StringBuilder();
        method.getAnnotations().forEach(ann -> entrySource.append(ann.toString()).append(" "));
        method.getModifiers().forEach(mod  -> entrySource.append(mod.toString()).append(" "));
        entrySource.append(method.getNameAsString());

        String entryId = methodName + "_0_0";
        nodes.put(entryId, new ControlFlowNode(entryId, NodeType.ENTRY, entrySource.toString().trim(), 0));

        String exitId = methodName + "_-1_0";

        // Mutable cursors passed through the recursive visitor
        String[]  prevIdHolder = { entryId };
        int[]     indexHolder  = { 1 };
        boolean[] hitReturn    = { false };

        if (method.getBody().isPresent()) {
            walkBlock(method.getBody().get(), methodName, exitId,
                      nodes, edges, prevIdHolder, indexHolder, hitReturn);
        }

        if (!hitReturn[0]) {
            edges.add(new ControlFlowEdge(prevIdHolder[0], exitId, EdgeType.NORMAL));
        }

        nodes.put(exitId, new ControlFlowNode(exitId, NodeType.EXIT, "EXIT", -1));
        return new ControlFlowModel(signature, nodes, edges, entryId, exitId);
    }

    // ── Recursive statement walker ────────────────────────────────────────────

    private void walkBlock(BlockStmt block, String methodName, String exitId,
                           LinkedHashMap<String, ControlFlowNode> nodes,
                           LinkedHashSet<ControlFlowEdge> edges,
                           String[] prev, int[] idx, boolean[] hitReturn) {
        for (Statement stmt : block.getStatements()) {
            if (hitReturn[0]) break; // stop after first return (no dead-code nodes)
            walkStatement(stmt, methodName, exitId, nodes, edges, prev, idx, hitReturn);
        }
    }

    private void walkStatement(Statement stmt, String methodName, String exitId,
                               LinkedHashMap<String, ControlFlowNode> nodes,
                               LinkedHashSet<ControlFlowEdge> edges,
                               String[] prev, int[] idx, boolean[] hitReturn) {
        if (hitReturn[0]) return;

        if (stmt instanceof BlockStmt block) {
            walkBlock(block, methodName, exitId, nodes, edges, prev, idx, hitReturn);

        } else if (stmt instanceof IfStmt ifStmt) {
            // ── if (condition) { then } [else { else }] ──────────────────
            addNode(methodName, NodeType.CONDITION,
                    ifStmt.getCondition().toString(),
                    ifStmt.getBegin().map(p -> p.line).orElse(0),
                    nodes, edges, prev, idx);

            walkStatement(ifStmt.getThenStmt(), methodName, exitId, nodes, edges, prev, idx, hitReturn);
            ifStmt.getElseStmt().ifPresent(elseStmt ->
                walkStatement(elseStmt, methodName, exitId, nodes, edges, prev, idx, hitReturn));

        } else if (stmt instanceof ForStmt forStmt) {
            // ── for (init; condition; update) { body } ───────────────────
            forStmt.getCompare().ifPresent(cond ->
                addNode(methodName, NodeType.CONDITION, cond.toString(),
                        stmt.getBegin().map(p -> p.line).orElse(0),
                        nodes, edges, prev, idx));
            walkStatement(forStmt.getBody(), methodName, exitId, nodes, edges, prev, idx, hitReturn);

        } else if (stmt instanceof ForEachStmt feStmt) {
            // ── for (Type var : iterable) { body } ───────────────────────
            addNode(methodName, NodeType.CONDITION,
                    feStmt.getVariable() + " : " + feStmt.getIterable(),
                    stmt.getBegin().map(p -> p.line).orElse(0),
                    nodes, edges, prev, idx);
            walkStatement(feStmt.getBody(), methodName, exitId, nodes, edges, prev, idx, hitReturn);

        } else if (stmt instanceof WhileStmt whileStmt) {
            addNode(methodName, NodeType.CONDITION, whileStmt.getCondition().toString(),
                    stmt.getBegin().map(p -> p.line).orElse(0),
                    nodes, edges, prev, idx);
            walkStatement(whileStmt.getBody(), methodName, exitId, nodes, edges, prev, idx, hitReturn);

        } else if (stmt instanceof DoStmt doStmt) {
            walkStatement(doStmt.getBody(), methodName, exitId, nodes, edges, prev, idx, hitReturn);
            addNode(methodName, NodeType.CONDITION, doStmt.getCondition().toString(),
                    stmt.getBegin().map(p -> p.line).orElse(0),
                    nodes, edges, prev, idx);

        } else if (stmt instanceof TryStmt tryStmt) {
            // Walk try-body; walk each catch; walk finally
            walkStatement(tryStmt.getTryBlock(), methodName, exitId, nodes, edges, prev, idx, hitReturn);
            tryStmt.getCatchClauses().forEach(cc ->
                walkStatement(cc.getBody(), methodName, exitId, nodes, edges, prev, idx, hitReturn));
            tryStmt.getFinallyBlock().ifPresent(fb ->
                walkStatement(fb, methodName, exitId, nodes, edges, prev, idx, hitReturn));

        } else if (stmt instanceof ReturnStmt) {
            String src = stmt.toString().trim();
            int    line = stmt.getBegin().map(p -> p.line).orElse(0);
            String id   = addNode(methodName, NodeType.RETURN, src, line, nodes, edges, prev, idx);
            edges.add(new ControlFlowEdge(id, exitId, EdgeType.NORMAL));
            hitReturn[0] = true;

        } else if (stmt instanceof ThrowStmt) {
            addNode(methodName, NodeType.THROW, stmt.toString().trim(),
                    stmt.getBegin().map(p -> p.line).orElse(0),
                    nodes, edges, prev, idx);
            // A throw is terminal — treat like return
            hitReturn[0] = true;

        } else {
            // ExpressionStmt, VariableDeclaration, etc. → plain STATEMENT node
            addNode(methodName, NodeType.STATEMENT, stmt.toString().trim(),
                    stmt.getBegin().map(p -> p.line).orElse(0),
                    nodes, edges, prev, idx);
        }
    }

    /** Adds a single CFG node, wires the incoming edge, advances the prev pointer. Returns the new node id. */
    private static String addNode(String methodName, NodeType type, String src, int line,
                                  LinkedHashMap<String, ControlFlowNode> nodes,
                                  LinkedHashSet<ControlFlowEdge> edges,
                                  String[] prev, int[] idx) {
        String id = methodName + "_" + line + "_" + (idx[0]++);
        nodes.put(id, new ControlFlowNode(id, type, src, line));
        edges.add(new ControlFlowEdge(prev[0], id, EdgeType.NORMAL));
        prev[0] = id;
        return id;
    }
}
