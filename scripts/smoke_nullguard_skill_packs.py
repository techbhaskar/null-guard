"""Opt-in five-language qualification using an explicit CLI and signed local archives.

Usage: python scripts/smoke_nullguard_skill_packs.py --cli-jar PATH --pack-archives PATH
Requires Java 21, Python 3.11+, Node 22+, .NET 10 and GPG/GPGV. No source checkout
or release downloads are assumed. The fixture's dotnet restore is explicit test
preparation; the scanner itself never restores application dependencies.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "skills/nullguard-audit/scripts/run_nullguard.py"


def run(command, env, expected=0):
    result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=300)
    if result.returncode != expected:
        raise AssertionError(f"Expected {expected}, got {result.returncode}: {command}\n{result.stdout}\n{result.stderr}")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli-jar", type=Path, required=True)
    parser.add_argument("--pack-archives", type=Path, required=True)
    args = parser.parse_args()
    jar, archives = args.cli_jar.resolve(), args.pack_archives.resolve()
    family = {"Darwin": "macos", "Linux": "linux", "Windows": "windows"}[platform.system()]
    arch = {"arm64": "arm64", "aarch64": "arm64", "x86_64": "x64", "AMD64": "x64"}[platform.machine()]
    with tempfile.TemporaryDirectory(prefix="nullguard-skill-packs-") as temporary:
        work = Path(temporary)
        env = dict(os.environ, NULLGUARD_PACK_HOME=str(work / "packs"))
        for pack in ("python", "ecmascript", "csharp"):
            archive = archives / f"nullguard-pack-{pack}-{family}-{arch}.zip"
            run(["java", "-jar", str(jar), "packs", "install", pack, "--from=" + str(archive)], env)
        fixture = work / "mixed project"
        files = {
            "java/Example.java": "class Example { int unsafe(String value) { return value.length(); } }\n",
            "python/pyproject.toml": '[project]\nname="skill-fixture"\nversion="0.1.0"\nrequires-python=">=3.11"\n',
            "python/app.py": 'def unsafe(name: str | None):\n    return name.count("a")\n',
            "web/package.json": '{"name":"skill-fixture","version":"0.1.0","private":true}',
            "web/tsconfig.json": '{"compilerOptions":{"strict":true,"target":"ES2022","jsx":"preserve","allowJs":true},"include":["*.ts","*.jsx"]}',
            "web/service.ts": "export function unsafe(name: string | null) { return name.length; }\n",
            "web/view.jsx": "export function unsafe() { const model = null; return <div>{model.name}</div>; }\n",
            "csharp/Example.csproj": '<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><TargetFramework>net10.0</TargetFramework><Nullable>enable</Nullable></PropertyGroup></Project>',
            "csharp/Example.cs": "public static class Example { public static int Unsafe(string? name) => name.Length; }\n",
        }
        for name, content in files.items():
            path = fixture / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        run(["dotnet", "restore", str(fixture / "csharp/Example.csproj")], env)

        def scan(project, languages, name, expected=0):
            result = run([sys.executable, str(RUNNER), "--project", str(project),
                          "--build-tool", "cli", "--cli-jar", str(jar),
                          "--languages", languages, "--output", str(work / name)], env, expected)
            return json.loads(result.stdout)

        for language, folder in (("java", "java"), ("python", "python"), ("javascript", "web"),
                                 ("typescript", "web"), ("csharp", "csharp")):
            result = scan(fixture / folder, language, "single-" + language)
            report = json.loads(Path(result["reports"][0]["path"]).read_text())
            findings = report.get("findings", []) if language == "java" else [
                finding for project in report["projects"] for finding in project["findings"]
                if finding["language"] == language]
            assert findings, f"Seeded finding missing for {language}"
            print(language + ": seeded finding and fresh report passed", flush=True)

        mixed = scan(fixture, "auto", "mixed")
        projects = mixed["reports"][0]["projects"]
        assert {language for p in projects for language in p["languages"]} == {
            "java", "python", "javascript", "typescript", "csharp"}
        assert all(p["status"] == "complete" for p in projects)
        assert all(not p["capabilities"].get("stabilityScore") for p in projects if p["languages"] != ["java"])
        print("Mixed five-language scan: independent projects and capability boundaries passed", flush=True)
        run(["java", "-jar", str(jar), "packs", "remove", "python"], env)
        failed = scan(fixture, "auto", "missing-pack", expected=3)
        assert failed["engineExitCode"] == 3 and failed["reports"][0]["status"] != "complete"
        print("Missing pack: nonzero result retained partial reports", flush=True)


if __name__ == "__main__":
    main()
