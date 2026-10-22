package com.nullguard.core.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.nullguard.core.builder.BasicControlFlowBuilder;
import com.nullguard.core.cfg.ControlFlowModel;
import com.nullguard.core.exception.CoreAnalysisException;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.ResolvedCallTarget;
import com.nullguard.core.model.SemanticCallSite;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Java source parser with project-, dependency-, and JDK-aware symbol resolution. */
public final class JavaParserAstParser implements AstParser {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(JavaParserAstParser.class);

    private final List<Path> configuredSourceRoots;
    private final List<Path> classpathEntries;

    public JavaParserAstParser() {
        this(List.of(), List.of());
    }

    public JavaParserAstParser(Collection<Path> sourceRoots, Collection<Path> classpathEntries) {
        this.configuredSourceRoots = normalizedPaths(sourceRoots);
        this.classpathEntries = normalizedPaths(classpathEntries);
    }

    @Override
    public ProjectModel parse(Path projectRoot) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        ProjectModel.Builder projectBuilder = ProjectModel.builder()
                .projectName(normalizedRoot.getFileName().toString());
        ModuleModel.Builder moduleBuilder = ModuleModel.builder().moduleName("root");
        BasicControlFlowBuilder cfgBuilder = new BasicControlFlowBuilder();
        LinkedHashMap<String, PackageModel.Builder> packageMap = new LinkedHashMap<>();
        int[] counts = new int[4];
        List<String> problemPaths = new ArrayList<>();

        try (ParserEnvironment environment = createParser(normalizedRoot);
             Stream<Path> paths = Files.walk(normalizedRoot)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());

