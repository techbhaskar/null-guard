package com.nullguard.core.model;

import java.util.List;

/**
 * Immutable descriptor for a field declared on a class or DTO.
 */
public record FieldModel(
        String name,
        String typeName,
        boolean primitive,
        boolean finalField,
        boolean staticField,
        boolean nonNull,
        boolean nullable,
        boolean injected,
        boolean mutated,
        List<String> annotations,
        int line
) {
    public FieldModel {
        name = name == null ? "" : name;
        typeName = typeName == null ? "Object" : typeName;
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
