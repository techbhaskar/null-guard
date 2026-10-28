"""Validate schema 2.0 reports using the checked-in protocol schemas."""
import argparse
import json
from pathlib import Path
from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT=Path(__file__).resolve().parents[1]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('report',type=Path);a=p.parse_args()
 directory=ROOT/'docs/multilang';schema=json.loads((directory/'report-v2.schema.json').read_text());schema['$id']='https://nullguard.local/schema/report-v2.schema.json'
 protocol=json.loads((directory/'protocol-v1.schema.json').read_text());registry=Registry().with_resource('https://nullguard.local/schema/protocol-v1.schema.json',Resource.from_contents(protocol))
 report=json.loads(a.report.read_text());Draft202012Validator(schema,registry=registry).validate(report)
 ids=set()
 for project in report['projects']:
  if project['project'] in ids:raise ValueError('Duplicate project id')
  ids.add(project['project'])
  for language in project['languages']:
   c=project['coverage'][language]
   if c['discoveredFiles']!=sum(c[k] for k in ['analyzedFiles','failedFiles','unsupportedFiles']):raise ValueError('Coverage omits files')
 print('Multi-language schema and coverage validation passed:',a.report)

if __name__=='__main__':main()
