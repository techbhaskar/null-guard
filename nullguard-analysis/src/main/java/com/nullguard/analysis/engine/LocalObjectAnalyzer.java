package com.nullguard.analysis.engine;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.nullguard.analysis.lattice.NullState;
import com.nullguard.core.model.*;
import java.util.*;

/** Bounded, allocation-sensitive local lifecycle analysis. Unknown effects invalidate state. */
public final class LocalObjectAnalyzer {
    private record Value(NullState state, String object, boolean field) {
        static Value unknown() { return new Value(NullState.UNKNOWN, null, false); }
    }
    private static final class ObjectState {
        String type;
        Map<String, NullState> fields = new LinkedHashMap<>();
        ObjectState copy() { var copy = new ObjectState(); copy.type = type; copy.fields.putAll(fields); return copy; }
    }
    private static final class State {
        Map<String, Value> variables = new LinkedHashMap<>();
        Map<String, ObjectState> heap = new LinkedHashMap<>();
        Set<String> initialized = new HashSet<>();
        Value result = Value.unknown();
        State copy() {
            var copy = new State(); copy.variables.putAll(variables);
            heap.forEach((id, object) -> copy.heap.put(id, object.copy())); copy.initialized.addAll(initialized); return copy;
        }
    }
    private final Map<String, MethodModel> methods = new TreeMap<>();
    private final Map<String, ClassModel> classes = new TreeMap<>();
    private final Set<String> limits = new LinkedHashSet<>();
    private final List<MethodFinding> issues = new ArrayList<>();
    private MethodModel root;
    private int allocation, steps, callLine;

    public void analyze(ProjectModel project) {
        project.getModules().values().forEach(mod -> mod.getPackages().values().forEach(pkg -> pkg.getClasses().values().forEach(cls -> {
            String type = cls.getQualifiedName() == null ? pkg.getPackageName() + "." + cls.getClassName() : cls.getQualifiedName();
            classes.put(type, cls); cls.getMethods().values().forEach(m -> methods.put(MethodIds.of(pkg, cls, m), m));
        })));
        methods.values().forEach(method -> {
            root = method; limits.clear(); issues.clear(); allocation = 0; steps = 0;
            callLine = method.getSourceLocation().map(SourceLocation::startLine).orElse(1);
            if (!method.getBodySource().isEmpty()) {
                var state = new State();
                method.getParameters().forEach(p -> state.variables.put(p.name(), new Value(p.nonNull() || p.primitive() ? NullState.NON_NULL : NullState.UNKNOWN, null, false)));
                execute(method, state, 0);
            }
            method.setLifecycleIssues(issues.stream().distinct().toList());
            method.setLifecycleLimitations(List.copyOf(limits));
        });
    }

    private List<State> execute(MethodModel method, State state, int depth) {
        if (depth > 4 || ++steps > 300) { invalidate(state, "local lifecycle depth/work budget"); return List.of(state); }
        try {
            var exits = new ArrayList<State>();
            BlockStmt body = method.getMethodName().equals("<init>")
                    ? com.github.javaparser.StaticJavaParser.parseBodyDeclaration("Tmp() " + method.getBodySource()).asConstructorDeclaration().getBody()
                    : com.github.javaparser.StaticJavaParser.parseBlock(method.getBodySource());
            var live = statements(body.getStatements(), List.of(state), method, depth, exits);
            exits.addAll(live); return exits;
        } catch (RuntimeException unsupported) { invalidate(state, "unsupported lifecycle statement"); return List.of(state); }
    }

    private List<State> statements(List<Statement> statements, List<State> states, MethodModel method, int depth, List<State> exits) {
        for (var statement : statements) {
            var next = new ArrayList<State>();
            for (var state : states) {
                if (++steps > 300) { invalidate(state, "local lifecycle work budget"); next.add(state); continue; }
                if (statement instanceof BlockStmt block) next.addAll(statements(block.getStatements(), List.of(state), method, depth, exits));
                else if (statement instanceof ExpressionStmt expression) { eval(expression.getExpression(), state, method, depth); next.add(state); }
                else if (statement instanceof ReturnStmt returned) { state.result = returned.getExpression().map(e -> eval(e, state, method, depth)).orElse(Value.unknown()); exits.add(state); }
                else if (statement instanceof ThrowStmt) { /* No normal completion. */ }
                else if (statement instanceof IfStmt conditional) {
                    eval(conditional.getCondition(), state, method, depth);
                    for (boolean truth : List.of(true, false)) {
                        var branch = state.copy();
                        var facts = com.nullguard.analysis.lattice.NullGuardCondition.parse(conditional.getCondition().toString());
                        var refinements = truth ? facts.onTrue() : facts.onFalse();
                        boolean impossible = refinements.entrySet().stream().anyMatch(e -> branch.variables.containsKey(e.getKey())
                                && branch.variables.get(e.getKey()).state() != NullState.UNKNOWN && branch.variables.get(e.getKey()).state() != e.getValue());
                        if (impossible) continue;
                        refinements.forEach((name, value) -> branch.variables.put(name, new Value(value, branch.variables.getOrDefault(name, Value.unknown()).object(), false)));
                        Statement body = truth ? conditional.getThenStmt() : conditional.getElseStmt().orElse(new EmptyStmt());
                        next.addAll(statements(List.of(body), List.of(branch), method, depth, exits));
                    }
                } else if (statement instanceof ExplicitConstructorInvocationStmt invocation) {
                    var args = invocation.getArguments().stream().map(a -> eval(a, state, method, depth)).toList();
                    invoke(target(method, invocation.toString()), state.variables.getOrDefault("@this", Value.unknown()), args, state, depth); next.add(state);
                } else if (statement.isEmptyStmt()) next.add(state);
                else { invalidate(state, "loops, try/switch or asynchronous lifecycle effects"); next.add(state); }
            }
            if (next.size() > 8) { State joined = merge(next); invalidate(joined, "local lifecycle path budget"); next = new ArrayList<>(List.of(joined)); }
            states = next;
        }
        return states;
    }

