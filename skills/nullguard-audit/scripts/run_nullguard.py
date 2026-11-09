#!/usr/bin/env python3
"""Run report-only NullGuard analysis and summarize only this invocation's reports."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

VERSION = "2.0.1"
REPORT = "nullguard-report-latest.json"
LANGUAGES = ["java", "python", "javascript", "typescript", "csharp"]
SKIP = {".git", "node_modules", ".venv", "venv", "vendor", ".gradle"}


def parse_languages(value):
    if value == "auto":
        return LANGUAGES.copy()
    aliases = {"dotnet": "csharp", "c#": "csharp", "js": "javascript", "ts": "typescript"}
    result = []
    for item in value.split(","):
        item = aliases.get(item.strip().lower(), item.strip().lower())
        if item not in LANGUAGES:
            raise ValueError("Languages must be auto or comma-separated: " + ",".join(LANGUAGES))
        if item not in result:
            result.append(item)
    return result


def detect_build(project):
    maven = (project / "pom.xml").is_file()
    gradle = any((project / name).is_file() for name in ("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"))
    if maven and gradle:
        raise ValueError("Both Maven and Gradle found; select --build-tool explicitly")
    return "maven" if maven else "gradle" if gradle else "cli"


def choose_tool(project, name):
    wrapper = {"maven": "mvnw.cmd" if os.name == "nt" else "mvnw",
               "gradle": "gradlew.bat" if os.name == "nt" else "gradlew"}[name]
    if (project / wrapper).is_file():
        return str(project / wrapper)
    installed = shutil.which("mvn" if name == "maven" else "gradle")
    if not installed:
        raise ValueError("No " + name + " wrapper or installed executable found")
    return installed


def snapshot_reports(project):
    result = {}
    for root, dirs, files in os.walk(project):
        dirs[:] = [d for d in dirs if d not in SKIP]
        if REPORT in files:
            path = Path(root) / REPORT
            stat = path.stat()
            result[path] = (stat.st_mtime_ns, stat.st_ctime_ns, stat.st_size, stat.st_ino)
    return result


def fresh_reports(project, before):
    return sorted(path for path, fingerprint in snapshot_reports(project).items()
                  if before.get(path) != fingerprint)


def summarize_report(report):
    if not isinstance(report, dict):
        raise ValueError("Report must be a JSON object")
    schema = report.get("schemaVersion")
    if schema == "1.0":
        if not isinstance(report.get("summary"), dict):
            raise ValueError("Schema 1.0 report is missing summary")
        completeness = report.get("analysisCompleteness", {})
        if not isinstance(completeness, dict):
            raise ValueError("Invalid analysisCompleteness")
        status = completeness.get("status", "UNKNOWN")
        if status not in {"NO_SOURCE", "UNAVAILABLE", "INCOMPLETE", "LIMITED", "SOURCE_ANALYZED", "UNKNOWN"}:
            raise ValueError("Unknown Java completeness status: " + str(status))
        findings = report.get("findings", [])
        if not isinstance(findings, list):
            raise ValueError("Invalid findings array")
        return {"schemaVersion": schema, "status": status,
                "findingCount": len(findings), "completeness": completeness,
                "scope": "Supplied Java sources; separate module reports do not establish cross-module flow"}
    if schema != "2.0":
        raise ValueError("Unsupported report schema: " + str(schema))
    statuses = {"complete", "partial", "failed"}
    if report.get("status") not in statuses or not isinstance(report.get("projects"), list):
        raise ValueError("Invalid multi-language status/projects")
    if not isinstance(report.get("diagnostics"), list) or not isinstance(report.get("preview"), bool):
        raise ValueError("Invalid multi-language diagnostics/preview")
    projects = []
    for project in report["projects"]:
        if not isinstance(project, dict) or project.get("status") not in statuses:
            raise ValueError("Invalid project status")
        if not isinstance(project.get("languages"), list) or not project["languages"]:
            raise ValueError("Missing project languages")
        for key, expected in (("findings", list), ("coverage", dict), ("diagnostics", list), ("capabilities", dict)):
            if not isinstance(project.get(key), expected):
                raise ValueError("Invalid project " + key)
        for language in project["languages"]:
            coverage = project["coverage"].get(language)
            if not isinstance(coverage, dict):
                raise ValueError("Missing coverage for " + str(language))
            for key in ("discoveredFiles", "analyzedFiles", "failedFiles", "unsupportedFiles", "unresolvedSymbols"):
                if type(coverage.get(key)) is not int or coverage[key] < 0:
                    raise ValueError("Invalid coverage counter: " + key)
            if coverage["discoveredFiles"] < sum(coverage[k] for k in ("analyzedFiles", "failedFiles", "unsupportedFiles")):
                raise ValueError("Coverage exceeds discovered file count")
            if type(coverage.get("budgetExhausted")) is not bool or not isinstance(coverage.get("missingDependencies"), list):
                raise ValueError("Invalid coverage budget/dependencies")
            if project["status"] == "complete" and (
                coverage["failedFiles"] or coverage["unsupportedFiles"] or coverage["budgetExhausted"]
                or coverage["missingDependencies"] or coverage["discoveredFiles"] != coverage["analyzedFiles"]
            ):
                raise ValueError("Complete project reports coverage gaps for " + str(language))
        projects.append({"project": project.get("project"), "languages": project["languages"],
                         "status": project["status"], "findingCount": len(project["findings"]),
                         "coverage": project["coverage"], "diagnostics": project["diagnostics"],
                         "capabilities": project["capabilities"]})
    if report["status"] == "complete" and any(p["status"] != "complete" for p in projects):
        raise ValueError("Complete report contains partial/failed projects")
    return {"schemaVersion": schema, "status": report["status"], "preview": report["preview"],
            "projects": projects, "diagnostics": report["diagnostics"],
            "scope": "Independent projects; no cross-language call graph or preview stability grade"}


def execute(command, project):
    # stdout belongs to the machine-readable skill summary; tool logs go to stderr.
    return subprocess.run(command, cwd=project, stdout=sys.stderr, stderr=sys.stderr, check=False).returncode


def require_java():
    java = shutil.which("java")
    if not java:
        raise ValueError("Java 21+ is required to run NullGuard")
    version = subprocess.run([java, "-version"], capture_output=True, text=True, check=False)
    match = re.search(r'version\s+"(\d+)', version.stderr + version.stdout)
    if version.returncode or not match or int(match[1]) < 21:
        raise ValueError("Java 21+ is required; check JAVA_HOME and PATH")
    return java


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--build-tool", choices=["auto", "maven", "gradle", "cli"], default="auto")
    parser.add_argument("--languages", default="auto")
    parser.add_argument("--cli-jar", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--classpath")
    args = parser.parse_args(argv)
    try:
        project = args.project.resolve()
        if not project.is_dir():
            raise ValueError("Project directory does not exist: " + str(project))
        languages = parse_languages(args.languages)
        tool = ("cli" if args.cli_jar else detect_build(project)) if args.build_tool == "auto" else args.build_tool
        # An explicit CLI or non-Java request must not silently audit only the Java subtree.
        if args.cli_jar or args.languages != "auto" and languages != ["java"]:
            if args.build_tool in {"maven", "gradle"}:
                raise ValueError("Maven/Gradle analyze Java only; use --build-tool cli for language packs")
            tool = "cli"
        if args.languages == "auto" and not args.cli_jar:
            # Auto means the full supported language set, so native Java requires an explicit choice.
            if tool in {"maven", "gradle"}:
                raise ValueError("For Java-only native analysis use --languages=java; for mixed repositories supply --cli-jar")
        if tool != "cli" and (args.output or args.classpath):
            raise ValueError("--output and --classpath apply to CLI runs; native tools use their project configuration")
        java = require_java()
        before = snapshot_reports(project)
        with tempfile.TemporaryDirectory(prefix="nullguard-skill-") as temporary:
            if tool == "cli":
                if not args.cli_jar or not args.cli_jar.resolve().is_file():
                    raise ValueError("Supply --cli-jar with a verified executable NullGuard JAR; see references/setup.md")
                output = args.output.resolve() if args.output else project / "nullguard-out" / Path(temporary).name
                if output.exists() and any(output.iterdir()):
                    raise ValueError("CLI output must be empty to prevent stale reports: " + str(output))
                command = [java, "-jar", str(args.cli_jar.resolve()), str(project), "--output=" + str(output)]
                if languages != ["java"]:
                    command.append("--languages=" + ",".join(languages))
                if args.classpath:
                    command.append("--classpath=" + args.classpath)
                code = execute(command, project)
                reports = [output / REPORT] if (output / REPORT).is_file() else []
            elif tool == "maven":
                command = [choose_tool(project, tool), "--batch-mode",
                           "com.arinits.nullguard:nullguard-maven-plugin:" + VERSION + ":analyze",
                           "-Dnullguard.failBuild=false"]
                code = execute(command, project)
                reports = fresh_reports(project, before)
            else:
                # Init script changes only this invocation, including projects with enforcement configured.
                init = Path(temporary) / "report-only.gradle"
                init.write_text("""gradle.projectsEvaluated {
  allprojects { p ->
    if (p.plugins.hasPlugin('com.arinits.nullguard')) {
      p.extensions.getByName('nullguard').failBuild.set(false)
      p.extensions.getByName('nullguard').toolVersion.set('2.0.1')
      p.tasks.withType(JavaExec).configureEach { t ->
        if (t.name == 'nullguard') {
          t.failBuild.set(false)
          t.javaLauncher.set(p.extensions.getByType(org.gradle.jvm.toolchain.JavaToolchainService).launcherFor {
            languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21))
          })
        }
      }
    }
  }
}
""", encoding="utf-8")
                command = [choose_tool(project, tool), "--console=plain", "--rerun-tasks", "--init-script", str(init), "nullguard"]
                code = execute(command, project)
                reports = fresh_reports(project, before)
        summaries = []
        for path in reports:
            with path.open(encoding="utf-8") as handle:
                summary = summarize_report(json.load(handle))
            summaries.append({"path": str(path), **summary})
        print(json.dumps({"buildTool": tool, "engineExitCode": code, "reports": summaries}, indent=2, allow_nan=False))
        if code:
            return code if code > 0 else 3
        if not reports:
            raise ValueError("No fresh reports generated (check source roots and Gradle plugin setup); no clean result established")
        if any(r["status"] in {"partial", "failed", "INCOMPLETE", "UNAVAILABLE", "NO_SOURCE", "UNKNOWN"}
               or r.get("schemaVersion") == "2.0" and not r["projects"] for r in summaries):
            return 3
        return 0
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print("NullGuard skill: " + str(error), file=sys.stderr)
        return 3


if __name__ == "__main__":
    sys.exit(main())
