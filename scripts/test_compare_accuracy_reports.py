import unittest
from compare_accuracy_reports import compare


class CompareAccuracyReportsTest(unittest.TestCase):
    def finding(self, path="A.java", line=1, message="risk"):
        return dict(ruleId="NG006", methodId="A#run()", message=message,
                    location=dict(path=path, startLine=line))

    def test_duplicate_count_reduction_is_not_hidden(self):
        finding = self.finding()
        result = compare(dict(findings=[finding, finding]), dict(findings=[finding]))
        self.assertEqual(1, result["retained"])
        self.assertEqual(1, result["removed"][0]["count"])
        self.assertEqual([], result["added"])

    def test_changed_path_line_or_message_is_explicit(self):
        result = compare(dict(findings=[self.finding()]), dict(findings=[self.finding("B.java", 2, "other risk")]))
        self.assertEqual(1, len(result["removed"]))
        self.assertEqual("B.java", result["added"][0]["path"])
        self.assertEqual(2, result["added"][0]["line"])

    def test_missing_locations_and_completeness_do_not_imply_clean(self):
        finding = self.finding()
        finding.pop("location")
        result = compare(dict(findings=[finding]), dict(findings=[finding]))
        self.assertIsNone(result["candidate_completeness"])
        self.assertEqual([], result["removed"])

    def test_severity_change_is_visible(self):
        old, new = self.finding(), self.finding()
        old["level"], new["level"] = "warning", "error"
        result = compare(dict(findings=[old]), dict(findings=[new]))
        self.assertEqual("error", result["added"][0]["level"])
        self.assertEqual("warning", result["removed"][0]["level"])


if __name__ == "__main__":
    unittest.main()
