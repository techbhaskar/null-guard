package com.nullguard.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ClassModel {
    private final String className;
    private final String qualifiedName;
    private final boolean interfaceType;
    private final Set<String> assignableTypes;
    private final Map<String, MethodModel> methods;
    private final String basePath;
    private final Set<String> annotations;
    private final List<FieldModel> fields;
    private final SourceLocation sourceLocation;

    private ClassModel(Builder builder) {
        this.className = Objects.requireNonNull(builder.className, "Class name cannot be null");
        this.qualifiedName = builder.qualifiedName;
        this.interfaceType = builder.interfaceType;
        this.assignableTypes = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.assignableTypes));
        this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(builder.methods));
        this.basePath = builder.basePath == null ? "" : builder.basePath;
        this.annotations = Collections.unmodifiableSet(new LinkedHashSet<>(builder.annotations));
        this.fields = List.copyOf(builder.fields);
        this.sourceLocation = builder.sourceLocation;
    }

    public String getClassName() { return className; }
    public String getQualifiedName() { return qualifiedName; }
    public boolean isInterfaceType() { return interfaceType; }
    public Set<String> getAssignableTypes() { return assignableTypes; }
    public Map<String, MethodModel> getMethods() { return methods; }
    public String getBasePath() { return basePath; }
    public Set<String> getAnnotations() { return annotations; }
    public List<FieldModel> getFields() { return fields; }
    public Optional<SourceLocation> getSourceLocation() { return Optional.ofNullable(sourceLocation); }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String className;
        private String qualifiedName;
        private boolean interfaceType;
        private final Set<String> assignableTypes = new LinkedHashSet<>();
        private final Map<String, MethodModel> methods = new LinkedHashMap<>();
        private String basePath = "";
        private final Set<String> annotations = new LinkedHashSet<>();
        private final List<FieldModel> fields = new ArrayList<>();
        private SourceLocation sourceLocation;

        public Builder className(String className) { this.className = className; return this; }
        public Builder qualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; return this; }
        public Builder interfaceType(boolean interfaceType) { this.interfaceType = interfaceType; return this; }
        public Builder basePath(String basePath) { this.basePath = basePath == null ? "" : basePath; return this; }
        public Builder sourceLocation(SourceLocation sourceLocation) { this.sourceLocation = sourceLocation; return this; }
        public Builder addAssignableType(String qualifiedType) {
            if (qualifiedType != null && !qualifiedType.isBlank()) assignableTypes.add(qualifiedType);
            return this;
        }
        public Builder addAnnotation(String annotation) {
            if (annotation != null && !annotation.isBlank()) annotations.add(annotation);
            return this;
        }
        public Builder annotations(Set<String> values) {
            this.annotations.clear();
            if (values != null) this.annotations.addAll(values);
            return this;
        }
        public Builder fields(List<FieldModel> values) {
            this.fields.clear();
            if (values != null) this.fields.addAll(values);
            return this;
        }
        public Builder addMethod(MethodModel method) { this.methods.put(method.getSignature(), method); return this; }
        public ClassModel build() { return new ClassModel(this); }
    }
}
