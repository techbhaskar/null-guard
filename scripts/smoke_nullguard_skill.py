"""Opt-in real Maven/Gradle qualification of the distributable NullGuard skill.

Uses published NullGuard 2.0.1 in temporary consumer projects. Requires network
access to Maven Central, Java 21+, Maven, and optionally Gradle 8.14+.
Run: python3 scripts/smoke_nullguard_skill.py --gradle /path/to/gradle
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "skills/nullguard-audit/scripts/run_nullguard.py"
VERSION = "2.0.1"
UNSAFE = """package sample;
public final class NullExample {
    private static String nullable() { return null; }
    public static int length() {
        String value = nullable();
        value.length();
        value.length();
        value.length();
        return value.length();
    }
}
@interface GetMapping { String value(); }
class Controller {
    @GetMapping("/one") public int one() { return NullExample.length(); }
    @GetMapping("/two") public int two() { return NullExample.length(); }
    @GetMapping("/three") public int three() { return NullExample.length(); }
    @GetMapping("/four") public int four() { return NullExample.length(); }
    @GetMapping("/five") public int five() { return NullExample.length(); }
}
"""
SAFE = UNSAFE.replace("String value = nullable();", "String value = nullable();\n        if (value == null) return 0;")
TEST = """package sample;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class NullExampleTest {
    @Test void missingValueHasZeroLength() { assertEquals(0, NullExample.length()); }
}
"""
POM = """<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>sample</groupId><artifactId>skill-smoke</artifactId><version>1</version>
  <properties><maven.compiler.release>21</maven.compiler.release></properties>
  <dependencies><dependency>
    <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
    <version>5.11.4</version><scope>test</scope>
  </dependency></dependencies>
  <build><plugins>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>
    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version></plugin>
  </plugins></build>
