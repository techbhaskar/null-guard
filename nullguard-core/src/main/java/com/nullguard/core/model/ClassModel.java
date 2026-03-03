package com.nullguard.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ClassModel {
    private final String className;
    private final Map<String, MethodModel> methods;

    private ClassModel(Builder builder) {
        this.className = Objects.requireNonNull(builder.className, "Class name cannot be null");
        this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(builder.methods));
    }

    public String getClassName() { return className; }
    public Map<String, MethodModel> getMethods() { return methods; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String className;
        private final Map<String, MethodModel> methods = new LinkedHashMap<>();

        public Builder className(String className) { this.className = className; return this; }
        public Builder addMethod(MethodModel method) { this.methods.put(method.getSignature(), method); return this; }
        public ClassModel build() { return new ClassModel(this); }
    }
}
