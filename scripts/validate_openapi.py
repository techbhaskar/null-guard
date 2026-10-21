"""Independent OpenAPI 3.0 validation, plus regression checks for nullable DTO references.

Install openapi-spec-validator==0.7.2. Pass generated JSON files as arguments.
Never resolves remote references: exported NullGuard documents must be self-contained.
"""
import json
import sys
from pathlib import Path
from openapi_spec_validator import OpenAPIV30SpecValidator
from openapi_schema_validator import OAS30Validator


def validate(path):
    spec = json.loads(path.read_text(encoding="utf-8"))
    def check_refs(value):
        if isinstance(value, dict):
            if "$ref" in value:
                assert value["$ref"].startswith("#/"), "External references are not allowed"
                assert len(value) == 1, "OpenAPI 3.0 Reference Object siblings would be ignored"
            for child in value.values():
                check_refs(child)
        elif isinstance(value, list):
            for child in value:
                check_refs(child)
    check_refs(spec)
    OpenAPIV30SpecValidator(spec).validate()
    # Exercise instance semantics, not only the meta-schema's structure.
    for item in spec["paths"].values():
        for operation in item.values():
            for response in operation.get("responses", {}).values():
                schema = response.get("content", {}).get("application/json", {}).get("schema", {})
                if "anyOf" in schema and any(branch.get("enum") == [None] for branch in schema["anyOf"]):
                    local = dict(schema, components=spec.get("components", {}))
                    validator = OAS30Validator(local)
                    validator.validate(None)
                    assert not validator.is_valid("not-an-object"), "Nullable DTO must not accept strings"
    print(f"OpenAPI validation passed: {path}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Provide one or more generated OpenAPI JSON files")
    for argument in sys.argv[1:]:
        validate(Path(argument))
