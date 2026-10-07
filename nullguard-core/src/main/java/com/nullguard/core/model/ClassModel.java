package com.nullguard.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

public final class ClassModel {
    private final String className;
    private final String qualifiedName;
    private final boolean interfaceType;
    private final Set<String> assignableTypes;
    private final Map<String, MethodModel> methods;

    private ClassModel(Builder builder) {
        this.className = Objects.requireNonNull(builder.className, "Class name cannot be null");
        this.qualifiedName = builder.qualifiedName;
        this.interfaceType = builder.interfaceType;
        this.assignableTypes = Collections.unmodifiableSet(
                new LinkedHashSet<>(builder.assignableTypes));
        this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(builder.methods));
    }

    public String getClassName() { return className; }
    public String getQualifiedName() { return qualifiedName; }
    public boolean isInterfaceType() { return interfaceType; }
    public Set<String> getAssignableTypes() { return assignableTypes; }
    public Map<String, MethodModel> getMethods() { return methods; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String className;
        private String qualifiedName;
        private boolean interfaceType;
        private final Set<String> assignableTypes = new LinkedHashSet<>();
        private final Map<String, MethodModel> methods = new LinkedHashMap<>();

        public Builder className(String className) { this.className = className; return this; }
        public Builder qualifiedName(String qualifiedName) { this.qualifiedName = qualifiedName; return this; }
        public Builder interfaceType(boolean interfaceType) { this.interfaceType = interfaceType; return this; }
        public Builder addAssignableType(String qualifiedType) {
            if (qualifiedType != null && !qualifiedType.isBlank()) assignableTypes.add(qualifiedType);
            return this;
        }
        public Builder addMethod(MethodModel method) { this.methods.put(method.getSignature(), method); return this; }
        public ClassModel build() { return new ClassModel(this); }
    }
}
