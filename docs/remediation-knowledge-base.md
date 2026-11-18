# Remediation knowledge base

Store reviewed resolutions in a local SochDB directory. Populate it from real
pull requests and verification records before using it for recommendations.
The CLI stores complete JSON records and prints JSON for later integrations.

## Record a draft

Install the storage dependency with `python -m pip install sochdb==0.8.3`.
Save a JSON file with these fields, replacing the illustrative values with
evidence from your own repository:

```json
{
  "repository": "example/orders",
  "commit": "commit inspected for the finding",
  "finding_id": "identifier from the scan or tracking system",
  "rule_id": "NG001",
  "language": "java",
  "title": "Handle a missing order",
  "applicability": "Order lookup may return no result",
  "change": "Map the missing-order result at the endpoint boundary",
  "pull_request": "https://example.com/orders/pull/123",
  "author": "developer account"
}
```

```powershell
$db = Join-Path $env:LOCALAPPDATA "NullGuard/remediation-kb"
$record = python scripts/remediation_kb.py --db $db add resolution.json | ConvertFrom-Json
python scripts/remediation_kb.py --db $db show $record.id
```

## Review and verify

Record the review decision, then attach test or CI evidence identifying what
passed and which revision was tested. The CLI records the supplied actor,
evidence, and UTC time for each transition.

```powershell
python scripts/remediation_kb.py --db $db review $record.id --actor "reviewer-account" --evidence "Review decision and link"
python scripts/remediation_kb.py --db $db verify $record.id --actor "verifier-account" --evidence "Test result, CI link, and tested revision"
python scripts/remediation_kb.py --db $db list --status verified --language java --rule-id NG001
```

This local CLI records operator attestations. Restrict access to the database
directory to expected operators. Actor names and evidence are supplied by the
operator. Integration with repository permissions and CI identity belongs in
a future shared deployment.