    private Value eval(Expression expression, State state, MethodModel method, int depth) {
        if (method == root && (expression.isObjectCreationExpr() || expression.isMethodCallExpr())) {
            var lines = method.getSemanticCallSites().stream().filter(s -> s.getExpression().equals(expression.toString()))
                    .map(SemanticCallSite::getLineNumber).filter(line -> line > 0).distinct().toList();
            callLine = lines.size() == 1 ? lines.get(0) : method.getSourceLocation().map(SourceLocation::startLine).orElse(1);
        }
        if (expression instanceof EnclosedExpr e) return eval(e.getInner(), state, method, depth);
        if (expression instanceof CastExpr e) return eval(e.getExpression(), state, method, depth);
        if (expression.isNullLiteralExpr()) return new Value(NullState.NULL, null, false);
        if (expression.isLiteralExpr() || expression.isArrayCreationExpr()) return new Value(NullState.NON_NULL, null, false);
        if (expression.isThisExpr() || expression.isSuperExpr()) return state.variables.getOrDefault("@this", Value.unknown());
        if (expression instanceof NameExpr name) {
            if (state.variables.containsKey(name.getNameAsString())) return state.variables.get(name.getNameAsString());
            return field(state.variables.getOrDefault("@this", Value.unknown()), name.getNameAsString(), state);
        }
        if (expression instanceof VariableDeclarationExpr declaration) {
            for (var variable : declaration.getVariables()) state.variables.put(variable.getNameAsString(), variable.getInitializer().map(e -> eval(e, state, method, depth)).orElse(Value.unknown()));
            return Value.unknown();
        }
        if (expression instanceof FieldAccessExpr access) return field(eval(access.getScope(), state, method, depth), access.getNameAsString(), state);
        if (expression instanceof AssignExpr assignment) {
            Value value = eval(assignment.getValue(), state, method, depth);
            Expression destination = assignment.getTarget();
            if (destination instanceof NameExpr name && state.variables.containsKey(name.getNameAsString())) state.variables.put(name.getNameAsString(), value);
            else {
                Value receiver = destination instanceof FieldAccessExpr access ? eval(access.getScope(), state, method, depth) : state.variables.getOrDefault("@this", Value.unknown());
                String name = destination instanceof FieldAccessExpr access ? access.getNameAsString() : destination.toString();
                if (receiver.object() != null && state.heap.containsKey(receiver.object())) state.heap.get(receiver.object()).fields.put(name, value.state());
                if (value.object() != null) limits.add("nested object fields/escaping allocation aliases");
            }
            return value;
        }
        if (expression instanceof ObjectCreationExpr creation) {
            var args = creation.getArguments().stream().map(a -> eval(a, state, method, depth)).toList();
            var target = target(method, creation.toString());
            if (target == null || !classes.containsKey(target.declaringType())) return new Value(NullState.NON_NULL, null, false);
            String id = "allocation:" + allocation++;
            var object = new ObjectState(); object.type = target.declaringType();
            classes.get(object.type).getFields().stream().filter(f -> !f.staticField()).forEach(f -> object.fields.put(f.name(), f.primitive() ? NullState.NON_NULL : NullState.NULL));
            state.heap.put(id, object);
            Value value = new Value(NullState.NON_NULL, id, false);
            if (!classes.get(object.type).getConstructionLimitations().isEmpty()) {
                invalidate(state, "unsupported constructor generator/options"); return value;
            }
            initialize(object.type, state, depth);
            invoke(target, value, args, state, depth); return value;
        }
        if (expression instanceof MethodCallExpr call) {
            var target = target(method, call.toString());
            Value receiver = call.getScope().map(e -> eval(e, state, method, depth)).orElse(state.variables.getOrDefault("@this", Value.unknown()));
            // Static calls do not dereference their qualifier, even when written
            // through an instance. Still evaluate it for nested instance calls.
            if ((target == null || !target.staticMethod()) && receiver.field() && receiver.state() != NullState.NON_NULL) issues.add(new MethodFinding("NG006", "warning", "Locally constructed object may dereference a null field at " + call, callLine));
            var args = call.getArguments().stream().map(a -> eval(a, state, method, depth)).toList();
            if (target != null && target.declaringType().equals("java.util.Objects") && target.methodName().equals("requireNonNull") && !args.isEmpty()) {
                if (call.getArgument(0).isNameExpr()) state.variables.put(call.getArgument(0).toString(), new Value(NullState.NON_NULL, args.get(0).object(), false));
                return new Value(NullState.NON_NULL, args.get(0).object(), false);
            }
            if (receiver.object() != null || args.stream().anyMatch(a -> a.object() != null)) return invoke(target, receiver, args, state, depth);
            return Value.unknown();
        }
        if (expression instanceof BinaryExpr binary) {
            eval(binary.getLeft(), state, method, depth);
            if (binary.getOperator() == BinaryExpr.Operator.AND || binary.getOperator() == BinaryExpr.Operator.OR) {
                invalidate(state, "short-circuit lifecycle effects");
            } else eval(binary.getRight(), state, method, depth);
            return new Value(NullState.NON_NULL, null, false);
        }
        invalidate(state, "unsupported expression/conditional lifecycle effect"); return Value.unknown();
    }

