package com.nullguard.core.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;

/** Reparse CFG snippets with the same Java syntax level, without changing global parser state. */
public final class SourceExpressions {
    private SourceExpressions() {}
    static final ParserConfiguration.LanguageLevel LANGUAGE_LEVEL = ParserConfiguration.LanguageLevel.JAVA_21;

    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() ->
            new JavaParser(new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL)));
    public static Expression parseExpression(String source) { return require(PARSER.get().parseExpression(source)); }
    public static Statement parseStatement(String source) { return require(PARSER.get().parseStatement(source)); }
    private static <T> T require(ParseResult<T> result) {
        if (!result.isSuccessful()) throw new ParseProblemException(result.getProblems());
        return result.getResult().orElseThrow(() -> new IllegalArgumentException("Empty parse result"));
    }
}
