package com.nullguard.core.builder;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.*;
import com.nullguard.core.cfg.ControlFlowEdge;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.cfg.ControlFlowNode;
import com.nullguard.core.cfg.EdgeType;
import com.nullguard.core.cfg.NodeType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * BasicControlFlowBuilder — builds a real control-flow graph for a method.
 *
 * <h3>What this replaces</h3>
 * The previous implementation walked statements with a single {@code prev} cursor and a
 * method-global {@code hitReturn} flag. That had two consequences that made the whole
 * analysis pipeline unsound:
 *
 * <ul>
 *   <li>Every node got exactly one predecessor and one {@link EdgeType#NORMAL} out-edge, so
 *       {@code TRUE_BRANCH}, {@code FALSE_BRANCH} and {@code EXCEPTION} were declared but
 *       never constructed. Then- and else-branches were concatenated into one straight line,
 *       so the analyser saw {@code if (c) x = null; else x = new Foo();} as two sequential
 *       writes and simply took the last one. Swapping the branches changed the result.</li>
 *   <li>{@code hitReturn} was set by the <em>first</em> return or throw at <em>any</em> nesting
 *       depth and then aborted the whole walk. For the standard guard-clause idiom —
 *       {@code if (a == null) { return; }} — everything after the guard was silently dropped
 *       from the CFG and never analysed. That is precisely where null bugs live.</li>
 * </ul>
 *
 * <h3>How it works now</h3>
 * The walker is written in terms of <em>flow sets</em>. {@link #walk} takes the set of
 * {@link Flow} edges entering a statement and returns the set leaving it. A {@code Flow} is a
 * pending edge: a source node id plus the {@link EdgeType} to label it with once the successor
 * exists. Joins fall out for free — a statement following an if/else simply receives more than
 * one incoming flow — and a statement that terminates abruptly (return/throw/break/continue)
 * returns an empty set, so the code after it is correctly treated as unreachable <em>locally</em>
 * without aborting the enclosing walk.
 *
 * <p>Loops emit a {@link EdgeType#BACK_EDGE} from the body (or the update clause) to the loop
 * head, which is what lets the downstream worklist analysis iterate to a fixpoint instead of
 * making a single linear pass.
 *
 * <h3>Deliberate approximations</h3>
 * <ul>
 *   <li>Exception edges from a {@code try} block go to the first node of every catch clause,
 *       from every node in the block. That is the standard conservative model; it does not
 *       attempt to match exception types to catch parameters.</li>
 *   <li>{@code finally} is walked once on the normal path. Its duplication onto every abrupt
 *       path is not modelled.</li>
 *   <li>Lambda and anonymous-class bodies are not descended into; they remain part of their
 *       enclosing statement's source text.</li>
 * </ul>
 *
 * <p>This class is stateless and therefore safe to share; all mutable state lives in
 * {@link Ctx}, which is created per {@link #build(MethodDeclaration)} call.
 */
public final class BasicControlFlowBuilder implements ControlFlowBuilder {

    /**
     * A pending control-flow edge: the node it leaves from, and the label to apply when its
     * successor is created.
     */
    private record Flow(String fromNodeId, EdgeType type) { }

    /** Break/continue targets for the innermost enclosing loop or switch. */
    private static final class BreakScope {
        private final List<Flow> breaks = new ArrayList<>();
        private final List<Flow> continues = new ArrayList<>();
        /** Switch statements accept break but not continue. */
        private final boolean acceptsContinue;

        BreakScope(boolean acceptsContinue) {
            this.acceptsContinue = acceptsContinue;
        }
    }

    /** Per-build mutable state. */
    private static final class Ctx {
        final LinkedHashMap<String, ControlFlowNode> nodes = new LinkedHashMap<>();
        final LinkedHashSet<ControlFlowEdge> edges = new LinkedHashSet<>();
        final Deque<BreakScope> breakScopes = new ArrayDeque<>();
        /** Catch-clause entry nodes of the enclosing try blocks, innermost last. */
        final Deque<List<String>> catchTargets = new ArrayDeque<>();
        final String methodName;
        final String exitId;
        int nextIndex = 1;
        final java.util.Map<String, com.nullguard.core.model.SourceLocation> ranges = new java.util.HashMap<>();

        Ctx(String methodName, String exitId) {
            this.methodName = methodName;
            this.exitId = exitId;
        }
    }

    @Override
    public ControlFlowModel build(MethodDeclaration method) {
        String methodName = method.getNameAsString();
        String signature = method.getSignature().asString();

        String exitId = methodName + "_-1_0";
        Ctx ctx = new Ctx(methodName, exitId);
        method.walk(node -> node.getRange().ifPresent(r -> ctx.ranges.putIfAbsent(r.begin.line + "|" + node.toString().trim(),
                new com.nullguard.core.model.SourceLocation("", r.begin.line, r.begin.column, r.end.line, r.end.column))));

        // ── Entry node ────────────────────────────────────────────────────────────
        // Source text = annotations + modifiers + name. FlowPathExtractor reads this to
        // detect REST annotations and visibility, so the format must not change.
        StringBuilder entrySource = new StringBuilder();
        method.getAnnotations().forEach(ann -> entrySource.append(ann.toString()).append(" "));
        method.getModifiers().forEach(mod -> entrySource.append(mod.toString()).append(" "));
        entrySource.append(methodName);

        String entryId = methodName + "_0_0";
        ctx.nodes.put(entryId, new ControlFlowNode(entryId, NodeType.ENTRY, entrySource.toString().trim(), 0));

        List<Flow> out = List.of(new Flow(entryId, EdgeType.NORMAL));
        if (method.getBody().isPresent()) {
            out = walk(method.getBody().get(), ctx, out);
        }

        // ── Exit node ─────────────────────────────────────────────────────────────
        // Every flow still live at the end of the body falls through to EXIT. Flows that
        // already terminated (return/throw) wired themselves to EXIT at the time.
        ctx.nodes.put(exitId, new ControlFlowNode(exitId, NodeType.EXIT, "EXIT", -1));
        connect(ctx, out, exitId);

        return new ControlFlowModel(signature, ctx.nodes, ctx.edges, entryId, exitId);
    }

    // ── Statement dispatch ────────────────────────────────────────────────────────

    /**
     * Walks one statement.
     *
     * @param in  flows entering the statement
     * @return flows leaving it; empty means control cannot fall out of this statement
     *         (it returned, threw, or jumped)
     */
    private List<Flow> walk(Statement stmt, Ctx ctx, List<Flow> in) {
        if (stmt instanceof BlockStmt block) {
            return walkSequence(block.getStatements(), ctx, in);

        } else if (stmt instanceof IfStmt ifStmt) {
            return walkIf(ifStmt, ctx, in);

        } else if (stmt instanceof WhileStmt whileStmt) {
            return walkWhile(whileStmt, ctx, in);

        } else if (stmt instanceof ForStmt forStmt) {
            return walkFor(forStmt, ctx, in);

        } else if (stmt instanceof ForEachStmt forEach) {
            return walkForEach(forEach, ctx, in);

        } else if (stmt instanceof DoStmt doStmt) {
            return walkDo(doStmt, ctx, in);

        } else if (stmt instanceof SwitchStmt switchStmt) {
            return walkSwitch(switchStmt, ctx, in);

        } else if (stmt instanceof TryStmt tryStmt) {
            return walkTry(tryStmt, ctx, in);

        } else if (stmt instanceof ReturnStmt) {
            String id = addNode(ctx, NodeType.RETURN, text(stmt), line(stmt), in);
            edge(ctx, id, ctx.exitId, EdgeType.NORMAL);
            return List.of();

        } else if (stmt instanceof ThrowStmt) {
            // A throw either enters an enclosing catch or leaves the method. The previous
            // implementation added the node and wired no out-edge at all, leaving it dangling.
            String id = addNode(ctx, NodeType.THROW, text(stmt), line(stmt), in);
            List<String> catches = ctx.catchTargets.peekLast();
            if (catches != null && !catches.isEmpty()) {
                for (String target : catches) {
                    edge(ctx, id, target, EdgeType.EXCEPTION);
                }
            } else {
                edge(ctx, id, ctx.exitId, EdgeType.EXCEPTION);
            }
            return List.of();

        } else if (stmt instanceof BreakStmt) {
            String id = addNode(ctx, NodeType.STATEMENT, text(stmt), line(stmt), in);
            BreakScope scope = ctx.breakScopes.peekLast();
            if (scope != null) {
                scope.breaks.add(new Flow(id, EdgeType.NORMAL));
            } else {
                edge(ctx, id, ctx.exitId, EdgeType.NORMAL);
            }
            return List.of();

        } else if (stmt instanceof ContinueStmt) {
            String id = addNode(ctx, NodeType.STATEMENT, text(stmt), line(stmt), in);
            BreakScope scope = enclosingLoop(ctx);
            if (scope != null) {
                scope.continues.add(new Flow(id, EdgeType.NORMAL));
            } else {
                edge(ctx, id, ctx.exitId, EdgeType.NORMAL);
            }
            return List.of();

        } else if (stmt instanceof LabeledStmt labeled) {
            // Labels are recorded as a node so the source text survives; labelled
            // break/continue still resolve to the innermost scope (a known approximation).
            List<Flow> afterLabel = List.of(new Flow(
                    addNode(ctx, NodeType.STATEMENT, labeled.getLabel().asString() + ":", line(stmt), in),
                    EdgeType.NORMAL));
            return walk(labeled.getStatement(), ctx, afterLabel);

        } else if (stmt instanceof SynchronizedStmt sync) {
            String id = addNode(ctx, NodeType.STATEMENT,
                    "synchronized (" + sync.getExpression() + ")", line(stmt), in);
            return walk(sync.getBody(), ctx, List.of(new Flow(id, EdgeType.NORMAL)));

        } else if (stmt instanceof AssertStmt assertStmt) {
            // An assert is a conditional that may throw; model the check, then fall through.
            String id = addNode(ctx, NodeType.CONDITION,
                    assertStmt.getCheck().toString(), line(stmt), in);
            return List.of(new Flow(id, EdgeType.TRUE_BRANCH));

        } else if (stmt instanceof EmptyStmt) {
            return in;

        } else {
            // ExpressionStmt, LocalClassDeclarationStmt, ExplicitConstructorInvocationStmt, ...
            return List.of(new Flow(
                    addNode(ctx, NodeType.STATEMENT, text(stmt), line(stmt), in), EdgeType.NORMAL));
        }
    }

    private List<Flow> walkSequence(List<Statement> statements, Ctx ctx, List<Flow> in) {
        List<Flow> current = in;
        for (Statement stmt : statements) {
            // An empty flow set means the preceding statement terminated abruptly. The rest
            // of THIS block is unreachable, but — unlike the old method-global hitReturn —
            // the enclosing walk continues normally.
            if (current.isEmpty()) break;
            current = walk(stmt, ctx, current);
        }
        return current;
    }

    // ── Composite statements ──────────────────────────────────────────────────────

    private List<Flow> walkIf(IfStmt ifStmt, Ctx ctx, List<Flow> in) {
        String cond = addNode(ctx, NodeType.CONDITION,
                ifStmt.getCondition().toString(), line(ifStmt), in);

        List<Flow> thenOut = walk(ifStmt.getThenStmt(), ctx,
                List.of(new Flow(cond, EdgeType.TRUE_BRANCH)));

        List<Flow> elseIn = List.of(new Flow(cond, EdgeType.FALSE_BRANCH));
        List<Flow> elseOut = ifStmt.getElseStmt().isPresent()
                ? walk(ifStmt.getElseStmt().get(), ctx, elseIn)
                : elseIn;   // no else: the false edge flows straight to the join

        return merge(thenOut, elseOut);
    }

    private List<Flow> walkWhile(WhileStmt stmt, Ctx ctx, List<Flow> in) {
        String head = addNode(ctx, NodeType.CONDITION, stmt.getCondition().toString(), line(stmt), in);
        BreakScope scope = pushLoop(ctx);

        List<Flow> bodyOut = walk(stmt.getBody(), ctx, List.of(new Flow(head, EdgeType.TRUE_BRANCH)));

        ctx.breakScopes.removeLast();
        backEdge(ctx, merge(bodyOut, scope.continues), head);
        return merge(List.of(new Flow(head, EdgeType.FALSE_BRANCH)), scope.breaks);
    }

    private List<Flow> walkFor(ForStmt stmt, Ctx ctx, List<Flow> in) {
        // The initialisation and update clauses used to be discarded entirely, so
        // `for (Node n = head; n != null; n = n.next)` lost both `n = head` and `n = n.next`.
        List<Flow> afterInit = walkExpressions(stmt.getInitialization(), ctx, in);

        String condText = stmt.getCompare().map(Expression::toString).orElse("true");
        String head = addNode(ctx, NodeType.CONDITION, condText, line(stmt), afterInit);
        BreakScope scope = pushLoop(ctx);

        List<Flow> bodyOut = walk(stmt.getBody(), ctx, List.of(new Flow(head, EdgeType.TRUE_BRANCH)));

        ctx.breakScopes.removeLast();
        List<Flow> beforeUpdate = merge(bodyOut, scope.continues);
        List<Flow> afterUpdate = walkExpressions(stmt.getUpdate(), ctx, beforeUpdate);
        backEdge(ctx, afterUpdate, head);

        return merge(List.of(new Flow(head, EdgeType.FALSE_BRANCH)), scope.breaks);
    }

    private List<Flow> walkForEach(ForEachStmt stmt, Ctx ctx, List<Flow> in) {
        String head = addNode(ctx, NodeType.CONDITION,
                stmt.getVariable() + " : " + stmt.getIterable(), line(stmt), in);
        BreakScope scope = pushLoop(ctx);

        List<Flow> bodyOut = walk(stmt.getBody(), ctx, List.of(new Flow(head, EdgeType.TRUE_BRANCH)));

        ctx.breakScopes.removeLast();
        backEdge(ctx, merge(bodyOut, scope.continues), head);
        return merge(List.of(new Flow(head, EdgeType.FALSE_BRANCH)), scope.breaks);
    }

    private List<Flow> walkDo(DoStmt stmt, Ctx ctx, List<Flow> in) {
        BreakScope scope = pushLoop(ctx);

        int mark = ctx.nodes.size();
        List<Flow> bodyOut = walk(stmt.getBody(), ctx, in);
        String firstBodyNode = nodeAt(ctx, mark);

        ctx.breakScopes.removeLast();
        String tail = addNode(ctx, NodeType.CONDITION, stmt.getCondition().toString(),
                line(stmt), merge(bodyOut, scope.continues));

        if (firstBodyNode != null) {
            edge(ctx, tail, firstBodyNode, EdgeType.BACK_EDGE);
        }
        return merge(List.of(new Flow(tail, EdgeType.FALSE_BRANCH)), scope.breaks);
    }

    private List<Flow> walkSwitch(SwitchStmt stmt, Ctx ctx, List<Flow> in) {
        // Previously a switch fell into the catch-all branch and became ONE opaque STATEMENT
        // node whose source text was the entire switch block, which the IR extractor then
        // mis-lowered with its `src.contains("=")` heuristic.
        String selector = addNode(ctx, NodeType.CONDITION,
                "switch (" + stmt.getSelector() + ")", line(stmt), in);
        BreakScope scope = pushSwitch(ctx);

        List<Flow> exits = new ArrayList<>();
        List<Flow> fallThrough = List.of();
        boolean hasDefault = false;

        for (SwitchEntry entry : stmt.getEntries()) {
            if (entry.getLabels().isEmpty()) hasDefault = true;
            List<Flow> entryIn = merge(List.of(new Flow(selector, EdgeType.TRUE_BRANCH)), fallThrough);
            List<Flow> entryOut = walkSequence(entry.getStatements(), ctx, entryIn);
            fallThrough = entryOut;   // Java switch entries fall through unless they break
        }
        exits.addAll(fallThrough);

        ctx.breakScopes.removeLast();
        exits.addAll(scope.breaks);
        if (!hasDefault) {
            // No default: the selector can bypass the whole statement.
            exits.add(new Flow(selector, EdgeType.FALSE_BRANCH));
        }
        return exits;
    }

    private List<Flow> walkTry(TryStmt stmt, Ctx ctx, List<Flow> in) {
        List<Flow> afterResources = walkExpressions(stmt.getResources(), ctx, in);

        // Pre-create the catch entry nodes so throws inside the try body have somewhere to go.
        // Each catch body is walked afterwards, starting from its own entry node.
        List<String> catchEntries = new ArrayList<>();
        for (CatchClause cc : stmt.getCatchClauses()) {
            String id = addNode(ctx, NodeType.STATEMENT,
                    "catch (" + cc.getParameter() + ")", line(cc.getBody()), List.of());
            catchEntries.add(id);
        }

        ctx.catchTargets.addLast(catchEntries);
        int mark = ctx.nodes.size();
        List<Flow> tryOut = walk(stmt.getTryBlock(), ctx, afterResources);
        List<String> tryBodyNodes = nodesFrom(ctx, mark);
        ctx.catchTargets.removeLast();

        // Conservative model: any node in the try body may raise into any catch clause.
        for (String from : tryBodyNodes) {
            for (String target : catchEntries) {
                edge(ctx, from, target, EdgeType.EXCEPTION);
            }
        }

        List<Flow> allOut = new ArrayList<>(tryOut);
        int i = 0;
        for (CatchClause cc : stmt.getCatchClauses()) {
            String entry = catchEntries.get(i++);
            allOut.addAll(walk(cc.getBody(), ctx, List.of(new Flow(entry, EdgeType.NORMAL))));
        }

        if (stmt.getFinallyBlock().isPresent()) {
            return walk(stmt.getFinallyBlock().get(), ctx, allOut);
        }
        return allOut;
    }

    /** Walks a list of bare expressions (for-init, for-update, try-with-resources) as statements. */
    private List<Flow> walkExpressions(List<Expression> expressions, Ctx ctx, List<Flow> in) {
        List<Flow> current = in;
        for (Expression expr : expressions) {
            String src = expr.toString().trim();
            if (!src.endsWith(";")) src = src + ";";
            current = List.of(new Flow(
                    addNode(ctx, NodeType.STATEMENT, src,
                            expr.getBegin().map(p -> p.line).orElse(0), current),
                    EdgeType.NORMAL));
        }
        return current;
    }

    // ── Graph primitives ──────────────────────────────────────────────────────────

    /** Creates a node and wires every incoming flow to it. Returns the new node id. */
    private String addNode(Ctx ctx, NodeType type, String src, int line, List<Flow> in) {
        String id = ctx.methodName + "_" + line + "_" + (ctx.nextIndex++);
        ctx.nodes.put(id, new ControlFlowNode(id, type, src, line, ctx.ranges.get(line + "|" + src.trim())));
        connect(ctx, in, id);
        return id;
    }

    private void connect(Ctx ctx, List<Flow> in, String targetId) {
        for (Flow f : in) {
            edge(ctx, f.fromNodeId(), targetId, f.type());
        }
    }

    /** Wires flows back to a loop head, relabelling them as back-edges. */
    private void backEdge(Ctx ctx, List<Flow> in, String headId) {
        for (Flow f : in) {
            edge(ctx, f.fromNodeId(), headId, EdgeType.BACK_EDGE);
        }
    }

    private void edge(Ctx ctx, String from, String to, EdgeType type) {
        ctx.edges.add(new ControlFlowEdge(from, to, type));
    }

    private static List<Flow> merge(List<Flow> a, List<Flow> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<Flow> merged = new ArrayList<>(a.size() + b.size());
        merged.addAll(a);
        merged.addAll(b);
        return merged;
    }

    private BreakScope pushLoop(Ctx ctx) {
        BreakScope scope = new BreakScope(true);
        ctx.breakScopes.addLast(scope);
        return scope;
    }

    private BreakScope pushSwitch(Ctx ctx) {
        BreakScope scope = new BreakScope(false);
        ctx.breakScopes.addLast(scope);
        return scope;
    }

    /** The innermost scope that a {@code continue} can target — switches do not qualify. */
    private static BreakScope enclosingLoop(Ctx ctx) {
        for (var it = ctx.breakScopes.descendingIterator(); it.hasNext(); ) {
            BreakScope scope = it.next();
            if (scope.acceptsContinue) return scope;
        }
        return null;
    }

    /** The id of the node at insertion position {@code index}, or null if none was added. */
    private static String nodeAt(Ctx ctx, int index) {
        int i = 0;
        for (String id : ctx.nodes.keySet()) {
            if (i++ == index) return id;
        }
        return null;
    }

    /** Every node id added at or after insertion position {@code mark}. */
    private static List<String> nodesFrom(Ctx ctx, int mark) {
        List<String> result = new ArrayList<>();
        int i = 0;
        for (String id : ctx.nodes.keySet()) {
            if (i++ >= mark) result.add(id);
        }
        return result;
    }

    private static String text(Statement stmt) {
        return stmt.toString().trim();
    }

    private static int line(Statement stmt) {
        return stmt.getBegin().map(p -> p.line).orElse(0);
    }
}
