# Implementation qualification — 2026-11-02

## Executed checks

- Runner behavioral suite: 27 tests, including language selection, native build
  selection, wrapper precedence, fresh reports, schema validation, coverage gaps,
  partial reports and execution errors. Python 3.13 on macOS arm64.
- Published Java integration 2.0.0: real Maven and Gradle 8.14.4 Groovy/Kotlin
  fixtures. Each produced two findings and a hotspot; report-only audit succeeded,
  explicit LOW policy enforcement failed with a policy-specific message and a
  fresh retained report. Guarded fixes produced zero findings, JUnit regression
  tests passed and fresh audits passed. These are fixture results, not precision
  measurements for real applications.
- Existing executable CLI 2.0.0-preview.1 and locally signed macOS arm64 archives:
  signed installation into an isolated pack home; individual Java, Python,
  JavaScript, TypeScript and C# scans all produced seeded findings. Mixed scan
  completed with all five languages and independent per-project coverage.
- Missing Python pack: scan exited 3 and retained partial reports. Non-Java
  stability-score capabilities remained false. Unresolved symbols stayed visible.
- Pack execution environment: Java 21.0.9, Python 3.13.13, Node 22.22.0,
  .NET SDK 10.0.401. Fixture NuGet restore was explicit setup outside scanning.
- Independent Codex subagent forward-test audited a temporary Java/Python/TS/C#
  repository, verified findings against source, preserved source files and exposed
  capability limits. It caught a mixed-language example omitting Java; corrected
  to helper `--languages auto` before packaging.

Reproduce from the source repository:

```sh
python3 -m unittest discover -s scripts/tests -p test_nullguard_skill.py -v
python3 scripts/smoke_nullguard_skill.py --gradle /absolute/gradle --require-gradle
python3 scripts/smoke_nullguard_skill_packs.py --cli-jar /absolute/verified-cli.jar --pack-archives /absolute/signed-archives
python3 scripts/package_nullguard_skill.py
```

## Still unqualified

Claude Code, Cursor and Copilot installation/discovery and behavioral runs have
not been executed. The Codex forward-test is not an installed-skill discovery
test. All four end-user surfaces require versioned audit/fix/CI evidence before
advertising broad tested compatibility. This package does not certify Windows or
Linux runtime behavior; its source CI workflow will provide separate results.

Local signed pack tests do not prove public signed-asset availability, production
accuracy or marketplace resource delivery. Public release qualification and
Agensi submission remain separate. No marketplace upload was performed.
