package com.nullguard.core.parser;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.nullguard.core.model.FieldModel;
import com.nullguard.core.model.MethodFinding;
import com.nullguard.core.model.ParameterModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts OpenAPI route/schema metadata and AST-level static analysis findings
 * during the single JavaParser pass.
 */
public final class AstMetadataExtractor {

    private static final Set<String> CLOSEABLE_TYPES = Set.of(
            "InputStream", "FileInputStream", "BufferedInputStream", "DataInputStream",
            "OutputStream", "FileOutputStream", "BufferedOutputStream", "DataOutputStream",
            "Reader", "FileReader", "BufferedReader", "InputStreamReader",
            "Writer", "FileWriter", "BufferedWriter", "OutputStreamWriter", "PrintWriter",
            "Scanner", "Socket", "ServerSocket", "DatagramSocket",
            "Connection", "PreparedStatement", "CallableStatement", "Statement", "ResultSet",
            "JarFile", "ZipFile", "ZipInputStream", "ZipOutputStream", "FileChannel", "RandomAccessFile"
    );

    private AstMetadataExtractor() {}

    public static String extractBasePath(ClassOrInterfaceDeclaration declaration) {
        for (AnnotationExpr ann : declaration.getAnnotations()) {
            String name = ann.getName().getIdentifier();
            if (name.equals("RequestMapping") || name.equals("Path")) {
                String path = extractAnnotationStringValue(ann, "value", "path");
                if (!path.isEmpty()) {
                    return normalizePathSegment(path);
                }
            }
        }
        return "";
    }

    public static Set<String> extractAnnotations(NodeWithAnnotations<?> node) {
        Set<String> result = new LinkedHashSet<>();
        for (AnnotationExpr ann : node.getAnnotations()) {
            result.add(ann.getName().getIdentifier());
        }
        return result;
    }

    public static List<FieldModel> extractFields(ClassOrInterfaceDeclaration declaration) {
        List<FieldModel> fields = new ArrayList<>();
        boolean classSetter = hasSimpleAnnotation(declaration, "Setter", "Data");
        Set<String> assignedFields = new LinkedHashSet<>();
        for (MethodDeclaration m : declaration.getMethods()) {
            for (AssignExpr assign : m.findAll(AssignExpr.class)) {
                String target = assign.getTarget().toString().trim();
                if (target.startsWith("this.")) {
                    assignedFields.add(target.substring(5));
                } else {
                    assignedFields.add(target);
                }
            }
        }

        for (FieldDeclaration field : declaration.getFields()) {
            List<String> annNames = field.getAnnotations().stream()
                    .map(a -> a.getName().getIdentifier())
                    .toList();
            boolean injected = hasSimpleAnnotation(field, "Autowired", "Inject", "Resource", "Value", "PersistenceContext");
            boolean nonNullAnn = hasSimpleAnnotation(field, "NonNull", "NotNull", "Nonnull", "NotBlank", "NotEmpty");
            boolean nullableAnn = hasSimpleAnnotation(field, "Nullable", "CheckForNull");
            boolean fieldSetter = hasSimpleAnnotation(field, "Setter");
            int line = field.getRange().map(r -> r.begin.line).orElse(0);

            for (VariableDeclarator var : field.getVariables()) {
                String varName = var.getNameAsString();
                boolean primitive = var.getType().isPrimitiveType();
                boolean mutated = !field.isFinal() && (classSetter || fieldSetter || assignedFields.contains(varName));
                fields.add(new FieldModel(
                        varName,
                        var.getType().asString(),
                        primitive,
                        field.isFinal(),
                        field.isStatic(),
                        primitive || nonNullAnn,
                        nullableAnn,
                        injected,
                        mutated,
                        annNames,
                        line
                ));
            }
        }
        return List.copyOf(fields);
    }

