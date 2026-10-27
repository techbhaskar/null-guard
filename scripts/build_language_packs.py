"""Build reproducible optional pack archives. Publishing is deliberately separate.

Compiler dependencies are locked. Installation requires adjacent .sha256 and .asc
files; use --sign-key with a locally configured GPG release key to sign archives.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
IDS=('python','ecmascript','csharp','go','rust')
VERSION='2.0.0-preview.1'

def run(command,cwd,env):
 subprocess.run(command,cwd=cwd,env=env,check=True)

def executable(name):
 path=shutil.which(name)
 if path:return path
 candidate=Path('/opt/homebrew/bin')/name
 if candidate.exists():return str(candidate)
 raise RuntimeError(f'{name} is required to build language packs')

def build(pack,stage,env):
 source=ROOT/'language-packs'/pack
 manifest=json.loads((source/'pack.json').read_text())
 if pack=='python':
  shutil.copy2(source/'analyzer.py',stage/'analyzer.py')
  if os.name=='nt':manifest['command'][0]='python'
 elif pack=='ecmascript':
  for name in ['package.json','package-lock.json','analyzer.cjs']:shutil.copy2(source/name,stage/name)
  run([executable('npm'),'ci','--ignore-scripts','--omit=dev','--no-audit','--no-fund'],stage,env)
 elif pack=='go':
  (stage/'bin').mkdir();name='nullguard-go'+('.exe' if os.name=='nt' else '')
  run([executable('go'),'build','-trimpath','-buildvcs=false','-ldflags=-buildid=','-o',str(stage/'bin'/name),'.'],source,env)
  manifest['command']=['${pack}/bin/'+name]
 elif pack=='rust':
  target=stage.parent/'rust-target'
  rustenv=dict(env,CARGO_TARGET_DIR=str(target),RUSTFLAGS='--remap-path-prefix='+str(source)+'=/nullguard/rust')
  run([executable('cargo'),'build','--release','--locked'],source,rustenv)
  (stage/'bin').mkdir();name='nullguard-rust'+('.exe' if os.name=='nt' else '')
  shutil.copy2(target/'release'/name,stage/'bin'/name);manifest['command']=['${pack}/bin/'+name]
 elif pack=='csharp':
  # Build a copy so IDE/parallel builds cannot replace files in the publication.
  copied=(stage.parent/'csharp-source').resolve();shutil.copytree(source,copied,ignore=shutil.ignore_patterns('bin','obj'))
  run([executable('dotnet'),'restore','NullGuard.CSharp.csproj','--locked-mode'],copied,env)
  run([executable('dotnet'),'publish','NullGuard.CSharp.csproj','--no-restore','-c','Release','-p:ContinuousIntegrationBuild=true','-p:Deterministic=true','-p:PathMap='+str(copied)+'=/nullguard/csharp','-o',str(stage/'bin')],copied,env)
 manifest['qualification']={'status':'experimental','highConfidenceRules':[]}
 (stage/'pack.json').write_text(json.dumps(manifest,indent=2,sort_keys=True)+'\n')
 for name in ['LICENSE']:
  shutil.copy2(ROOT/name,stage/name)
 # Preserve dependency locks as provenance without including compiler/toolchain caches.
 for name in ['go.mod','go.sum','Cargo.toml','Cargo.lock','NullGuard.CSharp.csproj','packages.lock.json']:
  if (source/name).exists():shutil.copy2(source/name,stage/name)


def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--packs',nargs='+',choices=IDS,default=list(IDS));parser.add_argument('--output',type=Path,default=ROOT/'dist-packs');parser.add_argument('--sign-key');args=parser.parse_args()
 os_id={'Darwin':'macos','Linux':'linux','Windows':'windows'}.get(platform.system())
 arch={'arm64':'arm64','aarch64':'arm64','x86_64':'x64','AMD64':'x64'}.get(platform.machine())
 if not os_id or not arch:raise RuntimeError('Unsupported build platform')
 args.output.mkdir(parents=True,exist_ok=True)
 env=dict(os.environ,SOURCE_DATE_EPOCH='1704067200',DOTNET_CLI_TELEMETRY_OPTOUT='1',DOTNET_NOLOGO='1',GOTOOLCHAIN='local')
 index={'version':VERSION,'protocolVersion':'1.0','platform':os_id+'-'+arch,'packs':[]}
 with tempfile.TemporaryDirectory(prefix='nullguard-pack-build-') as work:
  for pack in args.packs:
   stage=Path(work)/pack;stage.mkdir();build(pack,stage,env)
   output=args.output/f'nullguard-pack-{pack}-{os_id}-{arch}.zip'
   with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
    for file in sorted(stage.rglob('*')):
     if not file.is_file():continue
     info=zipfile.ZipInfo(file.relative_to(stage).as_posix(),date_time=(2024,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
     info.external_attr=(0o100755 if file.parent.name=='bin' else 0o100644)<<16
     archive.writestr(info,file.read_bytes())
   digest=hashlib.sha256(output.read_bytes()).hexdigest();Path(str(output)+'.sha256').write_text(digest+'  '+output.name+'\n')
   if args.sign_key:run([executable('gpg'),'--batch','--yes','--armor','--detach-sign','--local-user',args.sign_key,'--output',str(output)+'.asc',str(output)],ROOT,env)
   index['packs'].append({'id':pack,'asset':output.name,'sha256':digest,'bytes':output.stat().st_size,'signed':bool(args.sign_key)})
   print('Built',output,flush=True)
 (args.output/'packs-index.json').write_text(json.dumps(index,indent=2,sort_keys=True)+'\n')
 if args.sign_key:run([executable('gpg'),'--batch','--yes','--armor','--detach-sign','--local-user',args.sign_key,'--output',str(args.output/'packs-index.json.asc'),str(args.output/'packs-index.json')],ROOT,env)

if __name__=='__main__':main()
