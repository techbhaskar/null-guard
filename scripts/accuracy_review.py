"""Prepare reproducible accuracy reviews and summarize two independent review sheets.

No labels are inferred. Historical/agent-assisted labels are not imported.
"""
import argparse
import csv
import hashlib
import json
import random
from collections import Counter
from pathlib import Path

RULES = {"NG001", "NG002", "NG003", "NG004", "NG006"}
LABELS = {"", "true_positive", "false_positive", "context_dependent", "unreviewed"}
FIELDS = ["id", "project", "rule", "method", "path", "line", "message",
          "reviewer", "label", "evidence"]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def prepare(manifest_path, output, size, seed):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    findings, runs, names = [], [], set()
    for run in manifest["runs"]:
        for field in ("project", "repository", "commit", "analyzer_revision", "command", "classpath"):
            if not run.get(field):
                raise ValueError(f"Missing provenance: {field}")
        if run["project"] in names:
            raise ValueError("Duplicate project")
        names.add(run["project"])
        report_path = (manifest_path.parent / run["report"]).resolve()
        raw = report_path.read_bytes()
        report = json.loads(raw)
        runs.append({**run, "report_sha256": digest(raw),
                     "analysisCompleteness": report.get("analysisCompleteness", "not reported")})
        for index, finding in enumerate(report["findings"]):
            if finding["ruleId"] not in RULES:
                continue
            location = finding.get("location") or {}
            row = dict(id=digest(f'{run["project"]}:{digest(raw)}:{index}'.encode()),
                       project=run["project"], rule=finding["ruleId"],
                       method=finding.get("methodId", ""), path=location.get("path", ""),
                       line=location.get("startLine", ""), message=finding["message"],
                       reviewer="", label="unreviewed", evidence="")
            findings.append(row)
    findings.sort(key=lambda row: row["id"])
    selected = random.Random(seed).sample(findings, min(size, len(findings))) if size else findings
    output.mkdir(parents=True, exist_ok=False)
    packet = dict(schema=1, seed=seed, sampling="uniform_without_replacement" if size else "census",
                  eligible=len(findings), selected=len(selected), rules=sorted(RULES), runs=runs,
                  findings=selected)
    (output / "packet.json").write_text(json.dumps(packet, indent=2) + "\n", encoding="utf-8")
    for reviewer in ("a", "b"):
        with (output / f"review-{reviewer}.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=FIELDS)
            writer.writeheader()
            writer.writerows(selected)
    return packet


def read_review(path, packet):
    expected = {row["id"]: row for row in packet["findings"]}
    rows = {}
    with path.open(newline="", encoding="utf-8-sig") as stream:
        for row in csv.DictReader(stream):
            key = row["id"]
            if key not in expected or key in rows:
                raise ValueError("Unknown or duplicate finding ID")
            if any(row[field] != str(expected[key][field]) for field in FIELDS[:7]):
                raise ValueError("Finding context changed")
            if row["label"] not in LABELS:
                raise ValueError("Unknown review label")
            if row["label"] not in {"", "unreviewed"} and not (row["reviewer"].strip() and row["evidence"].strip()):
                raise ValueError("Completed judgments require reviewer and evidence")
            rows[key] = row
    if set(rows) != set(expected):
        raise ValueError("Review must retain every selected finding")
    return rows


def summarize(packet, first, second):
    counts = Counter()
    groups = {}
    for finding in packet["findings"]:
        a, b = first[finding["id"]], second[finding["id"]]
        if a["label"] in {"", "unreviewed"} or b["label"] in {"", "unreviewed"}:
            status = "unreviewed"
        elif a["reviewer"].strip().casefold() == b["reviewer"].strip().casefold():
            raise ValueError("Two distinct reviewers are required")
        elif a["label"] != b["label"]:
            status = "disagreement"
        else:
            status = a["label"]
        counts[status] += 1
        for group in ("project:" + finding["project"], "rule:" + finding["rule"]):
            groups.setdefault(group, Counter())[status] += 1
    tp, fp = counts["true_positive"], counts["false_positive"]
    # Deliberately no confidence interval: unresolved judgments may be nonrandom.
    return dict(eligible=packet["eligible"], selected=packet["selected"],
                sampling=packet["sampling"], counts=dict(counts), by_group=groups,
                decisive_agreed=tp + fp,
                precision_among_decisive_agreements=tp / (tp + fp) if tp + fp else None,
                false_discovery_proportion_among_decisive_agreements=fp / (tp + fp) if tp + fp else None,
                agreed_false_positive_share_of_selected=fp / packet["selected"] if packet["selected"] else None,
                caveat="Descriptive reviewed-sample results only. Reviewer independence requires human attestation. "
                       "No recall or false-positive rate over safe code is measured. "
                       "Unresolved findings prevent a population precision claim.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("manifest", type=Path)
    prep.add_argument("output", type=Path)
    prep.add_argument("--size", type=int, default=0, help="0 means full census")
    prep.add_argument("--seed", type=int, default=20261022)
    summary = sub.add_parser("summarize")
    summary.add_argument("packet", type=Path)
    summary.add_argument("first", type=Path)
    summary.add_argument("second", type=Path)
    args = parser.parse_args()
    if args.action == "prepare":
        if args.size < 0:
            parser.error("size must be nonnegative")
        packet = prepare(args.manifest, args.output, args.size, args.seed)
        print(json.dumps({"eligible": packet["eligible"], "selected": packet["selected"]}))
    else:
        packet = json.loads(args.packet.read_text(encoding="utf-8"))
        print(json.dumps(summarize(packet, read_review(args.first, packet), read_review(args.second, packet)), indent=2))


if __name__ == "__main__":
    main()