    public static String extractHttpMethod(MethodDeclaration method) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getName().getIdentifier();
            switch (name) {
                case "GetMapping", "GET" -> { return "GET"; }
                case "PostMapping", "POST" -> { return "POST"; }
                case "PutMapping", "PUT" -> { return "PUT"; }
                case "DeleteMapping", "DELETE" -> { return "DELETE"; }
                case "PatchMapping", "PATCH" -> { return "PATCH"; }
                case "RequestMapping" -> {
                    String raw = ann.toString().toUpperCase(Locale.ROOT);
                    if (raw.contains("POST")) return "POST";
                    if (raw.contains("PUT")) return "PUT";
                    if (raw.contains("DELETE")) return "DELETE";
                    if (raw.contains("PATCH")) return "PATCH";
                    return "GET";
                }
                default -> {}
            }
        }
        return "";
    }

    public static String extractHttpPath(MethodDeclaration method) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getName().getIdentifier();
            if (name.endsWith("Mapping") || name.equals("Path")) {
                String val = extractAnnotationStringValue(ann, "value", "path");
                if (!val.isEmpty()) {
                    return normalizePathSegment(val);
                }
            }
        }
        return "";
    }

    public static int extractResponseStatus(MethodDeclaration method, String httpMethod) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            if (ann.getName().getIdentifier().equals("ResponseStatus")) {
                String raw = ann.toString().toUpperCase(Locale.ROOT);
                if (raw.contains("CREATED") || raw.contains("201")) return 201;
                if (raw.contains("NO_CONTENT") || raw.contains("204")) return 204;
                if (raw.contains("ACCEPTED") || raw.contains("202")) return 202;
                if (raw.contains("BAD_REQUEST") || raw.contains("400")) return 400;
            }
        }
        if ("DELETE".equals(httpMethod) && method.getType().isVoidType()) {
            return 204;
        }
        return 200;
    }

    public static ParameterModel buildParameterModel(MethodDeclaration method,
                                                     Parameter p,
                                                     boolean nonNull,
                                                     boolean nullable) {
        String name = p.getNameAsString();
        String typeName = p.getType().asString();
        boolean primitive = p.getType().isPrimitiveType();
        String inLocation = "";
        String paramKey = name;
        boolean required = !nullable && !typeName.startsWith("Optional<");
        boolean validated = hasSimpleAnnotation(p, "Valid", "Validated");

        for (AnnotationExpr ann : p.getAnnotations()) {
            String annName = ann.getName().getIdentifier();
            switch (annName) {
                case "PathVariable", "PathParam" -> {
                    inLocation = "path";
                    String k = extractAnnotationStringValue(ann, "value", "name");
                    if (!k.isEmpty()) paramKey = k;
                    required = !ann.toString().replaceAll("\\s+", "").contains("required=false");
                }
                case "RequestParam", "QueryParam" -> {
                    inLocation = "query";
                    String k = extractAnnotationStringValue(ann, "value", "name");
                    if (!k.isEmpty()) paramKey = k;
                    String compact = ann.toString().replaceAll("\\s+", "");
                    required = !compact.contains("required=false") && !compact.contains("defaultValue=") && !nullable && !typeName.startsWith("Optional<");
                }
                case "RequestHeader", "HeaderParam" -> {
                    inLocation = "header";
                    String k = extractAnnotationStringValue(ann, "value", "name");
                    if (!k.isEmpty()) paramKey = k;
                    String compact = ann.toString().replaceAll("\\s+", "");
                    required = !compact.contains("required=false") && !compact.contains("defaultValue=") && !nullable;
                }
                case "RequestBody" -> {
                    inLocation = "body";
                    required = !ann.toString().replaceAll("\\s+", "").contains("required=false") && !nullable;
                }
                default -> {}
            }
        }
        return new ParameterModel(name, primitive, nonNull, nullable, typeName, inLocation, paramKey, required, validated);
    }

    public static List<MethodFinding> extractMethodFindings(MethodDeclaration method) {
        if (method.getBody().isEmpty()) return List.of();
        List<MethodFinding> findings = new ArrayList<>();
        var body = method.getBody().get();

        // 1. NG201: External / DB / Network call inside a loop
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (isInsideLoop(call, body) && isExternalOrDatabaseCall(call)) {
                int line = call.getRange().map(r -> r.begin.line).orElse(0);
                findings.add(new MethodFinding(
                        "NG201",
                        "warning",
                        "Potential N+1 or blocking I/O inside loop: '" + call.getScope().map(s -> s + ".").orElse("") + call.getNameAsString() + "(...)'",
                        line
                ));
            }
        }

        // 2. NG202: Swallowed / empty catch block
        for (CatchClause catchClause : body.findAll(CatchClause.class)) {
            String paramName = catchClause.getParameter().getNameAsString().toLowerCase(Locale.ROOT);
            if (!paramName.startsWith("ignored") && catchClause.getBody().getStatements().isEmpty()) {
                int line = catchClause.getRange().map(r -> r.begin.line).orElse(0);
                findings.add(new MethodFinding(
                        "NG202",
                        "warning",
                        "Swallowed exception in empty catch block for '" + catchClause.getParameter().getType().asString() + " " + catchClause.getParameter().getNameAsString() + "'",
                        line
                ));
            }
        }

        // 3. NG206: Unclosed AutoCloseable resource outside try-with-resources
        Set<String> closedVars = new LinkedHashSet<>();
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("close") && call.getScope().isPresent()) {
                closedVars.add(call.getScope().get().toString().trim());
            }
        }

        for (VariableDeclarationExpr varDecl : body.findAll(VariableDeclarationExpr.class)) {
            if (isTryResource(varDecl)) continue;
            for (VariableDeclarator var : varDecl.getVariables()) {
                if (var.getInitializer().isEmpty()) continue;
                String rawType = stripGenerics(var.getType().asString());
                Expression init = var.getInitializer().get();
                boolean closeableCreation = CLOSEABLE_TYPES.contains(rawType)
                        && (init.isObjectCreationExpr() || init.isMethodCallExpr());
                if (closeableCreation && !closedVars.contains(var.getNameAsString())) {
                    int line = var.getRange().map(r -> r.begin.line).orElse(0);
                    findings.add(new MethodFinding(
                            "NG206",
                            "warning",
                            "Resource '" + var.getNameAsString() + "' of type '" + rawType + "' is opened outside try-with-resources and never closed",
                            line
                    ));
                }
            }
        }

        return List.copyOf(findings);
    }

    private static boolean isInsideLoop(MethodCallExpr call, Node methodBody) {
        Node cur = call.getParentNode().orElse(null);
        while (cur != null && cur != methodBody) {
            if (cur instanceof ForStmt || cur instanceof ForEachStmt || cur instanceof WhileStmt || cur instanceof DoStmt) {
                return true;
            }
            if (cur instanceof MethodCallExpr parentCall && parentCall.getNameAsString().equals("forEach") && parentCall != call) {
                return true;
            }
            cur = cur.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean isExternalOrDatabaseCall(MethodCallExpr call) {
        String methodName = call.getNameAsString();
        String scope = call.getScope().map(Node::toString).orElse("");
        String scopeLower = scope.toLowerCase(Locale.ROOT);
        boolean repoOrClientScope = scopeLower.endsWith("repository")
                || scopeLower.endsWith("dao")
                || scopeLower.endsWith("client")
                || scopeLower.endsWith("template")
                || scopeLower.contains("resttemplate")
                || scopeLower.contains("webclient")
                || scopeLower.contains("jdbctemplate")
                || scopeLower.contains("entitymanager")
                || scopeLower.contains("httpclient")
                || scopeLower.endsWith("feign");
        boolean dbOrHttpMethod = methodName.startsWith("findBy")
                || methodName.equals("findAll")
                || methodName.equals("save")
                || methodName.equals("saveAll")
                || methodName.startsWith("deleteBy")
                || methodName.equals("executeQuery")
                || methodName.equals("executeUpdate")
                || methodName.equals("getForObject")
                || methodName.equals("getForEntity")
                || methodName.equals("postForObject")
                || methodName.equals("postForEntity")
                || methodName.equals("exchange");
        return repoOrClientScope || (!scope.isEmpty() && dbOrHttpMethod);
    }

    private static boolean isTryResource(VariableDeclarationExpr varDecl) {
        return varDecl.getParentNode()
                .filter(TryStmt.class::isInstance)
                .map(TryStmt.class::cast)
                .map(t -> t.getResources().contains(varDecl))
                .orElse(false);
    }

    private static String stripGenerics(String typeName) {
        int idx = typeName.indexOf('<');
        String base = idx >= 0 ? typeName.substring(0, idx) : typeName;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }

    private static String extractAnnotationStringValue(AnnotationExpr ann, String... keys) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            return cleanAnnotationValue(single.getMemberValue());
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (String key : keys) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if (pair.getNameAsString().equals(key)) {
                        String val = cleanAnnotationValue(pair.getValue());
                        if (!val.isEmpty()) return val;
                    }
                }
            }
        }
        return "";
    }

    private static String cleanAnnotationValue(Expression expr) {
        if (expr == null) return "";
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().getValue();
        }
        if (expr.isArrayInitializerExpr() && !expr.asArrayInitializerExpr().getValues().isEmpty()) {
            return cleanAnnotationValue(expr.asArrayInitializerExpr().getValues().get(0));
        }
        String raw = expr.toString().trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        return "";
    }

    private static String normalizePathSegment(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static boolean hasSimpleAnnotation(NodeWithAnnotations<?> node, String... names) {
        for (AnnotationExpr ann : node.getAnnotations()) {
            String id = ann.getName().getIdentifier();
            for (String n : names) {
                if (id.equals(n)) return true;
            }
        }
        return false;
    }
}
