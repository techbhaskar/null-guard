"""Export review candidates or validate evidence-based preview qualification.

No automatic true-positive labels or precision claims are generated.
"""
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

LANGUAGES={'python','javascript','typescript','csharp','go','rust'}
LABELS={'true-positive','false-positive','uncertain'}

def validate(rows,counts):
 errors=[];group=defaultdict(list);seen=set()
 for row in rows:
  key=(row.get('application'),row.get('commit'),row.get('ruleId'),row.get('path'),row.get('line'),row.get('column',0),row.get('message',''))
  if key in seen:errors.append('Duplicate review: '+str(key))
  seen.add(key);group[row.get('language')].append(row)
  if row.get('language') not in LANGUAGES:errors.append('Unsupported review language: '+str(row.get('language')))
  if not re.fullmatch('[0-9a-f]{40}',row.get('commit','')):errors.append('Unpinned source commit: '+str(key))
  if not isinstance(row.get('line'),int) or row['line']<1:errors.append('Invalid source location: '+str(key))
  if row.get('label') not in LABELS or not row.get('reviewer') or not row.get('reasoning') or not row.get('sourceUrl'):errors.append('Incomplete review evidence: '+str(key))
 for lang in sorted(LANGUAGES):
  if lang not in counts or not isinstance(counts[lang],int) or isinstance(counts[lang],bool) or counts[lang]<0:errors.append('Missing/invalid observed count: '+lang)
  selected=group[lang];observed=counts.get(lang);required=min(50,observed if isinstance(observed,int) and not isinstance(observed,bool) and observed>=0 else 50)
  if len(selected)<required:errors.append(f'{lang}: {len(selected)}/{required} required reviews')
  if len({r.get('application') for r in selected})<2:errors.append(lang+': reviews from two pinned applications required')
 rules=defaultdict(list)
 for row in rows:
  if row.get('confidence')=='high':rules[row['ruleId']].append(row)
 for rule,sample in rules.items():
  precision=sum(r.get('label')=='true-positive' for r in sample)/len(sample)
  if precision<.9:errors.append(f'{rule}: reviewed high-confidence precision {precision:.1%}; demote to experimental')
 return errors

def main():
 p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
 export=sub.add_parser('export');export.add_argument('report',type=Path);export.add_argument('--application',required=True);export.add_argument('--commit',required=True);export.add_argument('--repository',required=True);export.add_argument('--output',type=Path,required=True)
 check=sub.add_parser('validate');check.add_argument('reviews',type=Path);check.add_argument('--counts',type=Path,required=True)
 a=p.parse_args()
 if a.command=='validate':
  errors=validate(json.loads(a.reviews.read_text()),json.loads(a.counts.read_text()));print('\n'.join(errors) if errors else 'Reviewed sample meets the specified gates; no general precision or recall claim.');return int(bool(errors))
 report=json.loads(a.report.read_text());rows=[]
 for project in report['projects']:
  for finding in project['findings']:
   loc=finding['location'];rows.append(dict(application=a.application,commit=a.commit,language=finding['language'],ruleId=finding['ruleId'],confidence=finding['confidence'],path=loc['path'],line=loc['startLine'],column=loc['startColumn'],message=finding['message'],evidence=finding.get('evidence',[]),sourceUrl=a.repository.rstrip('/')+'/blob/'+a.commit+'/'+loc['path']+'#L'+str(loc['startLine']),label=None,reviewer=None,reasoning=None))
 a.output.write_text(json.dumps(rows,indent=2)+'\n');print(f'Exported {len(rows)} unreviewed findings')
 return 0

if __name__=='__main__':raise SystemExit(main())
