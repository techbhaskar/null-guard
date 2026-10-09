"""Validate a generated report against the official OASIS SARIF 2.1.0 schema.

Usage: python scripts/validate_sarif.py path/to/nullguard-results.sarif
Requires jsonschema; uses the schema from OASIS without modifying the report.
"""
import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

from jsonschema import FormatChecker
from jsonschema.validators import validator_for

SCHEMA_URL = "https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/schemas/sarif-schema-2.1.0.json"
SCHEMA_MIRROR = "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/main/sarif-2.1/schema/sarif-schema-2.1.0.json"


def main():
    try:
        with urllib.request.urlopen(SCHEMA_URL, timeout=30) as response:
            schema = json.load(response)
    except urllib.error.URLError as error:
        print(f"OASIS schema host unavailable ({error}); using its official GitHub mirror")
        with urllib.request.urlopen(SCHEMA_MIRROR, timeout=30) as response:
            schema = json.load(response)
    report_path = Path(sys.argv[1])
    report = json.loads(report_path.read_text(encoding="utf-8"))
    validator = validator_for(schema)
    validator.check_schema(schema)
    errors = list(validator(schema, format_checker=FormatChecker()).iter_errors(report))
    if errors:
        for error in errors:
            print(f"{'/'.join(str(item) for item in error.absolute_path)}: {error.message}")
        return 1
    print(f"SARIF schema validation passed: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
