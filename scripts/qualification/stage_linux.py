"""Stage only public pack sources, fixtures and the CLI for Linux qualification.

An allowlist avoids copying publishing credentials, local caches or signing keys.
"""
import argparse
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=False)
ignore=shutil.ignore_patterns('node_modules','target','bin','obj','__pycache__','build')
for name in ['language-packs','scripts']:
 shutil.copytree(ROOT/name,a.output/name,ignore=ignore)
shutil.copytree(ROOT/'examples/multilang',a.output/'examples/multilang',ignore=ignore)
shutil.copy2(ROOT/'LICENSE',a.output/'LICENSE')
jar='nullguard-cli/target/nullguard-cli-2.0.0-preview.1-jar-with-dependencies.jar'
(a.output/Path(jar).parent).mkdir(parents=True)
shutil.copy2(ROOT/jar,a.output/jar)
print(a.output.resolve())