            java.util.Map<String, Boolean> packageDefaults = new java.util.HashMap<>();
            java.util.Map<Path, com.github.javaparser.ast.CompilationUnit> units = new LinkedHashMap<>();
            counts[0] = javaFiles.size();
            for (Path info : javaFiles) {
                if (!info.getFileName().toString().equals("package-info.java")) continue;
                environment.parser().parse(info).getResult().ifPresent(cu -> cu.getPackageDeclaration().ifPresent(pkg -> {
                    boolean marked = qualifiedAnnotation(pkg, "org.jspecify.annotations.NullMarked")
                            || qualifiedAnnotation(pkg, "org.springframework.lang.NonNullApi");
                    if (qualifiedAnnotation(pkg, "org.jspecify.annotations.NullUnmarked")) marked = false;
                    packageDefaults.put(pkg.getNameAsString(), marked);
                }));
            }
            for (Path path : javaFiles) {
                var result = environment.parser().parse(path);
                if (!result.isSuccessful()) {
                    counts[2]++;
                    problemPaths.add(normalizedRoot.relativize(path).toString().replace('\\', '/'));
                }
                if (result.getResult().isEmpty()) {
                    LOG.warn("Could not parse {} - skipping. {} problem(s): {}",
                            path, result.getProblems().size(), result.getProblems());
                    continue;
                }

                result.getResult().ifPresent(compilationUnit -> {
                    counts[1]++;
                    counts[3] += compilationUnit.findAll(MethodDeclaration.class).size()
                            + compilationUnit.findAll(ConstructorDeclaration.class).size();
                    units.put(path, compilationUnit);
                });
            }
            units.values().forEach(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).forEach(ConstructorExpansion::expand));
            units.values().forEach(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).forEach(type -> {
                type.getFullyQualifiedName().ifPresent(name -> environment.memory().addDeclaration(name, type.isInterface()
                        ? new com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration(type, environment.types())
                        : new com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration(type, environment.types())));
            }));
            units.forEach((path, compilationUnit) -> {
                    String packageName = compilationUnit.getPackageDeclaration()
                            .map(declaration -> declaration.getNameAsString())
                            .orElse("default");
                    PackageModel.Builder packageBuilder = packageMap.computeIfAbsent(
                            packageName,
                            ignored -> PackageModel.builder().packageName(packageName));

                    compilationUnit.findAll(ClassOrInterfaceDeclaration.class).stream()
                            .sorted(Comparator.comparing(
                                            (ClassOrInterfaceDeclaration type) -> sourcePosition(type))
                                    .thenComparing(ClassOrInterfaceDeclaration::getNameAsString))
                            .forEach(type -> packageBuilder.addClass(
                                    buildClass(type, packageName, cfgBuilder, normalizedRoot.relativize(path), packageDefaults.getOrDefault(packageName, false))));
            });
        } catch (IOException e) {
            throw new CoreAnalysisException("Failed to read project: " + normalizedRoot, e);
        }

        packageMap.values().forEach(builder -> moduleBuilder.addPackage(builder.build()));
        projectBuilder.addModule(moduleBuilder.build());
        projectBuilder.parseDiagnostics(new com.nullguard.core.model.ParseDiagnostics(
                counts[0], counts[1], counts[2], counts[3], problemPaths));
        return projectBuilder.build();
    }

    private ClassModel buildClass(ClassOrInterfaceDeclaration declaration,
                                  String packageName,
                                  BasicControlFlowBuilder cfgBuilder, Path sourcePath, boolean packageDefault) {
        String fallbackQualifiedName = declaration.findCompilationUnit().flatMap(c -> c.getPackageDeclaration()).isEmpty()
                ? declaration.getNameAsString() : packageName + "." + declaration.getNameAsString();
        ClassModel.Builder classBuilder = ClassModel.builder()
                .constructionLimitations(declaration.containsData(ConstructorExpansion.LIMITS) ? declaration.getData(ConstructorExpansion.LIMITS) : List.of())
                .className(declaration.getNameAsString())
                .qualifiedName(fallbackQualifiedName)
                .interfaceType(declaration.isInterface())
                .basePath(AstMetadataExtractor.extractBasePath(declaration))
                .basePaths(AstMetadataExtractor.extractPaths(declaration))
                .annotations(AstMetadataExtractor.extractAnnotations(declaration))
                .fields(AstMetadataExtractor.extractFields(declaration))
                .sourceLocation(declaration.getRange().map(r -> new com.nullguard.core.model.SourceLocation(
                        sourcePath.toString().replace('\\', '/'), r.begin.line, r.begin.column, r.end.line, r.end.column)).orElse(null))
                .addAssignableType(fallbackQualifiedName);

        try {
            ResolvedReferenceTypeDeclaration resolvedType = declaration.resolve();
            classBuilder.qualifiedName(resolvedType.getQualifiedName())
                    .interfaceType(resolvedType.isInterface())
                    .addAssignableType(resolvedType.getQualifiedName());
            resolvedType.getAllAncestors().forEach(ancestor ->
                    classBuilder.addAssignableType(ancestor.getQualifiedName()));
        } catch (RuntimeException | LinkageError resolutionFailure) {
            LOG.debug("Could not resolve type hierarchy for {}: {}",
                    fallbackQualifiedName, resolutionFailure.getMessage());
            declaration.getExtendedTypes().forEach(type ->
                    classBuilder.addAssignableType(type.getNameWithScope()));
            declaration.getImplementedTypes().forEach(type ->
                    classBuilder.addAssignableType(type.getNameWithScope()));
        }

        // getMethods() returns only methods declared by this type. findAll(MethodDeclaration)
        // also includes methods of nested classes and duplicated them in their enclosing class.
        declaration.getMethods().stream()
                .sorted(Comparator.comparing(method -> method.getSignature().asString()))
                .forEach(method -> classBuilder.addMethod(buildMethod(method, cfgBuilder, sourcePath, nonNullFields(declaration), packageDefault)));
        declaration.getConstructors().stream().sorted(Comparator.comparing(c -> c.getSignature().asString()))
                .forEach(constructor -> {
                    MethodDeclaration synthetic = new MethodDeclaration().setName(constructor.getNameAsString())
                            .setType("void").setParameters(new com.github.javaparser.ast.NodeList<>(constructor.getParameters().stream().map(p -> p.clone()).toList()))
                            .setBody(constructor.getBody().clone());
                    constructor.getRange().ifPresent(synthetic::setRange);
                    synthetic.setParentNode(declaration);
                    classBuilder.addMethod(buildMethod(synthetic, cfgBuilder, sourcePath, Set.of(), packageDefault, constructor));
                });
        return classBuilder.build();
    }

    private MethodModel buildMethod(MethodDeclaration method, BasicControlFlowBuilder cfgBuilder, Path sourcePath, Set<String> fields, boolean packageDefault) {
        return buildMethod(method, cfgBuilder, sourcePath, fields, packageDefault, null);
    }

    private MethodModel buildMethod(MethodDeclaration method, BasicControlFlowBuilder cfgBuilder, Path sourcePath, Set<String> fields, boolean packageDefault, ConstructorDeclaration constructor) {
        boolean defaultNonNull = defaultNonNull(method, packageDefault);
        String signature = constructor == null ? method.getSignature().asString() : constructorSignature(constructor.getSignature().asString());
        String canonical = signature;
        try { canonical = constructor == null ? method.resolve().getSignature() : constructorSignature(constructor.resolve().getSignature()); }
        catch (RuntimeException | LinkageError unavailable) { /* Exact textual signature only; never arity guessing. */ }
        List<String> assumptions = new ArrayList<>();
        if (constructor == null) {
            var owner = method.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
            if (owner != null) {
                Set<String> stable = stableNonNullFields(owner);
                fields.stream().filter(f -> !stable.contains(f)).sorted().forEach(f -> assumptions.add("framework field: " + f));
            }
            method.getParameters().stream().filter(p -> frameworkParameter(method, p) && !hasAnnotation(p, "Nullable", "CheckForNull"))
                    .forEach(p -> assumptions.add("framework parameter: " + p.getNameAsString()));
        } else {
            method.getParameters().stream().filter(p -> constructorRequires(constructor, p.getNameAsString()))
                    .forEach(p -> assumptions.add("constructor parameter required by framework field assumption: " + p.getNameAsString()));
        }
        ControlFlowModel cfg = null;
        try {
            if (method.getBody().isPresent()) cfg = cfgBuilder.build(method);
        } catch (Exception cfgFailure) {
            if (method.getBody().isPresent()) {
                LOG.warn("CFG construction failed for {}; this method will contribute no risk.",
                        method.getSignature().asString(), cfgFailure);
            } else {
                LOG.debug("No body for {} (abstract or native); no CFG built.",
                        method.getSignature().asString());
            }
        }

        String httpMethod = AstMetadataExtractor.extractHttpMethod(method);
        String httpPath = AstMetadataExtractor.extractHttpPath(method);

        return MethodModel.builder()
                .bodySource(method.getBody().map(Object::toString).orElse(""))
                .origin(constructor != null && constructor.containsData(ConstructorExpansion.ORIGIN) ? constructor.getData(ConstructorExpansion.ORIGIN)
                        : method.containsData(ConstructorExpansion.ORIGIN) ? method.getData(ConstructorExpansion.ORIGIN) : "source")
                .sourceLocation(method.getRange().map(r -> new com.nullguard.core.model.SourceLocation(
                        sourcePath.toString().replace('\\', '/'), r.begin.line, r.begin.column, r.end.line, r.end.column)).orElse(null))
                .nonNullFields(fields)
                .parameters(method.getParameters().stream().map(p -> AstMetadataExtractor.buildParameterModel(
                        method,
                        p,
                        !hasAnnotation(p, "Nullable", "CheckForNull") && (hasAnnotation(p, "NonNull", "NotNull", "Nonnull") || frameworkParameter(method, p) || constructorRequires(constructor, p.getNameAsString()) || (defaultNonNull && defaultApplies(p.getType(), method))),
                        hasAnnotation(p, "Nullable", "CheckForNull"))).toList())
                .nonNullReturn(!hasAnnotation(method, "Nullable", "CheckForNull") && (hasAnnotation(method, "NonNull", "NotNull", "Nonnull") || (defaultNonNull && defaultApplies(method.getType(), method))))
                .primitiveReturn(constructor != null || method.getType().isPrimitiveType())
                .nullableReturn(hasAnnotation(method, "Nullable", "CheckForNull"))
                .returnType(method.getType().asString())
                .httpMethod(httpMethod)
                .httpPath(httpPath)
                .httpMethods(AstMetadataExtractor.extractHttpMethods(method))
                .httpPaths(AstMetadataExtractor.extractPaths(method))
                .responseStatus(AstMetadataExtractor.extractResponseStatus(method, httpMethod))
                .methodFindings(AstMetadataExtractor.extractMethodFindings(method))
                .methodName(constructor == null ? method.getNameAsString() : "<init>")
                .signature(signature)
                .canonicalSignature(canonical)
                .virtualMethod(constructor == null && !method.isPrivate() && !method.isStatic())
                .semanticDeclaration(true)
                .overriddenTargets(constructor == null ? overriddenTargets(method) : Set.of())
                .declaredBody(method.getBody().isPresent())
                .frameworkAssumptions(assumptions)
                .controlFlowModel(cfg)
                .semanticCallSites(resolveCallSites(constructor == null ? method : constructor))
                .build();
    }

    private List<SemanticCallSite> resolveCallSites(com.github.javaparser.ast.Node method) {
        List<MethodCallExpr> calls = new ArrayList<>(method.findAll(MethodCallExpr.class));
        calls.sort(Comparator.comparing((MethodCallExpr call) -> sourcePosition(call))
                .thenComparing(call -> call.toString()));

        List<SemanticCallSite> semanticCalls = new ArrayList<>();
        for (MethodCallExpr call : calls) {
            ResolvedCallTarget target = null;
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                String signature = resolved.toAst(MethodDeclaration.class)
                        .map(source -> source.getSignature().asString())
                        .orElseGet(resolved::getSignature);
                target = new ResolvedCallTarget(
                        resolved.declaringType().getQualifiedName(),
                        signature,
                        resolved.getName(),
                        resolved.getNumberOfParams(), resolved.getSignature(), !resolved.isStatic()
                                && resolved.accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PRIVATE
                                && !call.getScope().map(s -> s.isSuperExpr()).orElse(false));
            } catch (RuntimeException | LinkageError resolutionFailure) {
                LOG.debug("Could not resolve call {} in {}: {}",
                        call, sourcePosition(method), resolutionFailure.getMessage());
            }
            semanticCalls.add(new SemanticCallSite(
                    writtenCallName(call), call.getArguments().size(), target, call.toString(), call.getBegin().map(p -> p.line).orElse(-1)));
        }
        for (ObjectCreationExpr call : method.findAll(ObjectCreationExpr.class)) {
            ResolvedCallTarget target = null;
            try {
                var resolved = call.resolve();
                String signature = resolved.toAst(ConstructorDeclaration.class)
                        .map(c -> constructorSignature(c.getSignature().asString()))
                        .orElseGet(() -> constructorSignature(resolved.getSignature()));
                target = new ResolvedCallTarget(resolved.declaringType().getQualifiedName(), signature,
                        "<init>", resolved.getNumberOfParams(), constructorSignature(resolved.getSignature()));
            } catch (RuntimeException | LinkageError unavailable) { /* Reported as unresolved. */ }
            semanticCalls.add(new SemanticCallSite("new:" + call.getType().asString(), call.getArguments().size(), target, call.toString(), call.getBegin().map(p -> p.line).orElse(-1)));
        }
        for (var call : method.findAll(com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt.class)) {
            ResolvedCallTarget target = null;
            try {
                var resolved = call.resolve();
                String signature = resolved.toAst(ConstructorDeclaration.class)
                        .map(c -> constructorSignature(c.getSignature().asString()))
                        .orElseGet(() -> constructorSignature(resolved.getSignature()));
                target = new ResolvedCallTarget(resolved.declaringType().getQualifiedName(), signature,
                        "<init>", resolved.getNumberOfParams(), constructorSignature(resolved.getSignature()));
            } catch (RuntimeException | LinkageError unavailable) { /* Reported as unresolved. */ }
            semanticCalls.add(new SemanticCallSite(call.isThis() ? "this:<init>" : "super:<init>", call.getArguments().size(), target, call.toString(), call.getBegin().map(p -> p.line).orElse(-1)));
        }
        return List.copyOf(semanticCalls);
    }

    private static String constructorSignature(String signature) {
        return "<init>" + signature.substring(signature.indexOf('('));
    }

    /** Substitute ancestor type arguments before comparing an actual override. */
    private static Set<String> overriddenTargets(MethodDeclaration method) {
        Set<String> targets = new LinkedHashSet<>();
        if (method.isStatic() || method.isPrivate()) return targets;
        try {
            var resolved = method.resolve();
            String own = resolved.getSignature().replaceAll("\\s+", "");
            for (var ancestor : resolved.declaringType().getAllAncestors()) {
                for (var parent : ancestor.getTypeDeclaration().orElseThrow().getDeclaredMethods()) {
                    if (parent.isStatic() || parent.accessSpecifier() == com.github.javaparser.ast.AccessSpecifier.PRIVATE) continue;
                    if (parent.accessSpecifier() == com.github.javaparser.ast.AccessSpecifier.NONE
                            && !parent.declaringType().getPackageName().equals(resolved.declaringType().getPackageName())) continue;
                    List<String> parameters = new ArrayList<>();
                    for (int i = 0; i < parent.getNumberOfParams(); i++)
                        parameters.add(ancestor.typeParametersMap().replaceAll(parent.getParam(i).getType()).describe());
                    String substituted = parent.getName() + "(" + String.join(",", parameters) + ")";
                    if (own.equals(substituted.replaceAll("\\s+", "")))
                        targets.add(parent.declaringType().getQualifiedName() + "#" + parent.getSignature().replaceAll("\\s+", ""));
                }
            }
        } catch (RuntimeException | LinkageError unavailable) { /* Never guess missing hierarchy. */ }
        return targets;
    }

    private static boolean constructorRequires(ConstructorDeclaration constructor, String parameter) {
        if (constructor == null) return false;
        var owner = constructor.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (owner == null) return false;
        Set<String> fields = nonNullFields(owner);
        return constructor.getBody().getStatements().stream().map(s -> s.toString().replaceAll("\\s+", ""))
                .anyMatch(s -> fields.stream().anyMatch(f -> s.equals("this." + f + "=" + parameter + ";")));
    }

    /** Only stable fields with syntactically definite initializers are seeded. */
    private static Set<String> stableNonNullFields(ClassOrInterfaceDeclaration owner) {
        Set<String> names = new LinkedHashSet<>();
        owner.getFields().stream().filter(f -> f.isFinal()).forEach(f -> f.getVariables().forEach(v ->
                v.getInitializer().filter(e -> e.isObjectCreationExpr() || e.isArrayCreationExpr()
                        || (e.isLiteralExpr() && !e.isNullLiteralExpr()) || nonNullLoggerFactory(e))
                        .ifPresent(e -> names.add(v.getNameAsString()))));
        return names;
    }

    private static Set<String> nonNullFields(ClassOrInterfaceDeclaration owner) {
        Set<String> names = stableNonNullFields(owner);
        boolean springBean = java.util.stream.Stream.of("org.springframework.stereotype.Component", "org.springframework.stereotype.Service",
                "org.springframework.stereotype.Repository", "org.springframework.stereotype.Controller", "org.springframework.web.bind.annotation.RestController",
                "org.springframework.context.annotation.Configuration").anyMatch(a -> qualifiedAnnotation(owner, a));
        boolean lombokConstructor = qualifiedAnnotation(owner, "lombok.RequiredArgsConstructor") || qualifiedAnnotation(owner, "lombok.AllArgsConstructor");
        if (qualifiedAnnotation(owner, "lombok.extern.slf4j.Slf4j")) names.add("log");
        if (springBean) owner.getFields().forEach(f -> f.getVariables().forEach(v -> {
            if (hasAnnotation(f, "Nullable", "CheckForNull") || v.getType().getAnnotations().stream().anyMatch(a -> java.util.Set.of("Nullable", "CheckForNull").contains(a.getName().getIdentifier()))) return;
            boolean writable = hasAnnotation(f, "Setter") || hasAnnotation(owner, "Setter", "Data") || owner.getMethods().stream()
                    .flatMap(m -> m.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream())
                    .anyMatch(a -> a.getTarget().toString().equals(v.getNameAsString()) || a.getTarget().toString().equals("this." + v.getNameAsString()));
            boolean requiredAutowired = !writable && qualifiedAnnotation(f, "org.springframework.beans.factory.annotation.Autowired")
                    && f.getAnnotations().stream().filter(a -> a.getNameAsString().endsWith("Autowired"))
                        .noneMatch(a -> a.toString().contains("required = false") || a.toString().contains("required=false"));
            boolean constructorField = f.isFinal() && v.getInitializer().isEmpty() && (lombokConstructor ||
                    (owner.getConstructors().size() == 1 && owner.getConstructors().get(0).getParameters().stream()
                        .anyMatch(parameter -> parameter.getNameAsString().equals(v.getNameAsString()) && !hasAnnotation(parameter, "Nullable", "CheckForNull"))
                    && owner.getConstructors().get(0).getBody().getStatements().stream().anyMatch(statement ->
                        statement.toString().replaceAll("\\s+", "").equals("this." + v.getNameAsString() + "=" + v.getNameAsString() + ";"))));
            if (requiredAutowired || constructorField) names.add(v.getNameAsString());
        }));
        return names;
    }

    private static boolean nonNullLoggerFactory(com.github.javaparser.ast.expr.Expression expression) {
        if (!expression.isMethodCallExpr()) return false;
        var call = expression.asMethodCallExpr();
        if (!call.getNameAsString().equals("getLogger")) return false;
        try {
            return java.util.Set.of("org.slf4j.LoggerFactory", "java.util.logging.Logger", "org.apache.logging.log4j.LogManager")
                    .contains(call.resolve().declaringType().getQualifiedName());
        } catch (RuntimeException | LinkageError unresolved) { return false; }
    }

    private static boolean frameworkParameter(MethodDeclaration method, com.github.javaparser.ast.body.Parameter p) {
        boolean mapped = java.util.stream.Stream.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")
                .anyMatch(a -> qualifiedAnnotation(method, "org.springframework.web.bind.annotation." + a));
        if (mapped && qualifiedAnnotation(p, "org.springframework.web.bind.annotation.RequestBody"))
            return p.getAnnotations().stream().filter(a -> a.getName().getIdentifier().equals("RequestBody"))
                    .noneMatch(a -> a.toString().replaceAll("\\s+", "").contains("required=false"));
        if (mapped && p.getType().isClassOrInterfaceType()) {
            String type = p.getType().asString();
            for (String servlet : java.util.List.of("jakarta.servlet.http.HttpServletRequest", "jakarta.servlet.http.HttpServletResponse", "javax.servlet.http.HttpServletRequest", "javax.servlet.http.HttpServletResponse")) {
                if (type.equals(servlet) || (servlet.endsWith("." + type) && p.findCompilationUnit().stream().flatMap(c -> c.getImports().stream()).anyMatch(i -> !i.isStatic() && i.getNameAsString().equals(servlet)))) return true;
            }
        }
        if (qualifiedAnnotation(method, "org.aspectj.lang.annotation.Around")) {
            String type = p.getType().asString();
            if (type.equals("org.aspectj.lang.ProceedingJoinPoint") || (type.equals("ProceedingJoinPoint") && p.findCompilationUnit().stream().flatMap(c -> c.getImports().stream()).anyMatch(i -> i.getNameAsString().equals("org.aspectj.lang.ProceedingJoinPoint")))) return true;
            return method.getAnnotations().stream().filter(a -> a.getName().getIdentifier().equals("Around"))
                    .anyMatch(a -> a.toString().contains("@annotation(" + p.getNameAsString() + ")"));
        }
        return false;
    }

    private static boolean defaultNonNull(MethodDeclaration method, boolean packageDefault) {
        com.github.javaparser.ast.Node scope = method;
        while (scope != null) {
            if (scope instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> annotated) {
                if (qualifiedAnnotation(annotated, "org.jspecify.annotations.NullUnmarked")) return false;
                if (qualifiedAnnotation(annotated, "org.jspecify.annotations.NullMarked")) return true;
            }
            scope = scope.getParentNode().orElse(null);
        }
        return packageDefault;
    }

    private static boolean qualifiedAnnotation(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node, String qualified) {
        return node.getAnnotations().stream().anyMatch(a -> {
            String written = a.getNameAsString();
            if (written.equals(qualified)) return true;
            if (written.contains(".")) return false;
            if (!qualified.endsWith("." + written)) return false;
            return a.findCompilationUnit().map(cu -> cu.getImports().stream().anyMatch(i -> !i.isStatic()
                    && ((!i.isAsterisk() && i.getNameAsString().equals(qualified))
                    || (i.isAsterisk() && (i.getNameAsString() + "." + written).equals(qualified))))) .orElse(false);
        });
    }

    private static boolean defaultApplies(com.github.javaparser.ast.type.Type type, MethodDeclaration method) {
        if (!type.isClassOrInterfaceType()) return true;
        String name = type.asClassOrInterfaceType().getNameAsString();
        com.github.javaparser.ast.Node scope = method;
        while (scope != null) {
            if (scope instanceof com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters<?> generic
                    && generic.getTypeParameters().stream().anyMatch(p -> p.getNameAsString().equals(name))) return false;
            scope = scope.getParentNode().orElse(null);
        }
        return true;
    }

    private static boolean hasAnnotation(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node, String... names) {
        var annotations = new java.util.ArrayList<>(node.getAnnotations());
        // Read only the root type's annotations, not annotations on generic elements.
        if (node instanceof MethodDeclaration method) annotations.addAll(method.getType().getAnnotations());
        if (node instanceof com.github.javaparser.ast.body.Parameter parameter) annotations.addAll(parameter.getType().getAnnotations());
        return annotations.stream().anyMatch(a -> java.util.Arrays.asList(names).contains(a.getName().getIdentifier()));
    }

    private ParserEnvironment createParser(Path projectRoot) throws IOException {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        var memory = new com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver();
        typeSolver.add(memory);

        for (Path sourceRoot : discoverSourceRoots(projectRoot)) {
            try {
                typeSolver.add(new JavaParserTypeSolver(sourceRoot, new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)));
            } catch (RuntimeException invalidSourceRoot) {
                LOG.warn("Ignoring invalid source root {}: {}",
                        sourceRoot, invalidSourceRoot.getMessage());
            }
        }

        List<URL> classDirectories = new ArrayList<>();
        for (Path entry : classpathEntries) {
            if (!Files.exists(entry)) continue;
            if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try {
                    typeSolver.add(new JarTypeSolver(entry));
                } catch (IOException invalidJar) {
                    LOG.warn("Ignoring unreadable classpath JAR {}: {}",
                            entry, invalidJar.getMessage());
                }
            } else if (Files.isDirectory(entry)) {
                classDirectories.add(entry.toUri().toURL());
            }
        }

        URLClassLoader dependencyLoader = classDirectories.isEmpty()
                ? null
                : new URLClassLoader(classDirectories.toArray(URL[]::new), getClass().getClassLoader());
        // The running CLI/plugin already carries NullGuard and some dependency bytecode. Maven
        // classpath directories are layered on top when supplied; otherwise use the application
        // loader directly so CLI analysis is not limited to JDK types.
        typeSolver.add(new ClassLoaderTypeSolver(
                dependencyLoader == null ? getClass().getClassLoader() : dependencyLoader));
        typeSolver.add(new ReflectionTypeSolver());

        ParserConfiguration configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new ParserEnvironment(new JavaParser(configuration), dependencyLoader, typeSolver, memory);
    }

    private Set<Path> discoverSourceRoots(Path projectRoot) throws IOException {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        roots.add(projectRoot);
        roots.addAll(configuredSourceRoots);

        // When callers pass a repository root, include conventional roots for every module.
        try (Stream<Path> paths = Files.find(projectRoot, 6,
                (path, attributes) -> attributes.isDirectory()
                        && path.endsWith(Path.of("src", "main", "java")))) {
            paths.map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(roots::add);
        }
        roots.removeIf(path -> !Files.isDirectory(path));
        return roots;
    }

    private static String writtenCallName(MethodCallExpr call) {
        return call.getScope()
                .filter(NameExpr.class::isInstance)
                .map(NameExpr.class::cast)
                .map(scope -> scope.getNameAsString() + "." + call.getNameAsString())
                .orElseGet(call::getNameAsString);
    }

    private static String sourcePosition(com.github.javaparser.ast.Node node) {
        return node.getRange()
                .map(range -> String.format("%08d:%08d", range.begin.line, range.begin.column))
                .orElse("99999999:99999999");
    }

    private static List<Path> normalizedPaths(Collection<Path> paths) {
        if (paths == null) return List.of();
        return paths.stream()
                .filter(java.util.Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .distinct()
                .toList();
    }

    private record ParserEnvironment(JavaParser parser, URLClassLoader dependencyLoader, CombinedTypeSolver types,
                                     com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver memory)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (dependencyLoader != null) dependencyLoader.close();
        }
    }
}
