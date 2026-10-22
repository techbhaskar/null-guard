package com.nullguard.core.parser;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** In-memory lowering only. Never runs annotation processors or edits application sources. */
final class ConstructorExpansion {
    static final DataKey<String> ORIGIN = new DataKey<>() {};
    static final DataKey<List<String>> LIMITS = new DataKey<>() {};

    static void expand(ClassOrInterfaceDeclaration owner) {
        if (owner.isInterface()) return;
        List<String> limits = new ArrayList<>();
        owner.setData(LIMITS, limits);
        boolean unsupportedGenerator = List.of("lombok.Builder", "lombok.experimental.SuperBuilder", "lombok.Value").stream().anyMatch(a -> annotated(owner, a));
        if (unsupportedGenerator) limits.add("builder/value constructor generation is not modeled");
        for (String annotation : List.of("RequiredArgsConstructor", "AllArgsConstructor", "NoArgsConstructor", "Data")) {
            if (!annotated(owner, "lombok." + annotation)) continue;
            String options = owner.getAnnotations().stream().filter(a -> a.getName().getIdentifier().equals(annotation)).findFirst().map(Object::toString).orElse("").replaceAll("\\s+", "");
            if (options.contains("staticName=") || options.contains("onConstructor")) limits.add("Lombok factory/custom constructor annotations are not modeled");
            boolean force = options.contains("force=true");
            if (annotation.equals("NoArgsConstructor") && !force && owner.getFields().stream().filter(f -> f.isFinal() && !f.isStatic())
                    .flatMap(f -> f.getVariables().stream()).anyMatch(v -> v.getInitializer().isEmpty())) {
                limits.add("NoArgsConstructor without force cannot initialize blank final fields"); unsupportedGenerator = true; continue;
            }
            if (annotation.equals("Data") && !owner.getConstructors().isEmpty()) continue;
            List<VariableDeclarator> selected = new ArrayList<>();
            for (FieldDeclaration field : owner.getFields()) {
                if (field.isStatic()) continue;
                for (var variable : field.getVariables()) {
                    if (annotation.equals("NoArgsConstructor")) continue;
                    if (field.isFinal() && variable.getInitializer().isPresent()) continue;
                    if (annotation.equals("AllArgsConstructor") || (variable.getInitializer().isEmpty()
                            && (field.isFinal() || annotated(field, "lombok.NonNull")))) selected.add(variable);
                }
            }
            if (owner.getConstructors().stream().anyMatch(c -> c.getParameters().size() == selected.size()
                    && java.util.stream.IntStream.range(0, selected.size()).allMatch(i -> c.getParameter(i).getType().equals(selected.get(i).getType())))) continue;
            ConstructorDeclaration constructor = owner.addConstructor(Modifier.Keyword.PUBLIC);
            constructor.setRange(owner.getRange().orElse(null));
            constructor.setData(ORIGIN, "lombok:" + annotation);
            if (force && annotation.equals("NoArgsConstructor")) for (var field : owner.getFields()) {
                if (!field.isFinal() || field.isStatic()) continue;
                for (var variable : field.getVariables()) if (variable.getInitializer().isEmpty()) {
                    String defaultValue = variable.getType().isPrimitiveType() ? variable.getType().asString().equals("boolean") ? "false" : "0" : "null";
                    var assignment = new ExpressionStmt(new AssignExpr(new FieldAccessExpr(new ThisExpr(), variable.getNameAsString()),
                            com.github.javaparser.StaticJavaParser.parseExpression(defaultValue), AssignExpr.Operator.ASSIGN));
                    variable.getRange().ifPresent(assignment::setRange); constructor.getBody().addStatement(assignment);
                }
            }
            for (var variable : selected) {
                var field = (FieldDeclaration) variable.getParentNode().orElseThrow();
                var parameter = constructor.addAndGetParameter(variable.getType().clone(), variable.getNameAsString());
                parameter.setRange(variable.getRange().orElse(null));
                field.getAnnotations().stream().filter(a -> Set.of("NonNull", "Nullable", "NotNull", "Nonnull").contains(a.getName().getIdentifier()))
                        .forEach(a -> parameter.addAnnotation(a.clone()));
                if (annotated(field, "lombok.NonNull")) constructor.getBody().addStatement(
                        "java.util.Objects.requireNonNull(" + variable.getNameAsString() + ");");
                var assignment = new ExpressionStmt(new AssignExpr(new FieldAccessExpr(new ThisExpr(), variable.getNameAsString()),
                        new NameExpr(variable.getNameAsString()), AssignExpr.Operator.ASSIGN));
                variable.getRange().ifPresent(assignment::setRange);
                constructor.getBody().addStatement(assignment);
            }
        }
        if (owner.getConstructors().isEmpty() && !unsupportedGenerator) {
            var constructor = owner.addConstructor();
            constructor.setRange(owner.getRange().orElse(null));
            constructor.setData(ORIGIN, "implicit");
        }
        List<Statement> instance = new ArrayList<>(), statics = new ArrayList<>();
        for (var member : owner.getMembers()) {
            if (member instanceof FieldDeclaration field) for (var variable : field.getVariables()) {
                variable.getInitializer().ifPresent(value -> {
                    Expression target = field.isStatic() ? new NameExpr(variable.getNameAsString())
                            : new FieldAccessExpr(new ThisExpr(), variable.getNameAsString());
                    var statement = new ExpressionStmt(new AssignExpr(target, qualify(value.clone(), owner, field.isStatic()), AssignExpr.Operator.ASSIGN));
                    variable.getRange().ifPresent(statement::setRange);
                    (field.isStatic() ? statics : instance).add(statement);
                });
            }
            if (member instanceof InitializerDeclaration initializer)
                (initializer.isStatic() ? statics : instance).add(qualify(initializer.getBody().clone(), owner, initializer.isStatic()));
        }
        for (var constructor : owner.getConstructors()) {
            var statements = constructor.getBody().getStatements();
            if (!statements.isEmpty() && statements.get(0) instanceof ExplicitConstructorInvocationStmt invocation && invocation.isThis()) continue;
            int offset = !statements.isEmpty() && statements.get(0).isExplicitConstructorInvocationStmt() ? 1 : 0;
            for (Statement statement : instance) statements.add(offset++, statement.clone());
        }
        if (!statics.isEmpty()) {
            String name = "$nullguard$clinit";
            while (!owner.getMethodsByName(name).isEmpty()) name += "$";
            var method = owner.addMethod(name, Modifier.Keyword.STATIC);
            method.setType("void").setBody(new BlockStmt(new NodeList<>(statics)));
            method.setRange(owner.getRange().orElse(null));
            method.setData(ORIGIN, "static-initializer");
        }
        owner.setData(LIMITS, List.copyOf(limits));
    }

