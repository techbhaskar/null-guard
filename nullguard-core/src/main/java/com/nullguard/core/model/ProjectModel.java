package com.nullguard.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ProjectModel {
    private final String projectName;
    private final Map<String, ModuleModel> modules;
    private final ParseDiagnostics parseDiagnostics;

    private ProjectModel(Builder builder) {
        this.projectName = Objects.requireNonNull(builder.projectName, "Project name cannot be null");
        this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(builder.modules));
        this.parseDiagnostics = builder.parseDiagnostics;
    }

    public String getProjectName() { return projectName; }
    public Map<String, ModuleModel> getModules() { return modules; }
    public ParseDiagnostics getParseDiagnostics() { return parseDiagnostics; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String projectName;
        private ParseDiagnostics parseDiagnostics = ParseDiagnostics.unavailable();
        public Builder parseDiagnostics(ParseDiagnostics value) { this.parseDiagnostics = value; return this; }
        private final Map<String, ModuleModel> modules = new LinkedHashMap<>();

        public Builder projectName(String projectName) {
            this.projectName = projectName;
            return this;
        }

        public Builder addModule(ModuleModel module) {
            this.modules.put(module.getModuleName(), module);
            return this;
        }

        public ProjectModel build() { return new ProjectModel(this); }
    }
}
