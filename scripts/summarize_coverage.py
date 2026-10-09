"""Summarize JaCoCo XML instruction/branch coverage; no arbitrary threshold claim."""
from pathlib import Path
import xml.etree.ElementTree as ET
import argparse

parser = argparse.ArgumentParser()
parser.add_argument("--min-instruction", type=float)
parser.add_argument("--min-branch", type=float)
args = parser.parse_args()

totals = {"INSTRUCTION": [0, 0], "BRANCH": [0, 0]}
aggregate = Path("nullguard-coverage/target/site/jacoco-aggregate/jacoco.xml")
if (args.min_instruction is not None or args.min_branch is not None) and not aggregate.exists():
    raise SystemExit("Aggregate report is required for coverage gating; run mvn verify first")
if aggregate.exists():
    reports = [(group.attrib["name"], group) for group in ET.parse(aggregate).getroot().findall("group")]
else:
    reports = [(report.parts[0], ET.parse(report).getroot()) for report in sorted(Path(".").glob("*/target/site/jacoco/jacoco.xml"))]
for name, node in reports:
    counters = {item.attrib["type"]: item.attrib for item in node.findall("counter")}
    values = []
    for kind, total in totals.items():
        counter = counters.get(kind, {"missed": "0", "covered": "0"})
        missed, covered = int(counter["missed"]), int(counter["covered"])
        total[0] += missed
        total[1] += covered
        denominator = missed + covered
        values.append(f"{kind.lower()}: {100 * covered / denominator:.1f}%" if denominator else f"{kind.lower()}: n/a")
    print(f"{name}: {', '.join(values)}")
for kind, (missed, covered) in totals.items():
    denominator = missed + covered
    print(f"Total {kind.lower()}: {100 * covered / denominator:.1f}%" if denominator else f"Total {kind.lower()}: n/a")
    minimum = args.min_instruction if kind == "INSTRUCTION" else args.min_branch
    if minimum is not None and (not denominator or 100 * covered / denominator < minimum):
        raise SystemExit(f"{kind.lower()} coverage is below the required {minimum}%")
