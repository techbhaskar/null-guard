import csv
import json
import tempfile
import unittest
from pathlib import Path

from accuracy_review import FIELDS, prepare, read_review, summarize


class AccuracyReviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        findings = [dict(ruleId="NG001", methodId="Example#run()", message=f"risk {i}",
                         location=dict(path="Example.java", startLine=i + 1)) for i in range(8)]
        findings.append(dict(ruleId="NG101", message="recommendation"))
        (self.root / "report.json").write_text(json.dumps(dict(findings=findings)), encoding="utf-8")
        self.manifest = self.root / "runs.json"
        self.manifest.write_text(json.dumps(dict(runs=[dict(project="library", repository="https://example.org/repo",
            commit="abc", analyzer_revision="def", command="java -jar analyzer.jar src", classpath="none",
            report="report.json")])), encoding="utf-8")

    def sheet(self, packet, name, labels, reviewer):
        path = self.root / name
        with path.open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=FIELDS)
            writer.writeheader()
            for finding, label in zip(packet["findings"], labels):
                writer.writerow({**finding, "label": label, "reviewer": reviewer, "evidence": "Example.java:1; checked guard"})
        return read_review(path, packet)

    def test_sampling_is_reproducible_and_recommendations_excluded(self):
        a = prepare(self.manifest, self.root / "a", 4, 42)
        b = prepare(self.manifest, self.root / "b", 4, 42)
        self.assertEqual(a, b)
        self.assertEqual(8, a["eligible"])
        self.assertEqual("Example.java", a["findings"][0]["path"])
        self.assertEqual(64, len(a["runs"][0]["report_sha256"]))

    def test_disagreements_and_missing_judgments_remain_visible(self):
        packet = prepare(self.manifest, self.root / "packet", 4, 42)
        a = self.sheet(packet, "a.csv", ["true_positive", "false_positive", "true_positive", "unreviewed"], "alice")
        b = self.sheet(packet, "b.csv", ["true_positive", "false_positive", "false_positive", "true_positive"], "bob")
        result = summarize(packet, a, b)
        self.assertEqual(0.5, result["precision_among_decisive_agreements"])
        self.assertEqual(1, result["counts"]["disagreement"])
        self.assertEqual(1, result["counts"]["unreviewed"])
        self.assertEqual(0.25, result["agreed_false_positive_share_of_selected"])

    def test_single_reviewer_cannot_count_as_two(self):
        packet = prepare(self.manifest, self.root / "packet", 1, 42)
        a = self.sheet(packet, "a.csv", ["true_positive"], "Alice")
        b = self.sheet(packet, "b.csv", ["true_positive"], "alice")
        with self.assertRaisesRegex(ValueError, "distinct reviewers"):
            summarize(packet, a, b)

    def test_missing_rows_and_unsupported_labels_rejected(self):
        packet = prepare(self.manifest, self.root / "packet", 2, 42)
        with self.assertRaisesRegex(ValueError, "every selected"):
            self.sheet(packet, "a.csv", ["true_positive"], "alice")
        with self.assertRaisesRegex(ValueError, "Unknown review label"):
            self.sheet(packet, "b.csv", ["potential_true_positive", "unreviewed"], "bob")

    def test_unreviewed_packet_has_no_accuracy_claim(self):
        packet = prepare(self.manifest, self.root / "packet", 0, 42)
        a = read_review(self.root / "packet/review-a.csv", packet)
        b = read_review(self.root / "packet/review-b.csv", packet)
        self.assertIsNone(summarize(packet, a, b)["precision_among_decisive_agreements"])


if __name__ == "__main__":
    unittest.main()
