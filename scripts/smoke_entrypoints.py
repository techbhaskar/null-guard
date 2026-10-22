"""Exercise packaged CLI exit codes and the Maven-injected goal after mvn install."""
import json
import shutil
import zipfile
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "nullguard-cli/target/ci-reports"
SOURCE = ROOT / "examples/orders-service/src/main/java"
JAR = ROOT / "nullguard-cli/target/nullguard-cli-1.1.2-jar-with-dependencies.jar"


# The standalone distribution must not reintroduce the Maven runtime.
with zipfile.ZipFile(JAR) as archive:
    assert not any(name.startswith("org/apache/maven/") for name in archive.namelist()), "CLI unexpectedly packages Maven runtime classes"


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
assert "openApiSpec" in report
assert "architectureSummary" in report
assert "cveSummary" in report
assert (OUTPUT / "nullguard-openapi.json").exists()
assert (OUTPUT / "nullguard-results.sarif").exists()
assert (OUTPUT / "nullguard-dashboard-latest.html").exists()
assert (OUTPUT / "nullguard-swagger.html").exists()
assert (OUTPUT / "swagger-ui/swagger-ui-bundle.js").stat().st_size > 100_000

configured = OUTPUT / "configured"
api_config = ROOT / "examples/openapi-config.json"
run(["java", "-jar", str(JAR), str(SOURCE), f"--output={configured}", f"--openapi-config={api_config}"], 0, "cli-openapi-config")
configured_spec = json.loads((configured / "nullguard-openapi.json").read_text(encoding="utf-8"))
assert configured_spec["info"]["title"] == "Example Service API"
assert configured_spec["security"] == [{"bearerAuth": []}]
assert configured_spec["x-nullguard-try-it-out"] is False

maven = shutil.which("mvn")
if not maven:
    raise RuntimeError("Maven is required; run mvn install first")
goal = [maven, "--batch-mode", "-f", "examples/orders-service/pom.xml", "com.arinits.nullguard:nullguard-maven-plugin:1.1.2:analyze"]
run(goal + ["-Dnullguard.failBuild=false"], 0, "maven-report-only")
run(goal + ["-Dnullguard.failBuild=true", "-Dnullguard.failThreshold=HIGH"], 1, "maven-gate")
dashboard = ROOT / "examples/orders-service/target/nullguard/nullguard-dashboard-latest.html"
openapi_spec = ROOT / "examples/orders-service/target/nullguard/nullguard-openapi.json"
assert dashboard.exists()
assert openapi_spec.exists()
assert "hotspot" in (OUTPUT / "maven-gate.log").read_text(encoding="utf-8").lower()
run(goal + ["-Dnullguard.failBuild=false", f"-Dnullguard.openapiConfig={api_config}"], 0, "maven-openapi-config")
configured_maven_spec = json.loads(openapi_spec.read_text(encoding="utf-8"))
assert configured_maven_spec["info"]["title"] == "Example Service API"
assert configured_maven_spec["components"]["securitySchemes"]["bearerAuth"]["scheme"] == "bearer"
