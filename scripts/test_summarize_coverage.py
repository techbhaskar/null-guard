"""Coverage gating must not count partially covered lines as fully covered."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("summarize_coverage.py").resolve()


class CoverageGateTest(unittest.TestCase):
    def run_gate(self, xml, minimum):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "nullguard-coverage/target/site/jacoco-aggregate/jacoco.xml"
            report.parent.mkdir(parents=True)
            if xml is not None:
                report.write_text(xml, encoding="utf-8")
            return subprocess.run([sys.executable, str(SCRIPT), f"--min-full-line={minimum}"],
                                  cwd=directory, capture_output=True, text=True)

    def test_partial_instructions_and_branches_are_not_full_lines(self):
        xml = '''<report><group name="sample"><package name="demo"><sourcefile name="Demo.java">
          <line nr="1" mi="0" ci="3" mb="0" cb="0"/>
          <line nr="2" mi="1" ci="2" mb="0" cb="0"/>
          <line nr="3" mi="0" ci="3" mb="1" cb="1"/>
          <line nr="4" mi="3" ci="0" mb="0" cb="0"/>
        </sourcefile></package></group></report>'''
        passed = self.run_gate(xml, 25)
        self.assertEqual(0, passed.returncode, passed.stdout + passed.stderr)
        self.assertIn("1/4 (25.00%)", passed.stdout)
        self.assertNotEqual(0, self.run_gate(xml, 25.01).returncode)

    def test_empty_and_missing_reports_cannot_pass(self):
        self.assertNotEqual(0, self.run_gate("<report/>", 85).returncode)
        self.assertNotEqual(0, self.run_gate(None, 85).returncode)


if __name__ == "__main__":
    unittest.main()
