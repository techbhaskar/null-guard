package com.nullguard.core.model;

public record ParameterModel(
        String name,
        boolean primitive,
        boolean nonNull,
        boolean nullable,
        String typeName,
        String inLocation,
        String paramKey,
        boolean required,
        boolean validated
) {
    public ParameterModel(String name, boolean primitive, boolean nonNull, boolean nullable) {
        this(name, primitive, nonNull, nullable, primitive ? "primitive" : "Object", "", name, !nullable, false);
    }

    public ParameterModel {
        name = name == null ? "" : name;
        typeName = typeName == null ? "Object" : typeName;
        inLocation = inLocation == null ? "" : inLocation;
        paramKey = (paramKey == null || paramKey.isBlank()) ? name : paramKey;
    }
}
