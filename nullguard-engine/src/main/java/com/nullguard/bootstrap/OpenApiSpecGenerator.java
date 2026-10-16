package com.nullguard.bootstrap;

import com.nullguard.analysis.lattice.NullState;
import com.nullguard.analysis.model.ApiEndpointModel;
import com.nullguard.analysis.summary.MethodSummary;
import com.nullguard.core.model.ClassModel;
import com.nullguard.core.model.FieldModel;
import com.nullguard.core.model.MethodIds;
import com.nullguard.core.model.MethodModel;
import com.nullguard.core.model.ModuleModel;
import com.nullguard.core.model.PackageModel;
import com.nullguard.core.model.ParameterModel;
import com.nullguard.core.model.ProjectModel;
import com.nullguard.core.model.SourceLocation;
import com.nullguard.core.risk.RiskLevel;
import com.nullguard.scoring.model.AdjustedRiskModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates an OpenAPI 3.0.3 specification from the static ProjectModel and ApiEndpointModels,
 * enriched with NullGuard risk telemetry and contract-drift detection (NG301, NG302).
 */
public final class OpenApiSpecGenerator {

    private static final Pattern PATH_VAR_PATTERN = Pattern.compile("\\{([^}/]+)}");

    private OpenApiSpecGenerator() {}

