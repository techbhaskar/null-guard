import importlib.util
from pathlib import Path
import unittest
spec=importlib.util.spec_from_file_location('review',Path(__file__).parents[1]/'review_multilang.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class ReviewTests(unittest.TestCase):
 def test_unreviewed_findings_never_qualify(self):
  self.assertTrue(module.validate([],{}))
 def test_uncertain_high_confidence_cannot_count_as_correct(self):
  rows=[dict(application='a',commit='1',ruleId='NG-PY-001',path='a.py',line=1,language='python',confidence='high',label='uncertain',reviewer='r',reasoning='dynamic contract unknown',sourceUrl='https://example.test/source')]
  self.assertTrue(any('demote' in e for e in module.validate(rows,{'python':1})))

 def test_distinct_operations_on_same_line_are_not_duplicate_reviews(self):
  first=dict(application='a',commit='1'*40,ruleId='NG-GO-001',path='main.go',line=1,column=1,message='pointer',language='go',confidence='experimental',label='uncertain',reviewer='r',reasoning='unresolved',sourceUrl='https://example.test/source')
  second=dict(first,column=5)
  self.assertFalse(any('Duplicate' in error for error in module.validate([first,second],{'go':2})))
  self.assertTrue(any('Duplicate' in error for error in module.validate([first,first],{'go':2})))
 def test_missing_observed_counts_and_unpinned_sources_cannot_qualify(self):
  self.assertTrue(any('observed count' in error for error in module.validate([],{})))
  self.assertTrue(any('observed count' in error for error in module.validate([],{'go':'not-a-count'})))
