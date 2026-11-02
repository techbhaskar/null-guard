# NullGuard for coding agents

Give your coding agent a repeatable workflow for running NullGuard, verifying findings against source, prioritizing fixes, and adopting analysis in CI.

The free MIT **nullguard-audit** skill covers Java, C#/.NET, Python, JavaScript and TypeScript, including mixed repositories. Engine support for Go and Rust does not imply those languages are qualified by this skill's helper.

## What the skill does

- Selects a Java build integration or an explicitly supplied CLI with signed language packs.
- Reads only fresh reports and keeps coverage gaps and independent-project boundaries visible.
- Investigates source evidence before treating a finding as a defect.
- Applies requested fixes, adds meaningful regression tests, and reruns tests and analysis.
- Guides GitHub Actions adoption with report-only defaults. Java enforcement is explicit; experimental pack findings stay report-only.

## Install the complete folder

Download the skill ZIP using the download link below, or obtain `skills/nullguard-audit` from the [source repository](https://github.com/techbhaskar/null-guard/tree/master/skills/nullguard-audit). Extract and copy the **whole** `nullguard-audit` folder into your agent's supported skill location; keep scripts, references, templates and the license together.

[Download the MIT skill ZIP](pathname:///null-guard/downloads/nullguard-audit.zip)

| Agent | Typical project installation |
| --- | --- |
| Codex | `.agents/skills/nullguard-audit/` |
| Claude Code | `.claude/skills/nullguard-audit/` |
| Cursor | `.cursor/skills/nullguard-audit/` |
| GitHub Copilot | `.github/skills/nullguard-audit/` |

Locations depend on the product surface and version. These installation targets are not a claim of tested compatibility. Read the [installation reference](../../skills/nullguard-audit/references/distribution.md) and [recorded qualification evidence](../../skills/nullguard-audit/references/qualification.md).

## Try a real task

```text
Use nullguard-audit to audit this Python and TypeScript repository.
Show source evidence and explain any analysis gaps. Do not change code.
```

```text
Investigate this C# finding. Fix it if the source confirms the defect,
preserve our intended missing-value behavior, and run the regression tests.
```

```text
Add report-only NullGuard analysis to this Gradle project's GitHub Actions.
Keep the application's existing compilation toolchain.
```

## Run the helper directly

The helper needs Python 3.10+ and Java 21 for the analyzer. Language runtimes and signed packs are separate dependencies.

```bash
# Java using the project's native integration.
python skills/nullguard-audit/scripts/run_nullguard.py \
  --project . --build-tool maven --languages java

# The skill's auto selection covers its five supported languages.
python skills/nullguard-audit/scripts/run_nullguard.py \
  --project . --cli-jar /absolute/nullguard-cli.jar --languages auto
```

See the [complete workflow](../../skills/nullguard-audit/SKILL.md), [setup](../../skills/nullguard-audit/references/setup.md), [report interpretation](../../skills/nullguard-audit/references/reports.md), and [CI reference](../../skills/nullguard-audit/references/ci.md). No engine binaries, runtimes or packs are bundled with the skill.
