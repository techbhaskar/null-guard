"""Compare published and development NullGuard findings on caller-provided projects.

Build/install the candidate first. Projects are analyzed in report-only mode;
this script neither changes project source nor labels warnings automatically.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import time


def finding_key(finding):
    return (finding["ruleId"], finding["methodId"], finding["message"],
            finding.get("location", {}).get("startLine"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", action="append", required=True, metavar="NAME=PATH")
    parser.add_argument("--baseline", default="1.0.0")
    parser.add_argument("--candidate", default="1.0.1-SNAPSHOT")
    parser.add_argument("--output", type=Path, default=Path("target/evaluation"))
    args = parser.parse_args()
    output = args.output.resolve()
    summaries = []
    for entry in args.project:
        name, separator, raw_path = entry.partition("=")
        if not separator or not name or any(c not in "abcdefghijklmnopqrstuvwxyz0123456789-_" for c in name):
            parser.error("project must be NAME=PATH, with a lowercase alphanumeric name")
        project = Path(raw_path).resolve()
        if not (project / "pom.xml").is_file():
            parser.error(f"No pom.xml in {project}")
        versions = []
        for label, version in (("baseline", args.baseline), ("candidate", args.candidate)):
            destination = output / name / label
            destination.mkdir(parents=True, exist_ok=True)
            command = ["mvn", "--batch-mode", f"com.arinits.nullguard:nullguard-maven-plugin:{version}:analyze",
                       "-Dnullguard.failBuild=false"]
            started = time.time()
            with (destination / "maven.log").open("w", encoding="utf-8") as log:
                subprocess.run(command, cwd=project, stdout=log, stderr=subprocess.STDOUT,
                               check=True, timeout=300)
            reports = project / "target/nullguard"
            for filename in ("nullguard-report-latest.json", "nullguard-results.sarif", "nullguard-dashboard-latest.html"):
                report = reports / filename
                if not report.is_file() or report.stat().st_mtime < started:
                    raise RuntimeError(f"Analysis did not generate a fresh report: {report}")
                shutil.copyfile(report, destination / filename)
            versions.append(json.loads((destination / "nullguard-report-latest.json").read_text()))
        before, after = ({finding_key(f) for f in report["findings"]} for report in versions)
        summary = dict(project=name, baseline=args.baseline, candidate=args.candidate,
                       baseline_findings=len(versions[0]["findings"]), candidate_findings=len(versions[1]["findings"]),
                       removed=len(before - after), added=len(after - before))
        summaries.append(summary)
        print(json.dumps(summary), flush=True)
    (output / "comparison.json").write_text(json.dumps(summaries, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