</project>
"""
GRADLE_GROOVY = """plugins {
    id 'java'
    id 'com.arinits.nullguard' version '2.0.1'
}
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies { testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4' }
test { useJUnitPlatform() }
nullguard { failBuild.set(true); failThreshold.set('LOW') }
"""
GRADLE_KOTLIN = """plugins {
    java
    id("com.arinits.nullguard") version "2.0.1"
}
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies { testImplementation("org.junit.jupiter:junit-jupiter:5.11.4") }
tasks.test { useJUnitPlatform() }
nullguard { failBuild.set(true); failThreshold.set("LOW") }
"""


def execute(command, cwd):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True, timeout=600)
    if result.returncode:
        raise RuntimeError("Command failed: " + shlex.join(map(str, command)) + "\n"
                           + result.stdout[-12000:] + result.stderr[-12000:])
    return result


def wrapper(project, executable):
    """Expose an explicitly selected Gradle through the runner's wrapper path."""
    if os.name == "nt":
        path = project / "gradlew.bat"
        path.write_text('@echo off\n"' + executable + '" %*\n')
    else:
        path = project / "gradlew"
        path.write_text("#!/bin/sh\nexec " + shlex.quote(executable) + ' "$@"\n')
        path.chmod(0o755)


def audit(project, build):
    result = execute([sys.executable, str(RUNNER), "--project", str(project),
                      "--build-tool", build, "--languages", "java"], project)
    payload = json.loads(result.stdout)
    if not payload["reports"]:
        raise AssertionError("No report produced")
    reports = [json.loads(Path(item["path"]).read_text()) for item in payload["reports"]]
    if any(report.get("schemaVersion") != "1.0" for report in reports):
        raise AssertionError("Native Java must retain schema 1.0")
    return reports


def qualify(project, build, gradle=None, kotlin=False):
    print("Qualifying " + project.name, file=sys.stderr, flush=True)
    source = project / "src/main/java/sample/NullExample.java"
    source.parent.mkdir(parents=True)
    source.write_text(UNSAFE)
    test = project / "src/test/java/sample/NullExampleTest.java"
    test.parent.mkdir(parents=True)
    test.write_text(TEST)
    if build == "maven":
        (project / "pom.xml").write_text(POM)
        test_command = [shutil.which("mvn"), "--batch-mode", "test"]
    else:
        wrapper(project, gradle)
        extension = ".kts" if kotlin else ""
        (project / ("settings.gradle" + extension)).write_text(
            "pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }\n")
        (project / ("build.gradle" + extension)).write_text(GRADLE_KOTLIN if kotlin else GRADLE_GROOVY)
        test_command = [gradle, "--console=plain", "test"]
    before = audit(project, build)
    findings_before = sum(len(report.get("findings", [])) for report in before)
    if not findings_before:
        raise AssertionError("Seeded unsafe source produced no finding")
    if not any(report.get("hotspots") for report in before):
        raise AssertionError("Seeded API paths produced no hotspot for gate verification")
    gate_command = ([shutil.which("mvn"), "--batch-mode",
                     "com.arinits.nullguard:nullguard-maven-plugin:" + VERSION + ":analyze",
                     "-Dnullguard.failBuild=true", "-Dnullguard.failThreshold=LOW"]
                    if build == "maven" else [gradle, "--console=plain", "nullguard"])
    previous_reports = {path: path.stat().st_mtime_ns
                        for path in project.rglob("nullguard-report-latest.json")}
    gate = subprocess.run(gate_command, cwd=project, capture_output=True, text=True, timeout=600)
    if not gate.returncode:
        raise AssertionError("Explicit native hotspot gate unexpectedly passed")
    if not any(marker in gate.stdout + gate.stderr for marker in
               ("NullGuard: build failed", "[NullGuard] BUILD FAILED")):
        raise AssertionError("Native failure did not identify a NullGuard policy breach:\n"
                             + gate.stdout[-6000:] + gate.stderr[-6000:])
    # Reports must survive intentional policy failures, not just successful analyses.
    if not any(path.stat().st_mtime_ns != previous_reports.get(path)
               for path in project.rglob("nullguard-report-latest.json")):
        raise AssertionError("Native policy failure did not retain fresh report artifacts")
    source.write_text(SAFE)
    execute(test_command, project)
    after = audit(project, build)
    findings_after = sum(len(report.get("findings", [])) for report in after)
    if findings_after >= findings_before:
        raise AssertionError("Guarded source did not reduce findings: "
                             + str(findings_before) + " -> " + str(findings_after))
    return {"fixture": project.name, "engineVersion": VERSION,
            "findingCountBefore": findings_before, "findingCountAfter": findings_after,
            "regressionTests": "passed", "freshRerun": "passed", "nativePolicyGate": "failed as expected"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", help="Gradle executable; defaults to Gradle on PATH")
    parser.add_argument("--require-gradle", action="store_true", help="Fail rather than skip absent Gradle")
    args = parser.parse_args()
    gradle = args.gradle or shutil.which("gradle")
    if not shutil.which("mvn"):
        parser.error("Maven is required")
    if args.require_gradle and not gradle:
        parser.error("Gradle is required; provide --gradle")
    if gradle:
        gradle = str(Path(gradle).resolve())
        if not Path(gradle).is_file():
            parser.error("Gradle executable does not exist")
    results = []
    with tempfile.TemporaryDirectory(prefix="nullguard skill native ") as directory:
        root = Path(directory)
        for name, build, kotlin in (("maven", "maven", False),
                                    ("gradle-groovy", "gradle", False),
                                    ("gradle-kotlin", "gradle", True)):
            if build == "gradle" and not gradle:
                results.append({"fixture": name, "status": "not run", "reason": "Gradle unavailable"})
                continue
            project = root / name
            project.mkdir()
            results.append(qualify(project, build, gradle, kotlin))
    print(json.dumps({"nativeSkillQualification": results}, indent=2))


if __name__ == "__main__":
    main()
