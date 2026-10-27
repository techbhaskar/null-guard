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
