"""Scan pinned application checkouts and export reproducible, unreviewed samples.

Never restores dependencies or executes application code. Reports use installed
packs. Checkout HEAD must equal the recorded commit before scanning is allowed.
"""
import argparse
from collections import defaultdict, deque
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[2]
RECORDS=ROOT/'docs/multilang/qualification'

def sample(rows,limit):
    buckets=defaultdict(list)
    for row in rows:buckets[(row['application'],row['ruleId'])].append(row)
    for items in buckets.values():items.sort(key=lambda r:hashlib.sha256(json.dumps([r['path'],r['line'],r['column'],r['message']]).encode()).hexdigest())
    queues=[deque(buckets[key]) for key in sorted(buckets)]
    result=[]
    while queues and len(result)<limit:
        for queue in queues:
            if len(result)<limit:result.append(queue.popleft())
        queues=[queue for queue in queues if queue]
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--checkouts',type=Path,required=True)
    parser.add_argument('--reports',type=Path,required=True)
    parser.add_argument('--jar',type=Path,default=ROOT/'nullguard-cli/target/nullguard-cli-2.0.0-preview.1-jar-with-dependencies.jar')
    parser.add_argument('--output',type=Path,default=RECORDS)
    args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    repositories={entry['id']:entry for entry in json.loads((RECORDS/'applications.json').read_text())}
    pack_home=os.environ.get('NULLGUARD_PACK_HOME')
    metadata={'implementationCommit':subprocess.check_output(['git','-C',str(ROOT),'rev-parse','HEAD'],text=True).strip(),'implementationDiffSha256':hashlib.sha256(subprocess.check_output(['git','-C',str(ROOT),'diff','--binary'])).hexdigest(),'installedPacks':{}}
    if pack_home:
        for pack in ['python','ecmascript','csharp','go','rust']:
            folder=Path(pack_home)/pack;manifest=json.loads((folder/'pack.json').read_text());hashes={}
            for argument in manifest['command']:
                if argument.startswith('${pack}/'):
                    entry=folder/argument[8:];hashes[argument[8:]]=hashlib.sha256(entry.read_bytes()).hexdigest()
            metadata['installedPacks'][pack]={'version':manifest['version'],'entrypointSha256':hashes}
    (args.output/'scan-provenance.json').write_text(json.dumps(metadata,indent=2)+'\n')
    findings=defaultdict(list);summaries=[]
    for case in json.loads((RECORDS/'cases.json').read_text()):
        repository=repositories[case['repository']];checkout=(args.checkouts/repository['id']).resolve()
        head=subprocess.check_output(['git','-C',str(checkout),'rev-parse','HEAD'],text=True).strip()
        if head!=repository['commit']:raise ValueError('Checkout does not match pinned commit: '+case['application'])
        report_dir=(args.reports/case['application']).resolve()
        process=subprocess.run(['java','-jar',str(args.jar.resolve()),str(checkout/case['scope']),'--languages='+case['language'],'--timeout=120','--output='+str(report_dir)],capture_output=True,text=True,timeout=180)
        if process.returncode not in (0,3):raise RuntimeError(process.stderr)
        report=json.loads((report_dir/'nullguard-report-latest.json').read_text())
        observed=[]
        for project in report['projects']:
            for finding in project['findings']:
                location=finding['location'];source=(Path(project['root'])/location['path']).resolve();relative=source.relative_to(checkout).as_posix()
                observed.append(dict(application=case['application'],commit=head,language=case['language'],ruleId=finding['ruleId'],confidence=finding['confidence'],path=relative,line=location['startLine'],column=location['startColumn'],message=finding['message'],evidence=finding.get('evidence',[]),sourceUrl=repository['url']+'/blob/'+head+'/'+relative+'#L'+str(location['startLine']),sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),label=None,reviewer=None,reasoning=None))
        findings[case['language']].extend(observed)
        summaries.append(dict(**case,commit=head,status=report['status'],exitCode=process.returncode,findingCount=len(observed),projects=[dict(project=p['project'],status=p['status'],coverage=p['coverage'],diagnosticCount=len(p['diagnostics']),diagnostics=p['diagnostics'][:10]) for p in report['projects']]))
        print(case['application'],report['status'],len(observed),flush=True)
    (args.output/'review-counts.json').write_text(json.dumps({language:len(rows) for language,rows in findings.items()},indent=2)+'\n')
    selected=[row for language in sorted(findings) for row in sample(findings[language],50)]
    (args.output/'review-candidates.json').write_text(json.dumps(selected,indent=2)+'\n')
    text=json.dumps(summaries,indent=2).replace(str(args.checkouts.resolve()),'<applications>')
    (args.output/'scan-results.json').write_text(text+'\n')
    print('Exported',len(selected),'UNREVIEWED sample findings; no accuracy claim')

if __name__=='__main__':main()