    public static Map<String, Object> generate(
            ProjectModel project,
            List<ApiEndpointModel> endpoints,
            Map<String, AdjustedRiskModel> riskMap) {

        Map<String, MethodModel> methodById = new LinkedHashMap<>();
        Map<String, ClassModel> ownerClassById = new LinkedHashMap<>();
        Map<String, ClassModel> classBySimpleName = new LinkedHashMap<>();
        Map<String, ClassModel> classByQualifiedName = new LinkedHashMap<>();

        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    classBySimpleName.putIfAbsent(cls.getClassName(), cls);
                    if (cls.getQualifiedName() != null) {
                        classByQualifiedName.put(cls.getQualifiedName(), cls);
                    }
                    for (MethodModel m : cls.getMethods().values()) {
                        String id = MethodIds.of(pkg, cls, m);
                        methodById.put(id, m);
                        ownerClassById.put(id, cls);
                    }
                }
            }
        }

        Map<String, Map<String, Object>> paths = new TreeMap<>();
        Map<String, Map<String, Object>> schemas = new TreeMap<>();
        Set<String> visitedDtos = new LinkedHashSet<>();

        for (ApiEndpointModel endpoint : endpoints) {
            String methodId = endpoint.getEndpointId();
            MethodModel method = methodById.get(methodId);
            ClassModel ownerClass = ownerClassById.get(methodId);

            String rawPath = endpoint.getPath() == null || endpoint.getPath().isEmpty() ? "/" : endpoint.getPath();
            String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
            String verb = normalizeVerb(endpoint.getHttpMethod());

            Map<String, Object> pathItem = paths.computeIfAbsent(path, k -> new LinkedHashMap<>());
            Map<String, Object> operation = new LinkedHashMap<>();

            String controllerName = ownerClass != null ? ownerClass.getClassName() : extractClassName(methodId);
            String methodName = method != null ? method.getMethodName() : extractMethodName(methodId);

            operation.put("tags", List.of(controllerName));
            operation.put("summary", controllerName + "." + methodName);
            operation.put("operationId", controllerName + "_" + methodName);

            List<Map<String, Object>> openApiParams = new ArrayList<>();
            Set<String> documentedPathVars = new LinkedHashSet<>();
            Map<String, Object> requestBody = null;

            if (method != null) {
                for (ParameterModel param : method.getParameters()) {
                    if (isFrameworkIgnoredParameter(param.typeName())) {
                        continue;
                    }
                    String inLoc = param.inLocation();
                    if (inLoc.isEmpty()) {
                        if (path.contains("{" + param.name() + "}")) {
                            inLoc = "path";
                        } else if ((verb.equals("post") || verb.equals("put") || verb.equals("patch"))
                                && isComplexDtoType(param.typeName(), classBySimpleName, classByQualifiedName)) {
                            inLoc = "body";
                        } else {
                            inLoc = "query";
                        }
                    }

                    if ("body".equals(inLoc)) {
                        Map<String, Object> bodySchema = buildTypeSchema(
                                param.typeName(), !param.nonNull(), classBySimpleName, classByQualifiedName, schemas, visitedDtos);
                        Map<String, Object> reqBody = new LinkedHashMap<>();
                        reqBody.put("required", param.required());
                        reqBody.put("x-nullguard-validated", param.validated());
                        reqBody.put("content", Map.of("application/json", Map.of("schema", bodySchema)));
                        requestBody = reqBody;
                    } else {
                        if ("path".equals(inLoc)) {
                            documentedPathVars.add(param.paramKey());
                        }
                        Map<String, Object> paramObj = new LinkedHashMap<>();
                        paramObj.put("name", param.paramKey());
                        paramObj.put("in", inLoc);
                        paramObj.put("required", "path".equals(inLoc) || param.required());
                        paramObj.put("schema", buildTypeSchema(
                                param.typeName(), param.nullable() || !param.required(),
                                classBySimpleName, classByQualifiedName, schemas, visitedDtos));
                        paramObj.put("x-nullguard-nonnull", param.nonNull());
                        openApiParams.add(paramObj);
                    }
                }
            }

            Matcher pathVarMatcher = PATH_VAR_PATTERN.matcher(path);
            while (pathVarMatcher.find()) {
                String varName = pathVarMatcher.group(1);
                if (!documentedPathVars.contains(varName)) {
                    Map<String, Object> paramObj = new LinkedHashMap<>();
                    paramObj.put("name", varName);
                    paramObj.put("in", "path");
                    paramObj.put("required", true);
                    paramObj.put("schema", Map.of("type", "string"));
                    openApiParams.add(paramObj);
                }
            }

            if (!openApiParams.isEmpty()) {
                operation.put("parameters", openApiParams);
            }
            if (requestBody != null) {
                operation.put("requestBody", requestBody);
            }

            Map<String, Object> responses = new LinkedHashMap<>();
            int statusCode = method != null ? method.getResponseStatus() : 200;
            String returnType = method != null ? method.getReturnType() : "Object";
            String unwrappedReturn = unwrapResponseContainer(returnType);

            Map<String, Object> primaryResponse = new LinkedHashMap<>();
            primaryResponse.put("description", httpStatusDescription(statusCode));
            if (!"void".equalsIgnoreCase(unwrappedReturn)) {
                boolean responseNullable = method != null && (method.isNullableReturn() || (!method.isNonNullReturn() && !method.isPrimitiveReturn()));
                Map<String, Object> respSchema = buildTypeSchema(
                        unwrappedReturn, responseNullable, classBySimpleName, classByQualifiedName, schemas, visitedDtos);
                primaryResponse.put("content", Map.of("application/json", Map.of("schema", respSchema)));
            }
            responses.put(String.valueOf(statusCode), primaryResponse);

            if (requestBody != null || !openApiParams.isEmpty()) {
                responses.put("400", Map.of("description", "Bad Request / Invalid Input Contract"));
            }
            double riskScore = Math.round(endpoint.getApiRiskScore() * 100.0) / 100.0;
            if (riskScore >= 60.0) {
                responses.put("500", Map.of(
                        "description",
                        "High Downstream Null-Safety Risk Detected (NullGuard Score: " + String.format(Locale.ROOT, "%.2f", riskScore) + ")"
                ));
            }
            operation.put("responses", responses);

            operation.put("x-nullguard-endpoint-id", endpoint.getEndpointId());
            operation.put("x-nullguard-risk-score", riskScore);
            operation.put("x-nullguard-risk-level", RiskLevel.from(endpoint.getApiRiskScore()).name());
            operation.put("x-nullguard-propagation-depth", endpoint.getPropagationDepth());
            operation.put("x-nullguard-blast-chain", endpoint.getPropagationChain());
            operation.put("x-nullguard-hotspots", endpoint.getHotspotIndicators());

            pathItem.put(verb, operation);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", (project.getProjectName() == null ? "Service" : project.getProjectName()) + " API");
        info.put("version", "1.1.0");
        info.put("description", "Statically extracted OpenAPI 3.0.3 specification with NullGuard blast-radius and nullability telemetry.");
        spec.put("info", info);
        spec.put("paths", paths);
        spec.put("components", Map.of("schemas", schemas));
        return spec;
    }

    /**
     * Collects OpenAPI contract drift findings (NG301, NG302) for controller endpoints.
     */
    public static List<Finding> collectContractDriftFindings(ProjectModel project, List<ApiEndpointModel> endpoints) {
        Map<String, MethodModel> methodById = new LinkedHashMap<>();
        Map<String, ClassModel> ownerClassById = new LinkedHashMap<>();

        for (ModuleModel mod : project.getModules().values()) {
            for (PackageModel pkg : mod.getPackages().values()) {
                for (ClassModel cls : pkg.getClasses().values()) {
                    for (MethodModel m : cls.getMethods().values()) {
                        String id = MethodIds.of(pkg, cls, m);
                        methodById.put(id, m);
                        ownerClassById.put(id, cls);
                    }
                }
            }
        }

        List<Finding> findings = new ArrayList<>();
        for (ApiEndpointModel endpoint : endpoints) {
            String methodId = endpoint.getEndpointId();
            MethodModel method = methodById.get(methodId);
            ClassModel owner = ownerClassById.get(methodId);
            if (method == null || method.getSourceLocation().isEmpty()) continue;

            SourceLocation loc = method.getSourceLocation().get();
            boolean explicitRestController = owner != null && (owner.getAnnotations().contains("RestController") || owner.getAnnotations().contains("Controller"));

            // NG301: Controller endpoint on @RestController/@Controller returns NULL/UNKNOWN without @Nullable or Optional/ResponseEntity wrapper
            if (explicitRestController
                    && !method.isPrimitiveReturn()
                    && !"void".equalsIgnoreCase(method.getReturnType())
                    && !method.isNullableReturn()
                    && !method.getReturnType().startsWith("Optional<")
                    && !method.getReturnType().startsWith("ResponseEntity<")) {
                var summaryOpt = method.getMethodSummary();
                if (summaryOpt.isPresent() && summaryOpt.get() instanceof MethodSummary ms) {
                    NullState retState = ms.getReturnNullability();
                    if (retState == NullState.NULL || retState == NullState.UNKNOWN) {
                        findings.add(new Finding(
                                "NG301",
                                "warning",
                                methodId,
                                "OpenAPI contract drift: endpoint '" + endpoint.getHttpMethod() + " " + endpoint.getPath()
                                        + "' declares non-null response type '" + method.getReturnType()
                                        + "' but static return flow is " + retState,
                                loc
                        ));
                    }
                }
            }

            // NG302: @RequestBody parameter without @Valid/@Validated on an endpoint
            for (ParameterModel param : method.getParameters()) {
                if ("body".equals(param.inLocation()) && !param.validated()) {
                    findings.add(new Finding(
                            "NG302",
                            "warning",
                            methodId,
                            "OpenAPI input contract drift: @RequestBody parameter '" + param.name()
                                    + "' (" + param.typeName() + ") on '" + endpoint.getHttpMethod() + " " + endpoint.getPath()
                                    + "' is missing @Valid / @Validated bean validation",
                            loc
                    ));
                }
            }
        }
        return findings;
    }

    private static Map<String, Object> buildTypeSchema(
            String rawType,
            boolean nullable,
            Map<String, ClassModel> classBySimpleName,
            Map<String, ClassModel> classByQualifiedName,
            Map<String, Map<String, Object>> schemas,
            Set<String> visitedDtos) {

        String type = rawType == null ? "Object" : rawType.trim();
        if (type.startsWith("Optional<") && type.endsWith(">")) {
            return buildTypeSchema(type.substring(9, type.length() - 1).trim(), true,
                    classBySimpleName, classByQualifiedName, schemas, visitedDtos);
        }
        if ((type.startsWith("List<") || type.startsWith("Set<") || type.startsWith("Collection<") || type.startsWith("Flux<"))
                && type.endsWith(">")) {
            String inner = type.substring(type.indexOf('<') + 1, type.length() - 1).trim();
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "array");
            schema.put("items", buildTypeSchema(inner, false, classBySimpleName, classByQualifiedName, schemas, visitedDtos));
            if (nullable) schema.put("nullable", true);
            return schema;
        }
        if (type.endsWith("[]")) {
            String inner = type.substring(0, type.length() - 2).trim();
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "array");
            schema.put("items", buildTypeSchema(inner, false, classBySimpleName, classByQualifiedName, schemas, visitedDtos));
            if (nullable) schema.put("nullable", true);
            return schema;
        }
        if (type.startsWith("Map<") && type.endsWith(">")) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("additionalProperties", true);
            if (nullable) schema.put("nullable", true);
            return schema;
        }

        String simple = stripPackageAndGenerics(type);
        Map<String, Object> scalar = scalarSchema(simple);
        if (scalar != null) {
            if (nullable) scalar.put("nullable", true);
            return scalar;
        }

        ClassModel dtoClass = classByQualifiedName.getOrDefault(type, classBySimpleName.get(simple));
        if (dtoClass != null && isDtoCandidate(dtoClass)) {
            registerDtoSchema(dtoClass, classBySimpleName, classByQualifiedName, schemas, visitedDtos);
            Map<String, Object> refSchema = new LinkedHashMap<>();
            refSchema.put("$ref", "#/components/schemas/" + dtoClass.getClassName());
            if (nullable) refSchema.put("nullable", true);
            return refSchema;
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", "object");
        fallback.put("x-java-type", type);
        if (nullable) fallback.put("nullable", true);
        return fallback;
    }

    private static void registerDtoSchema(
            ClassModel dtoClass,
            Map<String, ClassModel> classBySimpleName,
            Map<String, ClassModel> classByQualifiedName,
            Map<String, Map<String, Object>> schemas,
            Set<String> visitedDtos) {

        String schemaName = dtoClass.getClassName();
        if (!visitedDtos.add(schemaName)) {
            return;
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> requiredFields = new ArrayList<>();

        for (FieldModel field : dtoClass.getFields()) {
            if (field.staticField()) continue;
            if (field.nonNull()) {
                requiredFields.add(field.name());
            }
            Map<String, Object> propSchema = new LinkedHashMap<>(buildTypeSchema(
                    field.typeName(), !field.nonNull(), classBySimpleName, classByQualifiedName, schemas, visitedDtos));
            propSchema.put("nullable", !field.nonNull());
            if (field.mutated()) {
                propSchema.put("x-nullguard-mutated", true);
            }
            properties.put(field.name(), propSchema);
        }

        Map<String, Object> dtoSchema = new LinkedHashMap<>();
        dtoSchema.put("type", "object");
        if (!requiredFields.isEmpty()) {
            dtoSchema.put("required", requiredFields);
        }
        dtoSchema.put("properties", properties);
        schemas.put(schemaName, dtoSchema);
    }

    private static boolean isDtoCandidate(ClassModel cls) {
        if (cls.isInterfaceType()) return false;
        Set<String> anns = cls.getAnnotations();
        if (anns.contains("RestController") || anns.contains("Controller")
                || anns.contains("Service") || anns.contains("Repository")
                || anns.contains("Component") || anns.contains("Configuration")) {
            return false;
        }
        String name = cls.getClassName();
        return !name.endsWith("Controller") && !name.endsWith("Service") && !name.endsWith("Repository");
    }

    private static boolean isComplexDtoType(
            String typeName,
            Map<String, ClassModel> classBySimpleName,
            Map<String, ClassModel> classByQualifiedName) {
        String simple = stripPackageAndGenerics(typeName);
        if (scalarSchema(simple) != null) return false;
        ClassModel cls = classByQualifiedName.getOrDefault(typeName, classBySimpleName.get(simple));
        return cls != null ? isDtoCandidate(cls) : !simple.equals("Object");
    }

    private static Map<String, Object> scalarSchema(String simpleType) {
        Map<String, Object> schema = new LinkedHashMap<>();
        switch (simpleType) {
            case "String", "CharSequence", "Character", "char" -> schema.put("type", "string");
            case "UUID" -> { schema.put("type", "string"); schema.put("format", "uuid"); }
            case "LocalDate", "Date" -> { schema.put("type", "string"); schema.put("format", "date"); }
            case "LocalDateTime", "Instant", "OffsetDateTime", "ZonedDateTime" -> {
                schema.put("type", "string");
                schema.put("format", "date-time");
            }
            case "int", "Integer", "short", "Short", "byte", "Byte" -> {
                schema.put("type", "integer");
                schema.put("format", "int32");
            }
            case "long", "Long", "BigInteger" -> {
                schema.put("type", "integer");
                schema.put("format", "int64");
            }
            case "double", "Double", "float", "Float", "BigDecimal" -> schema.put("type", "number");
            case "boolean", "Boolean" -> schema.put("type", "boolean");
            default -> { return null; }
        }
        return schema;
    }

    private static String unwrapResponseContainer(String returnType) {
        if (returnType == null || returnType.isBlank()) return "void";
        String trimmed = returnType.trim();
        for (String wrapper : List.of("ResponseEntity<", "HttpEntity<", "CompletableFuture<", "Mono<")) {
            if (trimmed.startsWith(wrapper) && trimmed.endsWith(">")) {
                return trimmed.substring(wrapper.length(), trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private static boolean isFrameworkIgnoredParameter(String typeName) {
        String simple = stripPackageAndGenerics(typeName);
        return Set.of("HttpServletRequest", "HttpServletResponse", "HttpSession",
                "Principal", "Authentication", "BindingResult", "Errors", "Model", "Pageable").contains(simple);
    }

    private static String stripPackageAndGenerics(String typeName) {
        if (typeName == null) return "Object";
        int gen = typeName.indexOf('<');
        String base = gen >= 0 ? typeName.substring(0, gen) : typeName;
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1).trim() : base.trim();
    }

    private static String normalizeVerb(String httpMethod) {
        if (httpMethod == null || httpMethod.isBlank() || "UNKNOWN".equalsIgnoreCase(httpMethod)) {
            return "get";
        }
        return httpMethod.toLowerCase(Locale.ROOT);
    }

    private static String httpStatusDescription(int statusCode) {
        return switch (statusCode) {
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            default -> "OK";
        };
    }

    private static String extractClassName(String methodId) {
        int hash = methodId.lastIndexOf('#');
        int dot = hash >= 0 ? methodId.lastIndexOf('.', hash) : methodId.lastIndexOf('.');
        if (hash >= 0 && dot >= 0 && dot < hash) {
            return methodId.substring(dot + 1, hash);
        }
        return hash > 0 ? methodId.substring(0, hash) : "Controller";
    }

    private static String extractMethodName(String methodId) {
        int hash = methodId.lastIndexOf('#');
        int paren = methodId.indexOf('(', Math.max(0, hash));
        if (hash >= 0 && paren > hash) {
            return methodId.substring(hash + 1, paren);
        }
        return methodId;
    }
}
