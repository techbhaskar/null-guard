package com.nullguard.core.parser;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Bounded post-construction facts, never assumptions about constructor callbacks. */
final class ConstructorFieldFacts {
    private record Facts(Set<String> fields, Set<String> earlyMethods) { }
    private static final DataKey<Facts> CACHE = new DataKey<>() { };
    private static final Facts NONE = new Facts(Set.of(), Set.of());
    private static final class State {
        final Map<String, Boolean> fields = new LinkedHashMap<>(), locals = new LinkedHashMap<>();
        State copy() { var s = new State(); s.fields.putAll(fields); s.locals.putAll(locals); return s; }
    }
    private final ClassOrInterfaceDeclaration owner;
    private final Set<String> early = new LinkedHashSet<>(), audited = new HashSet<>();
    private int work;

    private ConstructorFieldFacts(ClassOrInterfaceDeclaration owner) { this.owner = owner; }

    static Set<String> forMethod(MethodDeclaration method) {
        if (method.isStatic()) return Set.of();
        // Deferred/local bodies do not share this method's lexical receiver and
        // parameter scope. Do not feed outer-field facts into their analysis.
        if (!method.findAll(LambdaExpr.class).isEmpty() || !method.findAll(ClassOrInterfaceDeclaration.class).isEmpty()
                || method.findAll(ObjectCreationExpr.class).stream().anyMatch(c -> c.getAnonymousClassBody().isPresent())) return Set.of();
        var owner = method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (owner == null) return Set.of();
        if (!owner.containsData(CACHE)) {
            Facts facts;
            try { facts = new ConstructorFieldFacts(owner).infer(); }
            catch (RuntimeException | LinkageError | StackOverflowError unsupported) { facts = NONE; }
            owner.setData(CACHE, facts);
        }
        var facts = owner.getData(CACHE);
        return facts.earlyMethods().contains(method.getSignature().asString()) ? Set.of() : facts.fields();
    }

    private Facts infer() {
        if (owner.isInterface() || owner.getConstructors().isEmpty()
                || (owner.containsData(ConstructorExpansion.LIMITS) && !owner.getData(ConstructorExpansion.LIMITS).isEmpty())) return NONE;
        Set<String> candidates = new LinkedHashSet<>();
        owner.getFields().stream().filter(f -> f.isFinal() && !f.isStatic()).forEach(f -> f.getVariables().stream()
                .filter(v -> !v.getType().isPrimitiveType()).forEach(v -> candidates.add(v.getNameAsString())));
        if (candidates.isEmpty()) return NONE;
        if (!emptySuper(owner, new HashSet<>())) return NONE;
        // Java deserialization can restore fields without running these constructors.
        if (owner.resolve().getAllAncestors().stream().anyMatch(t -> t.getQualifiedName().equals("java.io.Serializable")
                || t.getQualifiedName().equals("java.io.Externalizable"))) return NONE;
        for (var constructor : owner.getConstructors()) audit(constructor.getBody(), 0);
        var exits = new ArrayList<State>();
        for (var constructor : owner.getConstructors()) {
            var start = new State(); candidates.forEach(f -> start.fields.put(f, false));
            constructor.getParameters().forEach(p -> start.locals.put(p.getNameAsString(), false));
            exits.addAll(construct(constructor, start, new HashSet<>()));
        }
        if (exits.isEmpty()) return NONE;
        candidates.removeIf(f -> exits.stream().anyMatch(s -> !s.fields.getOrDefault(f, false)));
        return new Facts(Set.copyOf(candidates), Set.copyOf(early));
    }

    /** Unknown/nontrivial superclass construction may call an override before fields exist. */
    private static boolean emptySuper(ClassOrInterfaceDeclaration type, Set<String> seen) {
        if (type.getExtendedTypes().isEmpty()) return true;
        if (seen.size() >= 8 || !seen.add(type.getFullyQualifiedName().orElse(type.getNameAsString()))) return false;
        var parent = type.getExtendedTypes().get(0).resolve().asReferenceType();
        if (parent.getQualifiedName().equals("java.lang.Object")) return true;
        var source = parent.getTypeDeclaration().orElseThrow().toAst(ClassOrInterfaceDeclaration.class).orElseThrow();
        return source.getFields().stream().allMatch(FieldDeclaration::isStatic)
                && source.getMembers().stream().noneMatch(m -> m instanceof InitializerDeclaration i && !i.isStatic())
                && source.getConstructors().stream().allMatch(c -> c.getBody().isEmpty()) && emptySuper(source, seen);
    }

