package com.nullguard.core.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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

        try (ParserEnvironment environment = createParser(normalizedRoot);
             Stream<Path> paths = Files.walk(normalizedRoot)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());

            java.util.Map<String, Boolean> packageDefaults = new java.util.HashMap<>();
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
                if (result.getResult().isEmpty()) {
                    LOG.warn("Could not parse {} - skipping. {} problem(s): {}",
                            path, result.getProblems().size(), result.getProblems());
                    continue;
                }

                result.getResult().ifPresent(compilationUnit -> {
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
            }
        } catch (IOException e) {
            throw new CoreAnalysisException("Failed to read project: " + normalizedRoot, e);
        }

        packageMap.values().forEach(builder -> moduleBuilder.addPackage(builder.build()));
        projectBuilder.addModule(moduleBuilder.build());
        return projectBuilder.build();
    }

    private ClassModel buildClass(ClassOrInterfaceDeclaration declaration,
                                  String packageName,
                                  BasicControlFlowBuilder cfgBuilder, Path sourcePath, boolean packageDefault) {
        String fallbackQualifiedName = packageName + "." + declaration.getNameAsString();
        ClassModel.Builder classBuilder = ClassModel.builder()
                .className(declaration.getNameAsString())
                .qualifiedName(fallbackQualifiedName)
                .interfaceType(declaration.isInterface())
                .addAssignableType(fallbackQualifiedName);

        try {
            ResolvedReferenceTypeDeclaration resolvedType = declaration.resolve();
            classBuilder.qualifiedName(resolvedType.getQualifiedName())
                    .interfaceType(resolvedType.isInterface())
                    .addAssignableType(resolvedType.getQualifiedName());
            resolvedType.getAllAncestors().forEach(ancestor ->
                    classBuilder.addAssignableType(ancestor.getQualifiedName()));
        } catch (RuntimeException resolutionFailure) {
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
        return classBuilder.build();
    }

    private MethodModel buildMethod(MethodDeclaration method, BasicControlFlowBuilder cfgBuilder, Path sourcePath, Set<String> fields, boolean packageDefault) {
        boolean defaultNonNull = defaultNonNull(method, packageDefault);
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

        return MethodModel.builder()
                .sourceLocation(method.getRange().map(r -> new com.nullguard.core.model.SourceLocation(
                        sourcePath.toString().replace('\\', '/'), r.begin.line, r.begin.column, r.end.line, r.end.column)).orElse(null))
                .nonNullFields(fields)
                .parameters(method.getParameters().stream().map(p -> new com.nullguard.core.model.ParameterModel(
                        p.getNameAsString(), p.getType().isPrimitiveType(), !hasAnnotation(p, "Nullable", "CheckForNull") && (hasAnnotation(p, "NonNull", "NotNull", "Nonnull") || (defaultNonNull && defaultApplies(p.getType(), method))),
                        hasAnnotation(p, "Nullable", "CheckForNull"))).toList())
                .nonNullReturn(!hasAnnotation(method, "Nullable", "CheckForNull") && (hasAnnotation(method, "NonNull", "NotNull", "Nonnull") || (defaultNonNull && defaultApplies(method.getType(), method))))
                .primitiveReturn(method.getType().isPrimitiveType())
                .nullableReturn(hasAnnotation(method, "Nullable", "CheckForNull"))
                .methodName(method.getNameAsString())
                .signature(method.getSignature().asString())
                .controlFlowModel(cfg)
                .semanticCallSites(resolveCallSites(method))
                .build();
    }

    private List<SemanticCallSite> resolveCallSites(MethodDeclaration method) {
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
                        resolved.getNumberOfParams());
            } catch (RuntimeException resolutionFailure) {
                LOG.debug("Could not resolve call {} in {}: {}",
                        call, method.getSignature().asString(), resolutionFailure.getMessage());
            }
            semanticCalls.add(new SemanticCallSite(
                    writtenCallName(call), call.getArguments().size(), target));
        }
        return List.copyOf(semanticCalls);
    }

    /** Only stable fields with syntactically definite initializers are seeded. */
    private static Set<String> nonNullFields(ClassOrInterfaceDeclaration owner) {
        Set<String> names = new LinkedHashSet<>();
        owner.getFields().stream().filter(f -> f.isFinal()).forEach(f -> f.getVariables().forEach(v ->
                v.getInitializer().filter(e -> e.isObjectCreationExpr() || e.isArrayCreationExpr()
                        || (e.isLiteralExpr() && !e.isNullLiteralExpr())).ifPresent(e -> names.add(v.getNameAsString()))));
        return names;
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

        for (Path sourceRoot : discoverSourceRoots(projectRoot)) {
            try {
                typeSolver.add(new JavaParserTypeSolver(sourceRoot));
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
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
        return new ParserEnvironment(new JavaParser(configuration), dependencyLoader);
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

    private record ParserEnvironment(JavaParser parser, URLClassLoader dependencyLoader)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (dependencyLoader != null) dependencyLoader.close();
        }
    }
}
