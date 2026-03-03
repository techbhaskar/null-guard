package com.nullguard.core.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.nullguard.core.exception.CoreAnalysisException;
import com.nullguard.core.model.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JavaParserAstParser implements AstParser {

    private final JavaParser javaParser;

    public JavaParserAstParser() {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
        ParserConfiguration config = new ParserConfiguration().setSymbolResolver(symbolSolver);
        this.javaParser = new JavaParser(config);
    }

    @Override
    public ProjectModel parse(Path projectRoot) {
        ProjectModel.Builder projectBuilder = ProjectModel.builder().projectName(projectRoot.getFileName().toString());
        ModuleModel.Builder moduleBuilder = ModuleModel.builder().moduleName("root");
        
        LinkedHashMap<String, PackageModel.Builder> packageMap = new LinkedHashMap<>();

        try (Stream<Path> paths = Files.walk(projectRoot)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());

            for (Path path : javaFiles) {
                javaParser.parse(path).getResult().ifPresent(cu -> {
                    String pkgName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("default");
                    PackageModel.Builder packageBuilder = packageMap.computeIfAbsent(pkgName, k -> PackageModel.builder().packageName(k));

                    cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                      .sorted(Comparator.comparing(ClassOrInterfaceDeclaration::getNameAsString))
                      .forEach(cid -> {
                        ClassModel.Builder classBuilder = ClassModel.builder().className(cid.getNameAsString());
                        cid.findAll(MethodDeclaration.class).stream()
                           .sorted(Comparator.comparing(m -> m.getSignature().asString()))
                           .forEach(md -> {
                            MethodModel.Builder methodBuilder = MethodModel.builder()
                                    .methodName(md.getNameAsString())
                                    .signature(md.getSignature().asString());
                            classBuilder.addMethod(methodBuilder.build());
                        });
                        packageBuilder.addClass(classBuilder.build());
                    });
                });
            }
        } catch (IOException e) {
            throw new CoreAnalysisException("Failed to read project: " + projectRoot, e);
        }

        packageMap.values().forEach(pb -> moduleBuilder.addPackage(pb.build()));
        projectBuilder.addModule(moduleBuilder.build());
        return projectBuilder.build();
    }
}
