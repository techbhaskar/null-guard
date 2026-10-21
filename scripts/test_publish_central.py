import argparse
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import publish_central


class ReleaseSafetyTest(unittest.TestCase):
    def test_missing_components_cannot_create_a_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = argparse.Namespace(staging=[root], version="1.1.1", output=root / "bundle.zip", gpg="gpg")
            with self.assertRaisesRegex(ValueError, "Expected 13 components"):
                publish_central.bundle(args)
            self.assertFalse(args.output.exists())

    def test_existing_upload_state_prevents_duplicate_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "deployment.json"
            state.write_text('{"state":"UPLOAD_INTENT"}', encoding="utf-8")
            with patch.object(publish_central, "request") as request:
                with self.assertRaisesRegex(ValueError, "Deployment state already exists"):
                    publish_central.upload(argparse.Namespace(state=state))
                request.assert_not_called()

    def test_bundle_mutation_prevents_upload(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bundle = root / "bundle.zip"
            bundle.write_bytes(b"changed")
            bundle.with_suffix(".manifest.json").write_text(json.dumps({"bundleSha256": "not-matching"}), encoding="utf-8")
            with patch.object(publish_central, "request") as request:
                with self.assertRaisesRegex(ValueError, "Bundle changed"):
                    publish_central.upload(argparse.Namespace(state=root / "state.json", bundle=bundle))
                request.assert_not_called()

    def test_credentials_read_without_being_logged(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = Path(directory) / "settings.xml"
            for namespace in ("http://maven.apache.org/SETTINGS/1.0.0", "http://maven.apache.org/SETTINGS/1.2.0"):
                settings.write_text(f'<settings xmlns="{namespace}"><servers><server><id>central</id>'
                                    '<username>example</username><password>example</password></server></servers></settings>', encoding="utf-8")
                self.assertEqual("Bearer ZXhhbXBsZTpleGFtcGxl", publish_central.authorization(settings))


if __name__ == "__main__":
    unittest.main()
