"""Mirror an existing signed Central release to GitHub Packages without rebuilding it.

Preparation verifies every original artifact/signature before any publication.
Credentials are environment variables; settings contain only environment references.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from publish_central import COMPONENTS, FINGERPRINT

CENTRAL = 'https://repo.maven.apache.org/maven2/'
REGISTRY = 'https://maven.pkg.github.com/techbhaskar/null-guard'
GROUP = 'com/arinits/nullguard'


def download(url, output):
    subprocess.run(['curl', '--fail', '--silent', '--show-error', '--location',
                    '--retry', '2', '--max-time', '120', '--output', str(output), url], check=True)


def prepare(version, directory):
    if not re.fullmatch(r'\d+\.\d+\.\d+', version):
        raise ValueError('Expected a stable numeric release version')
    inventory = []
    for artifact in sorted(COMPONENTS, key=lambda name: (name != "nullguard-parent", name)):
        relative = f'{GROUP}/{artifact}/{version}/'
        folder = directory / relative
        folder.mkdir(parents=True, exist_ok=True)
        index = folder / 'index.html'
        download(CENTRAL + relative, index)
        names = sorted(set(re.findall(r'href="([^"/]+\.(?:jar|pom|module))"', index.read_text())))
        if f'{artifact}-{version}.pom' not in names:
            raise ValueError(f'Missing POM for {artifact}')
        for name in names:
            if not name.startswith(f'{artifact}-{version}.') and not name.startswith(f'{artifact}-{version}-'):
                raise ValueError('Unexpected artifact filename')
            file = folder / name
            for suffix in ['', '.asc', '.sha256']:
                download(CENTRAL + relative + name + suffix, Path(str(file) + suffix))
            if hashlib.sha256(file.read_bytes()).hexdigest() != Path(str(file)+'.sha256').read_text().strip():
                raise ValueError(f'Checksum mismatch: {name}')
            verified = subprocess.run(['gpg', '--batch', '--status-fd', '1', '--verify', str(file)+'.asc', str(file)], capture_output=True, text=True)
            if verified.returncode or f'[GNUPG:] VALIDSIG {FINGERPRINT} ' not in verified.stdout:
                raise ValueError(f'Signature mismatch: {name}')
        pom = ET.parse(folder / f'{artifact}-{version}.pom').getroot()
        ns = {'m':'http://maven.apache.org/POM/4.0.0'}
        if pom.findtext('m:artifactId', namespaces=ns) != artifact:
            raise ValueError('POM artifact mismatch')
        actual_version = pom.findtext('m:version', namespaces=ns) or pom.findtext('m:parent/m:version', namespaces=ns)
        actual_group = pom.findtext('m:groupId', namespaces=ns) or pom.findtext('m:parent/m:groupId', namespaces=ns)
        if actual_version != version or actual_group != 'com.arinits.nullguard':
            raise ValueError('POM group/version mismatch')
        inventory.append({'artifact': artifact, 'files': names})
        print(f'Verified {artifact}: {len(names)} signed artifacts', flush=True)
    (directory / 'inventory.json').write_text(json.dumps({'version':version,'components':inventory}, indent=2)+'\n')
    return inventory


def registry_read(relative):
    credentials = (os.environ['GITHUB_PACKAGES_USERNAME'] + ':' + os.environ['GITHUB_PACKAGES_TOKEN']).encode()
    config = 'header = "Authorization: Basic ' + base64.b64encode(credentials).decode() + '"\n'
    result = subprocess.run(['curl','--silent','--show-error','--location','--config','-',
                             '--max-time','120','--write-out','\n%{http_code}',REGISTRY+'/'+relative],
                            input=config.encode(),capture_output=True,check=True)
    data, status = result.stdout.rsplit(b'\n',1)
    if status == b'404': return None
    if status != b'200': raise ValueError('GitHub registry read failed: HTTP '+status.decode())
    return data


def registry_upload(relative, file):
    credentials = (os.environ['GITHUB_PACKAGES_USERNAME'] + ':' + os.environ['GITHUB_PACKAGES_TOKEN']).encode()
    config = 'header = "Authorization: Basic ' + base64.b64encode(credentials).decode() + '"\n'
    subprocess.run(['curl','--fail','--silent','--show-error','--config','-',
                    '--max-time','120','--upload-file',str(file),REGISTRY+'/'+relative],input=config.encode(),check=True)


def existing_components(version, directory, inventory):
    existing = set()
    for component in inventory:
        artifact = component['artifact']
        prefix = f'{GROUP}/{artifact}/{version}/'
        pom = f'{artifact}-{version}.pom'
        remote = registry_read(prefix+pom)
        if remote is None: continue
        for name in component['files']:
            content = remote if name == pom else registry_read(prefix+name)
            if content != (directory/prefix/name).read_bytes():
                raise ValueError(f'Existing package differs or is incomplete: {artifact}:{version}/{name}; refusing overwrite')
        existing.add(artifact)
    return existing


def publish(version, directory, inventory):
    if not os.environ.get('GITHUB_PACKAGES_TOKEN') or not os.environ.get('GITHUB_PACKAGES_USERNAME'):
        raise ValueError('Set GITHUB_PACKAGES_USERNAME and GITHUB_PACKAGES_TOKEN')
    existing = existing_components(version, directory, inventory)
    with tempfile.TemporaryDirectory(prefix='nullguard-mirror-settings-') as temp:
        settings = Path(temp) / 'settings.xml'
        settings.write_text('<settings><servers><server><id>github</id><username>${env.GITHUB_PACKAGES_USERNAME}</username><password>${env.GITHUB_PACKAGES_TOKEN}</password></server></servers></settings>')
        settings.chmod(0o600)
        for component in inventory:
            artifact = component['artifact']
            if artifact in existing:
                print(f'Already mirrored and byte-verified {artifact}:{version}', flush=True)
                continue
            folder = directory / GROUP / artifact / version
            stem = f'{artifact}-{version}'
            pom = folder / (stem+'.pom')
            main = folder / (stem+'.jar')
            if not main.exists(): main = pom
            attached = [n for n in component['files'] if n.endswith('.jar') and n not in [main.name, pom.name]]
            args = ['mvn','--batch-mode','--non-recursive','--settings',str(settings),
                    'org.apache.maven.plugins:maven-deploy-plugin:3.1.4:deploy-file',
                    f'-Dfile={main}',f'-DpomFile={pom}','-DgeneratePom=false',
                    '-DrepositoryId=github',f'-Durl={REGISTRY}']
            if attached:
                args += ['-Dfiles='+','.join(str(folder/n) for n in attached),
                         '-Dclassifiers='+','.join(n[len(stem)+1:-4] for n in attached),
                         '-Dtypes='+','.join('jar' for _ in attached)]
            subprocess.run(args,check=True,timeout=180)
            for name in component['files']:
                if name.endswith('.module'):
                    registry_upload(f'{GROUP}/{artifact}/{version}/{name}', folder/name)
            for name in component['files']:
                if registry_read(f'{GROUP}/{artifact}/{version}/{name}') != (folder/name).read_bytes():
                    raise ValueError(f'Published artifact verification failed: {name}')
            print(f'Published and byte-verified {artifact}:{version}',flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version',required=True)
    parser.add_argument('--directory',type=Path,required=True)
    parser.add_argument('--publish',action='store_true')
    args=parser.parse_args()
    inventory=prepare(args.version,args.directory.resolve())
    if args.publish: publish(args.version,args.directory.resolve(),inventory)

if __name__=='__main__': main()
