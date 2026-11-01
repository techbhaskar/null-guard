"""Network-failure regression coverage for the SARIF validation gate."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import validate_sarif


class SarifSchemaFallbackTests(unittest.TestCase):
    def test_read_timeout_uses_official_mirror_and_still_rejects_invalid_report(self):
        schema = {"type": "object", "required": ["version"]}
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.sarif"
            for payload, expected in [({"version": "2.1.0"}, 0), ({}, 1)]:
                with self.subTest(payload=payload):
                    report.write_text(json.dumps(payload), encoding="utf-8")
                    with patch.object(validate_sarif.sys, "argv", ["validate_sarif", str(report)]), \
                         patch.object(validate_sarif.urllib.request, "urlopen", side_effect=[
                             TimeoutError("schema response stalled"),
                             io.BytesIO(json.dumps(schema).encode("utf-8")),
                         ]) as request:
                        self.assertEqual(expected, validate_sarif.main())
                    self.assertEqual(validate_sarif.SCHEMA_MIRROR, request.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