    private Value invoke(ResolvedCallTarget target, Value receiver, List<Value> args, State state, int depth) {
        var callee = target == null ? null : methods.get(target.qualifiedSignature());
        if (callee == null || callee.getBodySource().isEmpty()) {
            if (receiver.object() != null || args.stream().anyMatch(a -> a.object() != null)) invalidate(state, "unresolved/dependency call or escaping object");
            return Value.unknown();
        }
        if (target.virtualDispatch() && receiver.object() != null && !state.heap.get(receiver.object()).type.equals(target.declaringType())) {
            invalidate(state, "inherited/polymorphic object dispatch"); return Value.unknown();
        }
        var local = state.copy(); local.variables.clear(); local.variables.put("@this", receiver);
        for (int i = 0; i < Math.min(args.size(), callee.getParameters().size()); i++) local.variables.put(callee.getParameters().get(i).name(), args.get(i));
        List<State> exits = execute(callee, local, depth + 1);
        if (exits.isEmpty()) { invalidate(state, "no normal constructor/method exit"); return Value.unknown(); }
        State joined = merge(exits); state.heap = joined.heap; state.initialized = joined.initialized; return joined.result;
    }

    private void initialize(String type, State state, int depth) {
        if (!state.initialized.add(type)) return;
        String id = "static:" + type;
        var object = new ObjectState(); object.type = type;
        classes.get(type).getFields().stream().filter(FieldModel::staticField).forEach(f -> object.fields.put(f.name(), f.primitive() ? NullState.NON_NULL : NullState.NULL));
        state.heap.put(id, object);
        var initializer = classes.get(type).getMethods().values().stream().filter(m -> m.getOrigin().equals("static-initializer")).findFirst().orElse(null);
        if (initializer != null) invoke(new ResolvedCallTarget(type, initializer.getSignature(), initializer.getMethodName(), 0), new Value(NullState.NON_NULL, id, false), List.of(), state, depth);
    }

    private Value field(Value receiver, String name, State state) {
        if (receiver.object() == null || !state.heap.containsKey(receiver.object())) return Value.unknown();
        var object = state.heap.get(receiver.object());
        var statics = state.heap.get("static:" + object.type);
        return new Value(object.fields.getOrDefault(name, statics == null ? NullState.UNKNOWN : statics.fields.getOrDefault(name, NullState.UNKNOWN)), null, true);
    }
    private ResolvedCallTarget target(MethodModel method, String expression) {
        var sites = method.getSemanticCallSites().stream().filter(s -> s.getExpression().equals(expression)).toList();
        if (sites.stream().anyMatch(s -> s.getResolvedTarget().isEmpty())) return null;
        var targets = sites.stream().flatMap(s -> s.getResolvedTarget().stream()).distinct().toList();
        return targets.size() == 1 ? targets.get(0) : null;
    }
    private void invalidate(State state, String reason) {
        state.heap.values().forEach(object -> object.fields.replaceAll((name, value) -> NullState.UNKNOWN));
        limits.add(reason);
    }
    private State merge(List<State> states) {
        State result = states.get(0).copy(); result.result = states.get(0).result;
        for (State other : states.subList(1, states.size())) {
            result.heap.forEach((id, object) -> object.fields.replaceAll((name, value) -> value.merge(other.heap.containsKey(id) ? other.heap.get(id).fields.getOrDefault(name, NullState.UNKNOWN) : NullState.UNKNOWN)));
            other.heap.forEach((id, object) -> result.heap.putIfAbsent(id, object.copy()));
            result.variables.replaceAll((name, value) -> { Value next = other.variables.getOrDefault(name, Value.unknown()); return new Value(value.state().merge(next.state()), Objects.equals(value.object(), next.object()) ? value.object() : null, value.field() || next.field()); });
            result.result = new Value(result.result.state().merge(other.result.state()), Objects.equals(result.result.object(), other.result.object()) ? result.result.object() : null, result.result.field() || other.result.field());
            result.initialized.retainAll(other.initialized);
        }
        return result;
    }
}
