package com.arinits.nullguard.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** Project-level settings for the NullGuard verification task. */
public abstract class NullGuardExtension {
    public abstract Property<String> getToolVersion();
    public abstract Property<Boolean> getFailBuild();
    public abstract Property<String> getFailThreshold();
    public abstract Property<Boolean> getRunOnCheck();
    public abstract DirectoryProperty getReportDirectory();
    public abstract ListProperty<String> getExcludes();
    public abstract Property<String> getMaxHeapSize();
}
