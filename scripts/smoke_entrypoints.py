"""Exercise packaged CLI exit codes and the Maven-injected goal after mvn install."""
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "nullguard-cli/target/ci-reports"
SOURCE = ROOT / "examples/orders-service/src/main/java"
JAR = ROOT / "nullguard-cli/target/nullguard-cli-1.0-SNAPSHOT-jar-with-dependencies.jar"


def run(command, expected, name):
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / f"{name}.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != expected:
        raise AssertionError(f"{name}: expected exit {expected}, got {result.returncode}\n{result.stdout}\n{result.stderr}")
    print(f"{name}: expected exit {expected}")


run(["java", "-jar", str(JAR), str(SOURCE), f"--output={OUTPUT}"], 0, "cli-report-only")
run(["java", "-jar", str(JAR), str(SOURCE), f"--output={OUTPUT}", "--fail-build", "--fail-threshold=HIGH"], 1, "cli-gate")
report = json.loads((OUTPUT / "nullguard-report-latest.json").read_text(encoding="utf-8"))
assert len(report["apiEndpoints"]) == 5
assert any(h["methodRef"] == "sample.Services.Risky#load()" for h in report["hotspots"])
assert (OUTPUT / "nullguard-results.sarif").exists()

maven = shutil.which("mvn")
if not maven:
    raise RuntimeError("Maven is required; run mvn install first")
goal = [maven, "--batch-mode", "-f", "examples/orders-service/pom.xml", "com.nullguard:nullguard-maven-plugin:1.0-SNAPSHOT:analyze"]
run(goal + ["-Dnullguard.failBuild=false"], 0, "maven-report-only")
run(goal + ["-Dnullguard.failBuild=true", "-Dnullguard.failThreshold=HIGH"], 1, "maven-gate")
dashboard = ROOT / "examples/orders-service/target/nullguard/nullguard-dashboard-latest.html"
assert dashboard.exists()
assert "hotspot" in (OUTPUT / "maven-gate.log").read_text(encoding="utf-8").lower()
