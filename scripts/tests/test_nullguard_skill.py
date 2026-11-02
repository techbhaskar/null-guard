"""Behavioral regression tests for the distributable NullGuard skill runner."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from contextlib import redirect_stderr, redirect_stdout


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "nullguard_skill_runner",
    ROOT / "skills/nullguard-audit/scripts/run_nullguard.py",
)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def java_report():
    return {
        "schemaVersion": "1.0",
        "summary": {"totalMethods": 1, "stabilityIndex": 85.0, "grade": "B"},
        "graph": {"nodes": [], "edges": []},
        "analysisCompleteness": {"status": "SOURCE_ANALYZED"},
        "findings": [], "hotspots": [], "suggestions": [], "apiEndpoints": [],
    }


def pack_report():
    return {
        "schemaVersion": "2.0", "analyzerVersion": "2.0.0-preview.1",
        "preview": True, "status": "complete", "diagnostics": [],
        "projects": [{
            "protocolVersion": "1.0", "analyzerVersion": "2.0.0-preview.1",
            "project": "/service", "languages": ["python"], "status": "complete",
            "findings": [], "diagnostics": [], "capabilities": {},
            "coverage": {"python": {
                "discoveredFiles": 1, "analyzedFiles": 1, "failedFiles": 0,
                "unsupportedFiles": 0, "unresolvedSymbols": 0,
                "budgetExhausted": False, "missingDependencies": [],
            }},
        }],
    }


class LanguageSelectionTests(unittest.TestCase):
    def test_auto_covers_requested_languages_without_other_packs(self):
        self.assertEqual(runner.parse_languages("auto"),
                         ["java", "python", "javascript", "typescript", "csharp"])

    def test_language_aliases(self):
        self.assertEqual(runner.parse_languages("dotnet,js,ts"),
                         ["csharp", "javascript", "typescript"])
        self.assertEqual(runner.parse_languages("c#"), ["csharp"])

    def test_invalid_language_or_empty_selection_is_rejected(self):
        for value in ("", "python,", "auto,python", "ruby", "go", "rust"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                runner.parse_languages(value)


class ProjectDetectionTests(unittest.TestCase):
    def test_native_build_detection_and_source_only_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(runner.detect_build(root), "cli")
            (root / "pom.xml").write_text("<project/>")
            self.assertEqual(runner.detect_build(root), "maven")
            (root / "pom.xml").unlink()
            for filename in ("build.gradle", "build.gradle.kts"):
                (root / filename).write_text("")
                self.assertEqual(runner.detect_build(root), "gradle")
                (root / filename).unlink()

    def test_mixed_build_requires_explicit_choice(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").touch()
            (root / "build.gradle.kts").touch()
            with self.assertRaises(ValueError):
                runner.detect_build(root)

    def test_project_wrapper_precedes_global_tool(self):
        with tempfile.TemporaryDirectory(prefix="nullguard project ") as directory:
            root = Path(directory)
            for tool, filename in (("maven", "mvnw.cmd" if os.name == "nt" else "mvnw"),
                                   ("gradle", "gradlew.bat" if os.name == "nt" else "gradlew")):
                with self.subTest(tool=tool):
                    wrapper = root / filename
                    wrapper.write_text("wrapper")
                    wrapper.chmod(0o755)
                    with patch.object(runner.shutil, "which", return_value="/global/tool"):
                        self.assertEqual(Path(runner.choose_tool(root, tool)), wrapper)


class ReportInterpretationTests(unittest.TestCase):
    def test_both_report_schemas_are_recognized(self):
        for report in (java_report(), pack_report()):
            with self.subTest(schema=report["schemaVersion"]):
                result = runner.summarize_report(report)
                self.assertEqual(result["schemaVersion"], report["schemaVersion"])
                self.assertIn("status", result)

    def test_unknown_and_missing_schema_never_become_success(self):
        for report in ({}, {"schemaVersion": "9.0"}, [], None):
            with self.subTest(report=report), self.assertRaises(ValueError):
                runner.summarize_report(report)

    def test_missing_java_completeness_remains_unknown(self):
        report = java_report()
        del report["analysisCompleteness"]
        result = runner.summarize_report(report)
        self.assertIn("UNKNOWN", json.dumps(result).upper())

    def test_java_no_source_or_incomplete_not_reported_as_clean(self):
        for status in ("NO_SOURCE", "UNAVAILABLE", "INCOMPLETE", "LIMITED"):
            with self.subTest(status=status):
                report = java_report()
                report["analysisCompleteness"]["status"] = status
                result = runner.summarize_report(report)
                self.assertIn(status, json.dumps(result))

    def test_java_summary_must_be_an_object(self):
        for value in (None, [], "clean"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                report = java_report()
                report["summary"] = value
                runner.summarize_report(report)

    def test_partial_and_failed_projects_keep_their_status(self):
        for status in ("partial", "failed"):
            with self.subTest(status=status):
                report = pack_report()
                report["status"] = status
                report["projects"][0]["status"] = status
                self.assertEqual(runner.summarize_report(report)["status"], status)

    def test_invalid_top_level_pack_payload_is_rejected(self):
        for key, value in (("status", "clean"), ("projects", {}),
                           ("diagnostics", "none")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                report = pack_report()
                report[key] = value
                runner.summarize_report(report)

    def test_incomplete_project_structure_is_rejected(self):
        for key in ("languages", "status", "findings", "coverage", "diagnostics", "capabilities"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                report = pack_report()
                del report["projects"][0][key]
                runner.summarize_report(report)

    def test_invalid_project_field_types_are_rejected(self):
        invalid = {"languages": [], "status": "unknown", "findings": {},
                   "coverage": [], "diagnostics": "none", "capabilities": []}
        for key, value in invalid.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                report = pack_report()
                report["projects"][0][key] = value
                runner.summarize_report(report)

    def test_invalid_coverage_accounting_and_types_are_rejected(self):
        invalid = (("discoveredFiles", -1), ("analyzedFiles", True),
                   ("failedFiles", "0"), ("unsupportedFiles", 0.5),
                   ("unresolvedSymbols", -1), ("budgetExhausted", 0),
                   ("missingDependencies", "none"), ("analyzedFiles", 2))
        for key, value in invalid:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                report = pack_report()
                report["projects"][0]["coverage"]["python"][key] = value
                runner.summarize_report(report)

    def test_complete_project_cannot_hide_coverage_gaps(self):
        for key, value in (("analyzedFiles", 0), ("budgetExhausted", True),
                           ("missingDependencies", ["restore assets missing"])):
            with self.subTest(key=key), self.assertRaises(ValueError):
                report = pack_report()
                report["projects"][0]["coverage"]["python"][key] = value
                runner.summarize_report(report)

    def test_unresolved_symbols_remain_visible_without_inventing_failure(self):
        report = pack_report()
        report["projects"][0]["coverage"]["python"]["unresolvedSymbols"] = 2
        result = runner.summarize_report(report)
        self.assertEqual(result["projects"][0]["coverage"]["python"]["unresolvedSymbols"], 2)

    def test_mixed_language_report_preserves_independent_projects(self):
        report = pack_report()
        for language in ("csharp", "javascript", "typescript", "java"):
            project = copy.deepcopy(report["projects"][0])
            project["project"] = "/service/" + language
            project["languages"] = [language]
            project["coverage"] = {language: project["coverage"]["python"]}
            report["projects"].append(project)
        result = runner.summarize_report(report)
        self.assertEqual(len(result["projects"]), 5)


class ReportFreshnessTests(unittest.TestCase):
    def test_unchanged_existing_report_is_not_fresh(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report = root / "target/nullguard/nullguard-report-latest.json"
            report.parent.mkdir(parents=True)
            report.write_text(json.dumps(java_report()))
            before = runner.snapshot_reports(root)
            self.assertEqual(runner.fresh_reports(root, before), [])

    def test_new_and_rewritten_reports_are_fresh_with_spaces_in_path(self):
        with tempfile.TemporaryDirectory(prefix="nullguard reports ") as directory:
            root = Path(directory)
            report = root / "build/reports/nullguard/nullguard-report-latest.json"
            report.parent.mkdir(parents=True)
            before = runner.snapshot_reports(root)
            report.write_text(json.dumps(java_report()))
            self.assertIn(report, runner.fresh_reports(root, before))
            before = runner.snapshot_reports(root)
            report.write_text(json.dumps(pack_report()))
            self.assertIn(report, runner.fresh_reports(root, before))


class RunnerExecutionTests(unittest.TestCase):
    def invoke_cli(self, report, code=0, languages="auto"):
        with tempfile.TemporaryDirectory(prefix="nullguard execution ") as directory:
            root = Path(directory)
            jar = root / "verified cli.jar"
            jar.touch()
            observed = []

            def analyze(command, project):
                observed.append(command)
                output = Path(next(arg.split("=", 1)[1] for arg in command
                                   if arg.startswith("--output=")))
                output.mkdir(parents=True)
                (output / runner.REPORT).write_text(json.dumps(report))
                return code

            stdout = io.StringIO()
            with patch.object(runner, "require_java", return_value="/java21"), \
                    patch.object(runner, "execute", side_effect=analyze), \
                    redirect_stdout(stdout), redirect_stderr(io.StringIO()):
                result = runner.main(["--project", str(root), "--cli-jar", str(jar),
                                      "--languages", languages])
            return result, json.loads(stdout.getvalue()), observed[0]

    def test_mixed_run_requests_all_five_languages_and_emits_report_paths(self):
        code, output, command = self.invoke_cli(pack_report())
        self.assertEqual(code, 0)
        self.assertIn("--languages=java,python,javascript,typescript,csharp", command)
        self.assertEqual(output["reports"][0]["schemaVersion"], "2.0")
        self.assertTrue(output["reports"][0]["path"].endswith(runner.REPORT))
        self.assertFalse(any(arg.startswith("--fail") for arg in command))

    def test_cli_execution_failure_retains_available_reports_and_exit_code(self):
        code, output, _ = self.invoke_cli(pack_report(), code=7)
        self.assertEqual(code, 7)
        self.assertEqual(output["engineExitCode"], 7)
        self.assertEqual(len(output["reports"]), 1)

    def test_partial_results_cannot_become_success_even_if_process_returns_zero(self):
        report = pack_report()
        report["status"] = "partial"
        report["projects"][0]["status"] = "partial"
        self.assertEqual(self.invoke_cli(report)[0], 3)

    def test_java_cli_does_not_select_preview_language_mode(self):
        code, _, command = self.invoke_cli(java_report(), languages="java")
        self.assertEqual(code, 0)
        self.assertFalse(any(arg.startswith("--languages=") for arg in command))

    def test_native_mode_cannot_silently_ignore_requested_non_java_languages(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(runner, "execute") as execute, redirect_stderr(io.StringIO()):
                code = runner.main(["--project", directory, "--build-tool", "maven",
                                    "--languages", "python"])
            self.assertEqual(code, 3)
            execute.assert_not_called()

    def test_missing_fresh_report_is_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").touch()
            stdout = io.StringIO()
            with patch.object(runner, "require_java", return_value="/java21"), \
                    patch.object(runner, "choose_tool", return_value="/mvn"), \
                    patch.object(runner, "execute", return_value=0), \
                    redirect_stdout(stdout), redirect_stderr(io.StringIO()):
                code = runner.main(["--project", directory, "--languages", "java"])
            self.assertEqual(code, 3)
            self.assertEqual(json.loads(stdout.getvalue())["reports"], [])


if __name__ == "__main__":
    unittest.main()
