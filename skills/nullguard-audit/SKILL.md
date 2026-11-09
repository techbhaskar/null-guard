---
name: nullguard-audit
description: Run NullGuard audits for Java, C#/.NET, Python, JavaScript and TypeScript projects, investigate findings, apply requested fixes with tests, and configure GitHub Actions. Use for NullGuard analysis, null-risk triage, or its CI adoption in single-language and mixed repositories.
license: MIT
---

# NullGuard audit

Requires Python 3.10+ for helpers and Java 21 for the analyzer. Language packs additionally require Python 3.11+, Node 22+, or .NET 10 as appropriate. Agents need local file and command execution access.

Turn NullGuard output into source-verified findings and, when requested, tested fixes or CI adoption. Support Java, C#/.NET, Python, JavaScript/JSX and TypeScript/TSX. C# support does not include VB or F#.

## Select and run

1. Inspect project manifests and the user's requested scope. Identify modules, language configurations, existing analyzer setup and test commands. Do not infer cross-service or cross-language calls from matching names.
2. Read [setup](references/setup.md) for the selected language. Java uses the published Maven/Gradle integrations pinned to **2.0.1**, with analyzer Java 21. Non-Java and mixed-language analysis requires an explicitly supplied compatible CLI and installed signed packs; describe the preview capability limits. Do not infer pack availability from the Java release number.
3. Use the helper from this skill's directory:

   ```sh
   python scripts/run_nullguard.py --project /absolute/project --build-tool auto --languages java
   python scripts/run_nullguard.py --project /absolute/project --build-tool cli --cli-jar /absolute/nullguard-cli.jar --languages auto
   ```

   Supply the repository root for mixed analysis. The helper is report-only and does not edit project configuration. Use `--languages java` with native Maven/Gradle, and explicit `--build-tool maven` or `gradle` for ambiguous roots. The default `--languages auto` means all five supported languages and requires `--cli-jar`; it must not silently audit only Java. CLI runs default to a fresh directory under `nullguard-out/`; an explicitly supplied `--output` directory must be empty. Missing packs require explicit provisioning; scanning must not install packs or restore application dependencies. If scripts cannot be accessed, use the equivalent native commands in setup.
4. Read fresh JSON reports and [interpretation guidance](references/reports.md). A nonzero run may retain useful partial reports, but it must remain visibly unsuccessful. Missing reports, empty sources, unsupported schemas or unknown coverage are not clean audits.

## Investigate and fix

- Inspect the actual source and related locations behind each important finding. Distinguish confirmed defects, plausible risks and findings needing more context. Preserve reported confidence; experimental pack output is not a verified defect merely because a rule fired.
- Prioritize by source evidence, consequences and supported caller/API exposure. Java exposes propagation and hotspots; pack reports do not establish Java-style stability grades, cross-language blast radius, advisory reachability or new OpenAPI output.
- Use [remediation guidance](references/remediation.md) for language-specific decisions. Apply fixes only within the user's requested scope, preserve intended error/null behavior, add meaningful regression coverage, run relevant tests and rerun the same audit. State checks that could not run.
- For requested CI changes, read [CI adoption](references/ci.md) and adapt the matching bundled template. Start report-only. Enable Java native gates only when requested; default an unspecified gate threshold to `CRITICAL`. Pack findings remain report-only.

## Deliver

Give the language/module scope, analyzer version, completeness/coverage limitations, prioritized findings with source evidence, and report locations. For requested edits, include what changed, tests and rerun results. Never present a good grade or zero findings as proof of safety.

For installation and recorded agent qualification, read [distribution](references/distribution.md). The [listing draft](references/marketplace.md) is for review, not permission to upload or publish.
