#!/usr/bin/env python3
"""Search historical NullGuard JSON reports using local embeddings and SochDB."""

import argparse
import json
import os
import re
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


def load_findings(paths):
    records = {}
    for path in dict.fromkeys(path.resolve() for path in paths):
        report = json.loads(path.read_text(encoding="utf-8-sig"))
        if isinstance(report, dict) and isinstance(report.get("findings"), list):
            projects = [report]
        elif isinstance(report, dict) and isinstance(report.get("projects"), list):
            projects = report["projects"]
        else:
            raise ValueError(f"{path}: expected a NullGuard JSON report")
        for project in projects:
            if not isinstance(project, dict) or not isinstance(project.get("findings"), list):
                raise ValueError(f"{path}: expected a findings array for each project")
            for finding in project["findings"]:
                if not isinstance(finding, dict) or not isinstance(finding.get("message"), str):
                    raise ValueError(f"{path}: expected a finding with a message")
                project_name = project.get("project")
                key = json.dumps([project_name, finding], sort_keys=True)
                if key not in records:
                    records[key] = {"report": str(path), "reports": [],
                                    "project": project_name, "finding": finding}
                reports = records[key]["reports"]
                if not reports or reports[-1] != str(path):
                    reports.append(str(path))
    return list(records.values())


def search(records, query, limit):
    if not records:
        return []
    model, index = build_index(records)
    return query_index(records, model, index, query, limit)


def build_index(records):
    import numpy as np
    from fastembed import TextEmbedding
    from sochdb import vector

    texts = []
    for record in records:
        finding = record["finding"]
        fields = [finding.get("ruleId"), finding.get("language"), finding["message"],
                  finding.get("methodId")]
        texts.append(" ".join(field for field in fields if field))

    model = TextEmbedding(model_name="BAAI/bge-small-en-v1.5")
    vectors = np.asarray(list(model.passage_embed(texts)), dtype=np.float32)
    if os.name == "nt":
        # SochDB 0.8.3 calls the Unix-only os.uname before checking Windows.
        dll = Path(vector.__file__).parent / "lib" / "x86_64-pc-windows-msvc" / "sochdb_index.dll"
        if not dll.is_file():
            raise RuntimeError(f"SochDB's bundled Windows index library is missing: {dll}")
        original_find_library = vector._find_library
        vector._find_library = lambda: str(dll)
        try:
            index = vector.VectorIndex(dimension=vectors.shape[1])
        finally:
            vector._find_library = original_find_library
    else:
        index = vector.VectorIndex(dimension=vectors.shape[1])
    index.insert_batch(np.arange(len(records), dtype=np.uint64), vectors)
    return model, index


def query_index(records, model, index, query, limit):
    query_vector = next(model.query_embed(query))
    return [dict(records[int(row)], distance=float(distance))
            for row, distance in index.search(query_vector, k=min(limit, len(records)))]


def serve_history(directory, port):
    directory = directory.resolve()
    page = directory / "nullguard-history.html"
    if not page.is_file():
        raise ValueError(f"Run the updated NullGuard analyzer to generate {page}")
    archive = re.compile(r"nullguard-(?:report-\d{8}_\d{6}(?:_\d+)?\.json|dashboard-(?:latest|\d{8}_\d{6}(?:_\d+)?)\.html)")
    snapshot = None
    records = []
    model = index = None

    class HistoryHandler(BaseHTTPRequestHandler):
        def respond(self, status, body, content_type):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def lookup(self, query):
            nonlocal snapshot, records, model, index
            paths = sorted(path for path in directory.glob("nullguard-report-*.json")
                           if archive.fullmatch(path.name) and path.is_file()
                           and path.resolve().parent == directory)
            version = [(path, path.stat().st_mtime_ns, path.stat().st_size) for path in paths]
            if snapshot != version:
                updated = load_findings(paths)
                model, index = build_index(updated) if updated else (None, None)
                records, snapshot = updated, version
            matches = query_index(records, model, index, query, 5) if records else []
            for match in matches:
                match["reports"] = [Path(path).name for path in match["reports"]]
                match.pop("report")
            return matches

        def do_GET(self):
            if self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}":
                self.send_error(403)
                return
            url = urlsplit(self.path)
            if url.path == "/api/similar-findings":
                query = parse_qs(url.query).get("q", [""])[0].strip()
                status = 200
                try:
                    if not query or len(query) > 500:
                        status, result = 400, {"error": "Enter a query between 1 and 500 characters."}
                    else:
                        result = {"matches": self.lookup(query)}
                except ImportError:
                    status, result = 503, {"error": "Install scripts/requirements-sochdb.txt and retry."}
                except (OSError, ValueError, RuntimeError) as exc:
                    status, result = 500, {"error": f"Lookup failed: {exc}"}
                self.respond(status, json.dumps(result).encode(), "application/json")
                return
            name = url.path.removeprefix("/")
            if name in ("", page.name):
                file = page
            elif archive.fullmatch(name) or name in ("nullguard-report-latest.json", "nullguard-openapi.json"):
                file = directory / name
            else:
                self.send_error(404)
                return
            try:
                if file.resolve().parent != directory:
                    self.send_error(404)
                    return
                content = file.read_bytes()
            except OSError:
                self.send_error(404)
                return
            mime = "application/json" if file.suffix == ".json" else "text/html; charset=utf-8"
            self.respond(200, content, mime)

    with HTTPServer(("127.0.0.1", port), HistoryHandler) as server:
        print(f"Open http://127.0.0.1:{server.server_port}/ for Run History and SochDB lookup.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="*", type=Path, help="historical NullGuard JSON reports")
    parser.add_argument("--query", help="finding message or a description to search for")
    parser.add_argument("--limit", type=int, default=3, help="maximum results (default: 3)")
    parser.add_argument("--serve", type=Path, metavar="REPORT_DIRECTORY", help="serve Run History with SochDB lookup")
    parser.add_argument("--port", type=int, default=8765, help="local server port (default: 8765)")
    args = parser.parse_args()
    if args.serve is not None:
        if args.reports or args.query is not None or not 0 <= args.port <= 65535:
            parser.error("--serve takes a report directory and an optional valid --port")
        try:
            serve_history(args.serve, args.port)
        except (OSError, ValueError) as exc:
            parser.exit(1, f"Unable to serve Run History: {exc}\n")
        return
    if not args.reports or args.limit < 1 or not args.query or not args.query.strip():
        parser.error("provide reports, a nonempty query and a positive limit")

    try:
        results = search(load_findings(args.reports), args.query, args.limit)
    except ImportError as exc:
        parser.exit(1, f"{exc}\nInstall dependencies with python -m pip install -r scripts/requirements-sochdb.txt\n")
    except (OSError, ValueError, RuntimeError) as exc:
        parser.exit(1, f"Finding search failed: {exc}\n")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
