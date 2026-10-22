package com.nullguard.core.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SymbolResolutionOverflowTest {
    @Test
    void overflowKeepsSourceCallUnresolvedAndContinuesWithHealthyCall() {
        var unit = StaticJavaParser.parse("class Sample { void run() { broken(); System.nanoTime(); } }");
        unit.setData(Node.SYMBOL_RESOLVER_KEY, new JavaSymbolSolver(new ReflectionTypeSolver()) {
            @Override public <T> T resolveDeclaration(Node node, Class<T> type) {
                if (node instanceof MethodCallExpr call && call.getNameAsString().equals("broken"))
                    throw new StackOverflowError("recursive type resolution");
                return super.resolveDeclaration(node, type);
            }
        });
        var sites = new JavaParserAstParser().resolveCallSites(unit.findFirst(MethodDeclaration.class).orElseThrow());
        assertEquals(2, sites.size());
        assertEquals("broken()", sites.get(0).getExpression());
        assertEquals(1, sites.get(0).getLineNumber());
        assertTrue(sites.get(0).getResolvedTarget().isEmpty());
        assertTrue(sites.get(0).getResolutionFailure().contains("StackOverflowError"));
        assertEquals("java.lang.System", sites.get(1).getResolvedTarget().orElseThrow().declaringType());
    }

    @Test
    void unrelatedVmErrorsStillPropagate() {
        var unit = StaticJavaParser.parse("class Sample { void run() { broken(); } }");
        unit.setData(Node.SYMBOL_RESOLVER_KEY, new JavaSymbolSolver(new ReflectionTypeSolver()) {
            @Override public <T> T resolveDeclaration(Node node, Class<T> type) {
                throw new OutOfMemoryError("test fault");
            }
        });
        assertThrows(OutOfMemoryError.class, () -> new JavaParserAstParser()
                .resolveCallSites(unit.findFirst(MethodDeclaration.class).orElseThrow()));
    }
}