    /** Initializers have class scope, not constructor-parameter scope. */
    private static <T extends Node> T qualify(T node, ClassOrInterfaceDeclaration owner, boolean staticContext) {
        if (staticContext) return node; // The synthetic static method introduces no parameters.
        Set<String> fields = new HashSet<>();
        owner.getFields().stream().flatMap(f -> f.getVariables().stream()).forEach(v -> fields.add(v.getNameAsString()));
        Set<String> locals = new HashSet<>();
        node.findAll(VariableDeclarator.class).forEach(v -> locals.add(v.getNameAsString()));
        node.findAll(Parameter.class).forEach(p -> locals.add(p.getNameAsString()));
        Set<String> overlap = new HashSet<>(locals);
        overlap.retainAll(fields);
        Set<String> constructorParameters = new HashSet<>();
        owner.getConstructors().forEach(c -> c.getParameters().forEach(p -> constructorParameters.add(p.getNameAsString())));
        var parameterOverlap = new HashSet<>(locals); parameterOverlap.retainAll(constructorParameters);
        if (!overlap.isEmpty() || !parameterOverlap.isEmpty())
            owner.getData(LIMITS).add("initializer-local shadowing requires conservative lifecycle invalidation");
        for (var name : new ArrayList<>(node.findAll(NameExpr.class))) {
            if (!fields.contains(name.getNameAsString()) || locals.contains(name.getNameAsString())) continue;
            Expression scope = staticContext ? new NameExpr(owner.getNameAsString()) : new ThisExpr();
            var access = new FieldAccessExpr(scope, name.getNameAsString()); name.getRange().ifPresent(access::setRange);
            if (name == node) return (T) access;
            name.replace(access);
        }
        return node;
    }

    static boolean annotated(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node, String qualified) {
        return node.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals(qualified)
                || (!a.getNameAsString().contains(".") && qualified.endsWith("." + a.getNameAsString()) && a.findCompilationUnit().stream().flatMap(c -> c.getImports().stream())
                    .anyMatch(i -> !i.isStatic() && (i.isAsterisk() ? (i.getNameAsString() + "." + a.getNameAsString()).equals(qualified)
                            : i.getNameAsString().equals(qualified)))));
    }
}
