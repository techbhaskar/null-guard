"""Compare individual emitted findings without treating fewer warnings as accuracy."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


def finding_key(finding):
    location = finding.get("location") or {}
    return (finding["ruleId"], finding["methodId"], location.get("path", ""),
            location.get("startLine"), finding["message"], finding.get("level", finding.get("severity")),
            json.dumps(location, sort_keys=True))


def compare(before, after):
    old = Counter(finding_key(f) for f in before["findings"])
    new = Counter(finding_key(f) for f in after["findings"])
    removed, added = old - new, new - old

    def entries(delta):
        return [dict(ruleId=key[0], methodId=key[1], path=key[2], line=key[3],
                     message=key[4], level=key[5], location=json.loads(key[6]), count=count)
                for key, count in sorted(delta.items(), key=lambda item: repr(item[0]))]

    return dict(baseline_by_rule=dict(Counter(f["ruleId"] for f in before["findings"])),
                candidate_by_rule=dict(Counter(f["ruleId"] for f in after["findings"])),
                removed=entries(removed), added=entries(added),
                retained=sum((old & new).values()),
                baseline_completeness=before.get("analysisCompleteness", {}).get("status"),
                candidate_completeness=after.get("analysisCompleteness", {}).get("status"),
                note="Finding differences only; no precision or recall inferred from warning counts.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    old, new = args.baseline.read_bytes(), args.candidate.read_bytes()
    result = compare(json.loads(old), json.loads(new))
    result.update(baseline_sha256=hashlib.sha256(old).hexdigest(),
                  candidate_sha256=hashlib.sha256(new).hexdigest())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k not in {"removed", "added"}}, indent=2))
    print(json.dumps(dict(removed=sum(f["count"] for f in result["removed"]),
                         added=sum(f["count"] for f in result["added"]))))


if __name__ == "__main__":
    main()
