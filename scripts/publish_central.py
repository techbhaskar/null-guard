"""Bundle verified Maven/Gradle staging artifacts; explicitly upload or query Central.

Credentials are read only from a private Maven settings file (never printed).
Upload is deliberately a separate command, with no automatic retries.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

API = "https://central.sonatype.com/api/v1/publisher/"
FINGERPRINT = "440E91AFE2B4E7291DFBB4BCF03390620AF752E8"
COMPONENTS = {"nullguard-parent", "nullguard-core", "nullguard-analysis", "nullguard-callgraph",
              "nullguard-scoring", "nullguard-suggestions", "nullguard-visualization", "nullguard-engine",
              "nullguard-cli", "nullguard-maven-plugin", "nullguard-coverage", "nullguard-gradle-plugin",
              "com.arinits.nullguard.gradle.plugin"}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def bundle(args):
    entries = {}
    components = set()
    for root in args.staging:
        for file in sorted(root.rglob("*")):
            if not file.is_file() or file.suffix not in {".pom", ".jar", ".module"}:
                continue
            relative = file.relative_to(root)
            if relative.parent.name != args.version:
                continue
            if not relative.as_posix().startswith("com/arinits/nullguard/"):
                raise ValueError("Unexpected group in staging")
            if relative.as_posix() in entries:
                raise ValueError(f"Duplicate staged artifact: {relative}")
            signature = Path(str(file) + ".asc")
            if not signature.is_file() or not signature.read_bytes().startswith(b"-----BEGIN PGP SIGNATURE-----"):
                raise ValueError(f"Missing detached signature: {relative}")
            verified = subprocess.run([args.gpg, "--batch", "--status-fd", "1", "--verify", str(signature), str(file)],
                                      capture_output=True, text=True)
            if verified.returncode or f"[GNUPG:] VALIDSIG {FINGERPRINT} " not in verified.stdout:
                raise ValueError(f"Signature verification failed: {relative}")
            if file.suffix == ".pom":
                pom = ET.parse(file).getroot()
                ns = {"m": "http://maven.apache.org/POM/4.0.0"}
                artifact = pom.findtext("m:artifactId", namespaces=ns)
                version = pom.findtext("m:version", namespaces=ns) or pom.findtext("m:parent/m:version", namespaces=ns)
                if version != args.version or artifact != relative.parent.parent.name:
                    raise ValueError(f"Staged POM coordinate mismatch: {relative}")
                components.add(artifact)
            entries[relative.as_posix()] = file.read_bytes()
            entries[relative.as_posix() + ".asc"] = signature.read_bytes()
    if components != COMPONENTS:
        raise ValueError(f"Expected 13 components; missing={COMPONENTS - components}, unexpected={components - COMPONENTS}")
    artifact_count = len(entries) // 2
    for name, data in list(entries.items()):
        for algorithm in ("md5", "sha1", "sha256", "sha512"):
            entries[name + "." + algorithm] = hashlib.new(algorithm, data).hexdigest().encode("ascii")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise ValueError("Bundle already exists; choose a new output rather than overwriting release artifacts")
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in sorted(entries):
            archive.writestr(name, entries[name])
    manifest = {"version": args.version, "components": sorted(components), "verifiedSignatures": artifact_count,
                "signingFingerprint": FINGERPRINT, "bundleSha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
                "bundleBytes": args.output.stat().st_size}
    write_json(args.output.with_suffix(".manifest.json"), manifest)
    print(json.dumps(manifest))


def authorization(settings):
    root = ET.parse(settings).getroot()
    namespace = root.tag[1:].split("}", 1)[0] if root.tag.startswith("{") else ""
    ns = {"m": namespace}
    server = next((s for s in root.findall("m:servers/m:server", ns) if s.findtext("m:id", namespaces=ns) == "central"), None)
    if server is None:
        raise ValueError("Private Maven settings has no central server")
    def resolve(value):
        value = re.sub(r"\$\{env\.([^}]+)}", lambda match: os.environ.get(match.group(1), ""), value or "")
        if not value or value.startswith("{"):
            raise ValueError("Central credentials are unavailable or Maven-encrypted")
        return value
    token = resolve(server.findtext("m:username", namespaces=ns)) + ":" + resolve(server.findtext("m:password", namespaces=ns))
    return "Bearer " + base64.b64encode(token.encode()).decode()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request(settings, endpoint, data=b"", content_type=None):
    headers = {"Authorization": authorization(settings)}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(API + endpoint, data=data, headers=headers, method="POST")
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=180) as response:
            return response.read().decode()
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"Central returned HTTP {error.code}; credentials are not logged") from None


def upload(args):
    if args.state.exists():
        raise ValueError("Deployment state already exists: inspect status before any new upload")
    manifest = json.loads(args.bundle.with_suffix(".manifest.json").read_text(encoding="utf-8"))
    data = args.bundle.read_bytes()
    if manifest["bundleSha256"] != hashlib.sha256(data).hexdigest():
        raise ValueError("Bundle changed after signature verification")
    write_json(args.state, {"state": "UPLOAD_INTENT", "manifest": manifest})
    boundary = "nullguard-" + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
            'Content-Type: application/octet-stream\r\n\r\n').encode() + data + f"\r\n--{boundary}--\r\n".encode()
    endpoint = "upload?" + urllib.parse.urlencode({"name": "NullGuard " + manifest["version"], "publishingType": "AUTOMATIC"})
    deployment = request(args.settings, endpoint, body, f"multipart/form-data; boundary={boundary}").strip()
    uuid.UUID(deployment)
    write_json(args.state, {"deploymentId": deployment, "manifest": manifest})
    print(json.dumps({"deploymentId": deployment, "state": "UPLOADED_NOT_YET_CONFIRMED"}))


def status(args):
    deployment = json.loads(args.state.read_text(encoding="utf-8"))["deploymentId"]
    result = json.loads(request(args.settings, "status?" + urllib.parse.urlencode({"id": deployment})))
    write_json(args.state.with_suffix(".status.json"), result)
    print(json.dumps(result))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    prepare = sub.add_parser("bundle")
    prepare.add_argument("--version", required=True)
    prepare.add_argument("--staging", type=Path, action="append", required=True)
    prepare.add_argument("--gpg", required=True)
    prepare.add_argument("--output", type=Path, required=True)
    for command in ("upload", "status"):
        child = sub.add_parser(command)
        child.add_argument("--settings", type=Path, required=True)
        child.add_argument("--state", type=Path, required=True)
        if command == "upload":
            child.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    {"bundle": bundle, "upload": upload, "status": status}[args.command](args)
