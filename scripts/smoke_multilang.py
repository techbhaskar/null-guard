"""Install signed release archives into an empty home, then scan the mixed example.

Usage: python scripts/smoke_multilang.py --assets dist-packs
This intentionally uses the production installer, not development pack paths.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
VERSION='2.0.0-preview.1'
PACKS=['python','ecmascript','csharp','go','rust']

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--assets',type=Path,required=True);args=parser.parse_args()
 family={'Darwin':'macos','Linux':'linux','Windows':'windows'}[platform.system()];arch={'arm64':'arm64','aarch64':'arm64','AMD64':'x64','x86_64':'x64'}[platform.machine()]
 jar=ROOT/f'nullguard-cli/target/nullguard-cli-{VERSION}-jar-with-dependencies.jar'
 with tempfile.TemporaryDirectory(prefix='nullguard-clean-install-') as directory:
  home=Path(directory);output=home/'reports';env=dict(os.environ,NULLGUARD_PACK_HOME=str(home/'packs'))
  def run(*arguments,expected=0):
   result=subprocess.run(['java','-jar',str(jar),*map(str,arguments)],cwd=ROOT,env=env,capture_output=True,text=True,timeout=300)
   if result.returncode!=expected:raise AssertionError(f'{arguments}: expected {expected}, got {result.returncode}\n{result.stdout}\n{result.stderr}')
  for pack in PACKS:run('packs','install',pack,'--from='+str((args.assets/f'nullguard-pack-{pack}-{family}-{arch}.zip').resolve()))
  run(ROOT/'examples/multilang','--languages=auto','--output='+str(output))
  report=json.loads((output/'nullguard-report-latest.json').read_text());assert report['schemaVersion']=='2.0' and report['status']=='complete'
  assert {l for p in report['projects'] for l in p['languages']}=={'java','python','javascript','typescript','csharp','go','rust'}
  assert all(p['findings'] for p in report['projects'])
  for p in report['projects']:
   for c in p['coverage'].values():assert c['discoveredFiles']==c['analyzedFiles'] and c['failedFiles']==0 and c['unsupportedFiles']==0
   if p['languages']!=['java']:assert p['capabilities']['stabilityScore'] is False
  for pack in PACKS:run('packs','remove',pack)
  assert not any((home/'packs').iterdir())
 print('Signed clean installation, seven-language mixed scan and removal passed on '+family+'-'+arch)

if __name__=='__main__':main()
