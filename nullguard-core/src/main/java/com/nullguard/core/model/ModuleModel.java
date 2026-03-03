package com.nullguard.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ModuleModel {
    private final String moduleName;
    private final Map<String, PackageModel> packages;

    private ModuleModel(Builder builder) {
        this.moduleName = Objects.requireNonNull(builder.moduleName, "Module name cannot be null");
        this.packages = Collections.unmodifiableMap(new LinkedHashMap<>(builder.packages));
    }

    public String getModuleName() { return moduleName; }
    public Map<String, PackageModel> getPackages() { return packages; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String moduleName;
        private final Map<String, PackageModel> packages = new LinkedHashMap<>();

        public Builder moduleName(String moduleName) { this.moduleName = moduleName; return this; }
        public Builder addPackage(PackageModel pkg) { this.packages.put(pkg.getPackageName(), pkg); return this; }
        public ModuleModel build() { return new ModuleModel(this); }
    }
}