    private void audit(Node body, int depth) {
        if (depth > 6 || ++work > 500) throw new IllegalStateException("construction audit budget");
        if (!body.findAll(LambdaExpr.class).isEmpty() || !body.findAll(MethodReferenceExpr.class).isEmpty())
            throw new IllegalStateException("construction callback/escape");
        for (var self : body.findAll(ThisExpr.class)) {
            var parent = self.getParentNode().orElseThrow();
            boolean qualifier = parent instanceof FieldAccessExpr field && field.getScope() == self
                    || parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == self).isPresent();
            if (self.getTypeName().isPresent() || !qualifier)
                throw new IllegalStateException("this escapes construction");
        }
        for (var creation : body.findAll(ObjectCreationExpr.class)) {
            if (creation.getAnonymousClassBody().isPresent()) throw new IllegalStateException("anonymous construction capture");
            var type = creation.resolve().declaringType();
            if (!type.getQualifiedName().startsWith("java.")) {
                var source = type.toAst(ClassOrInterfaceDeclaration.class).orElseThrow();
                if (source.findAncestor(ClassOrInterfaceDeclaration.class).isPresent() && !source.isStatic())
                    throw new IllegalStateException("inner construction capture");
            }
        }
        for (var call : body.findAll(MethodCallExpr.class)) {
            // Explicit non-this receivers cannot publish this unless an argument does;
            // direct/implicit captures are rejected above.
            if (call.getScope().isPresent() && !call.getScope().get().isThisExpr() && !call.getScope().get().isSuperExpr()) continue;
            var resolved = call.resolve();
            if (resolved.isStatic()) continue;
            if (!resolved.declaringType().getQualifiedName().equals(owner.getFullyQualifiedName().orElse(owner.getNameAsString())))
                throw new IllegalStateException("unknown construction dispatch");
            var source = resolved.toAst(MethodDeclaration.class).orElseThrow();
            if (!source.isPrivate() && !source.isFinal() && !owner.isFinal())
                throw new IllegalStateException("overridable construction dispatch");
            early.add(source.getSignature().asString());
            if (audited.add(source.getSignature().asString())) audit(source.getBody().orElseThrow(), depth + 1);
        }
    }

    private List<State> construct(ConstructorDeclaration constructor, State input, Set<String> stack) {
        String signature = constructor.getSignature().asString();
        if (stack.size() >= 8 || !stack.add(signature)) throw new IllegalStateException("constructor cycle/budget");
        var exits = new ArrayList<State>();
        exits.addAll(statements(constructor.getBody().getStatements(), List.of(input), exits, stack));
        stack.remove(signature);
        return exits;
    }

    private List<State> statements(List<Statement> statements, List<State> live, List<State> exits, Set<String> stack) {
        for (var statement : statements) {
            if (++work > 500) throw new IllegalStateException("construction flow budget");
            var next = new ArrayList<State>();
            for (var state : live) {
                if (statement instanceof BlockStmt block) {
                    Set<String> previous = Set.copyOf(state.locals.keySet());
                    var results = statements(block.getStatements(), List.of(state), exits, stack);
                    results.forEach(s -> s.locals.keySet().retainAll(previous));
                    next.addAll(results);
                } else if (statement instanceof ExpressionStmt expression) {
                    assign(expression.getExpression(), state); next.add(state);
                } else if (statement instanceof IfStmt branch) {
                    for (boolean truth : List.of(true, false)) {
                        var copy = state.copy(); refine(copy, branch.getCondition(), truth);
                        next.addAll(statements(List.of(truth ? branch.getThenStmt() : branch.getElseStmt().orElse(new EmptyStmt())), List.of(copy), exits, stack));
                    }
                } else if (statement instanceof ThrowStmt) { /* no normal completion */ }
                else if (statement instanceof ReturnStmt) exits.add(state);
                else if (statement instanceof ExplicitConstructorInvocationStmt invocation) {
                    if (!invocation.isThis()) throw new IllegalStateException("explicit superclass construction");
                    var resolved = invocation.resolve();
                    var declaration = resolved.toAst(ConstructorDeclaration.class).orElseThrow();
                    var target = owner.getConstructors().stream().filter(c -> c.getSignature().asString().equals(declaration.getSignature().asString())).findFirst().orElseThrow();
                    var delegated = state.copy(); delegated.locals.clear();
                    for (int i = 0; i < target.getParameters().size(); i++)
                        delegated.locals.put(target.getParameter(i).getNameAsString(), value(invocation.getArgument(i), state));
                    for (var result : construct(target, delegated, stack)) {
                        result.locals.clear(); result.locals.putAll(state.locals); next.add(result);
                    }
                } else if (statement.isEmptyStmt()) next.add(state);
                else throw new IllegalStateException("unsupported construction flow");
            }
            if (next.size() > 16) throw new IllegalStateException("construction path budget");
            live = next;
        }
        return live;
    }

    private void assign(Expression expression, State state) {
        if (expression instanceof VariableDeclarationExpr declaration) {
            declaration.getVariables().forEach(v -> state.locals.put(v.getNameAsString(), v.getInitializer().map(e -> value(e, state)).orElse(false)));
        } else if (expression instanceof AssignExpr assignment) {
            boolean fact = assignment.getOperator() == AssignExpr.Operator.ASSIGN && value(assignment.getValue(), state);
            var target = assignment.getTarget();
            if (target.isNameExpr() && state.locals.containsKey(target.toString())) state.locals.put(target.toString(), fact);
            else {
                String field = target.isNameExpr() ? target.toString()
                        : target.isFieldAccessExpr() && target.asFieldAccessExpr().getScope().isThisExpr() ? target.asFieldAccessExpr().getNameAsString() : "";
                if (state.fields.containsKey(field)) state.fields.put(field, fact);
            }
        } else value(expression, state);
    }

    private boolean value(Expression expression, State state) {
        if (expression.isEnclosedExpr()) return value(expression.asEnclosedExpr().getInner(), state);
        if (expression.isCastExpr()) return value(expression.asCastExpr().getExpression(), state);
        if (expression.isNullLiteralExpr()) return false;
        if (expression.isLiteralExpr() || expression.isObjectCreationExpr() || expression.isArrayCreationExpr() || expression.isClassExpr()) return true;
        if (expression instanceof NameExpr name) return state.locals.getOrDefault(name.getNameAsString(), state.fields.getOrDefault(name.getNameAsString(), false));
        if (expression instanceof FieldAccessExpr field && field.getScope().isThisExpr()) return state.fields.getOrDefault(field.getNameAsString(), false);
        if (expression instanceof MethodCallExpr call) {
            call.getArguments().forEach(a -> value(a, state));
            var resolved = call.resolve();
            if (!resolved.isStatic()) return false;
            String type = resolved.declaringType().getQualifiedName();
            if (type.equals("java.util.Objects") && resolved.getName().equals("requireNonNull") && !call.getArguments().isEmpty()) {
                if (call.getArgument(0).isNameExpr() && state.locals.containsKey(call.getArgument(0).toString())) state.locals.put(call.getArgument(0).toString(), true);
                return true;
            }
            return type.equals("java.util.Arrays") && resolved.getName().equals("asList");
        }
        return false;
    }

    private static void refine(State state, Expression condition, boolean truth) {
        if (condition.isEnclosedExpr()) { refine(state, condition.asEnclosedExpr().getInner(), truth); return; }
        if (!(condition instanceof BinaryExpr binary)) return;
        if (binary.getOperator() != BinaryExpr.Operator.EQUALS && binary.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return;
        Expression name = binary.getLeft().isNullLiteralExpr() ? binary.getRight() : binary.getRight().isNullLiteralExpr() ? binary.getLeft() : null;
        if (name != null && name.isNameExpr() && state.locals.containsKey(name.toString()))
            state.locals.put(name.toString(), truth == (binary.getOperator() == BinaryExpr.Operator.NOT_EQUALS));
    }
}
