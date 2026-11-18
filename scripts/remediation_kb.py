"""Manage reviewed NullGuard resolutions in an embedded SochDB database."""

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlsplit
from uuid import UUID, uuid4


PREFIX = b"remediation/v1/"
REQUIRED = ("repository", "commit", "finding_id", "rule_id", "language",
            "title", "applicability", "change", "pull_request", "author")


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def draft(path):
    fields = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(fields, dict) or set(fields) != set(REQUIRED):
        raise ValueError("Draft must contain exactly these fields: " + ", ".join(REQUIRED))
    for name, value in fields.items():
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{name} must be a nonempty string")
        fields[name] = value.strip()
    link = urlsplit(fields["pull_request"])
    if link.scheme not in ("https", "http") or not link.hostname:
        raise ValueError("pull_request must be an HTTP or HTTPS URL")
    return dict(fields, id=str(uuid4()), schema_version=1, status="draft", created_at=timestamp(), events=[])


def store(transaction, record):
    key = PREFIX + record["id"].encode()
    transaction.put(key, json.dumps(record, ensure_ascii=False).encode())


def read(transaction, identifier):
    value = transaction.get(PREFIX + str(UUID(identifier)).encode())
    if value is None:
        raise ValueError(f"Resolution {identifier} was not found")
    return json.loads(value)


def transition(transaction, args):
    record = read(transaction, args.id)
    expected, status = ("draft", "reviewed") if args.action == "review" else ("reviewed", "verified")
    if record["status"] != expected:
        raise ValueError(f"Resolution must be {expected} before it can be {status}")
    actor, evidence = args.actor.strip(), args.evidence.strip()
    if not actor or not evidence:
        raise ValueError("Actor and evidence must be nonempty")
    record["events"].append(dict(status=status, actor=actor, evidence=evidence, at=timestamp()))
    record["status"] = status
    store(transaction, record)
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True, help="SochDB directory")
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("add", help="store a draft from JSON").add_argument("file", type=Path)
    commands.add_parser("show", help="show a resolution and its review history").add_argument("id")
    listing = commands.add_parser("list", help="list records with optional exact filters")
    listing.add_argument("--repository")
    listing.add_argument("--rule-id")
    listing.add_argument("--language")
    listing.add_argument("--status", choices=("draft", "reviewed", "verified"))
    for action, help_text in (("review", "record a review decision"),
                              ("verify", "record verification evidence")):
        command = commands.add_parser(action, help=help_text)
        command.add_argument("id")
        command.add_argument("--actor", required=True)
        command.add_argument("--evidence", required=True)
    args = parser.parse_args()
    try:
        record = draft(args.file) if args.action == "add" else None
        if args.action != "add" and not args.db.is_dir():
            raise ValueError(f"Knowledge base does not exist: {args.db}")
        from sochdb import Database

        with Database.open(str(args.db.resolve())) as db, db.transaction() as transaction:
            if args.action == "add":
                store(transaction, record)
                result = record
            elif args.action == "show":
                result = read(transaction, args.id)
            elif args.action == "list":
                filters = {name: getattr(args, name) for name in ("repository", "rule_id", "language", "status")
                           if getattr(args, name) is not None}
                records = (json.loads(value) for _, value in transaction.scan_prefix(PREFIX))
                result = [item for item in records if all(item.get(key) == value for key, value in filters.items())]
            else:
                result = transition(transaction, args)
        print(json.dumps(result, indent=2, ensure_ascii=True))
    except ImportError:
        parser.exit(1, "Install SochDB with python -m pip install sochdb==0.8.3\n")
    except (OSError, ValueError, RuntimeError) as exc:
        parser.exit(1, f"Knowledge base: {exc}\n")


if __name__ == "__main__":
    main()
