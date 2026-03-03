package com.nullguard.core.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ProjectModel {
    private final String projectName;
    private final Map<String, ModuleModel> modules;

    private ProjectModel(Builder builder) {
        this.projectName = Objects.requireNonNull(builder.projectName, "Project name cannot be null");
        this.modules = Collections.unmodifiableMap(new LinkedHashMap<>(builder.modules));
    }

    public String getProjectName() { return projectName; }
    public Map<String, ModuleModel> getModules() { return modules; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String projectName;
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
