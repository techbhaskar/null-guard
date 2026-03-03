package com.nullguard.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PackageModel {
    private final String packageName;
    private final Map<String, ClassModel> classes;

    private PackageModel(Builder builder) {
        this.packageName = Objects.requireNonNull(builder.packageName, "Package name cannot be null");
        this.classes = Collections.unmodifiableMap(new LinkedHashMap<>(builder.classes));
    }

    public String getPackageName() { return packageName; }
    public Map<String, ClassModel> getClasses() { return classes; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String packageName;
        private final Map<String, ClassModel> classes = new LinkedHashMap<>();

        public Builder packageName(String packageName) { this.packageName = packageName; return this; }
        public Builder addClass(ClassModel classModel) { this.classes.put(classModel.getClassName(), classModel); return this; }
        public PackageModel build() { return new PackageModel(this); }
    }
}
