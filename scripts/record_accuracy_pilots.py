"""Record provenance for the two source-only pilots; run after their CLI analyses."""
import hashlib
import json
from pathlib import Path
import subprocess
from collections import Counter


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def main():
    root = Path(__file__).resolve().parent.parent
    jar = root / "nullguard-cli/target/nullguard-cli-1.1.2-jar-with-dependencies.jar"
    jar_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = []
    summaries = []
    for name, source, repository in (
        ("gson", "gson/src/main/java", "https://github.com/google/gson"),
        ("commons-lang", "src/main/java", "https://github.com/apache/commons-lang"),
    ):
        checkout = root / "target/accuracy-corpus" / name
        report = root / "target/accuracy" / name / "nullguard-report-latest.json"
        command = (f"java -Xmx2g -jar {jar.relative_to(root).as_posix()} "
                   f"target/accuracy-corpus/{name}/{source} --output=target/accuracy/{name}")
        run = dict(project=name, repository=repository,
                   commit=git("-C", str(checkout), "rev-parse", "HEAD"),
                   source_dirty=bool(git("-C", str(checkout), "status", "--porcelain")),
                   analyzer_revision=git("-C", str(root), "rev-parse", "HEAD"),
                   analyzer_working_tree_status=git("-C", str(root), "status", "--porcelain"),
                   analyzer_jar_sha256=jar_hash, command=command,
                   java_version=subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.strip(),
                   classpath="none; source-only pilot, dependency contracts may be unresolved",
                   report=f"{name}/nullguard-report-latest.json")
        log = root / "target" / f"accuracy-{name}.log"
        log_text = log.read_text(encoding="utf-8", errors="replace")
        if not report.is_file():
            summaries.append(dict(project=name, commit=run["commit"], status="failed_no_report",
                                  provenance=run, log=str(log.relative_to(root)),
                                  log_sha256=hashlib.sha256(log.read_bytes()).hexdigest(),
                                  failure="StackOverflowError" if "StackOverflowError" in log_text else "inspect log",
                                  accuracy_status="not measurable; failed analyses are not clean results"))
            continue
        data = json.loads(report.read_text(encoding="utf-8"))
        runs.append(run)
        summaries.append(dict(project=name, commit=run["commit"],
                              status="completed_unreviewed",
                              report_sha256=hashlib.sha256(report.read_bytes()).hexdigest(),
                              findings_by_rule=dict(Counter(f["ruleId"] for f in data["findings"])),
                              analysisCompleteness=data.get("analysisCompleteness"),
                              accuracy_status="unreviewed; no precision or recall measured"))
    destination = root / "target/accuracy"
    (destination / "runs.json").write_text(json.dumps(dict(runs=runs), indent=2) + "\n", encoding="utf-8")
    (destination / "pilot-summary.json").write_text(json.dumps(summaries, indent=2) + "\n", encoding="utf-8")
    print(json.dumps([{k: v for k, v in row.items() if k != "analysisCompleteness"} for row in summaries], indent=2))


if __name__ == "__main__":
    main()
