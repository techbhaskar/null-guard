---
title: "NullGuard 2.0: audit a mixed-language repository"
description: "Get started with NullGuard 2.0, its signed language packs, independent project reports and explicit analysis limits."
slug: nullguard-2-multi-language-analysis
authors: [nullguard]
tags: [releases, multi-language]
---

NullGuard 2.0 brings optional Python, JavaScript, TypeScript, C#, Go and Rust analyzers alongside the Java stability engine. A mixed repository can now be scanned through one CLI, with findings, source evidence and coverage diagnostics grouped by project.

The new packs remain **experimental and report-only**. A shared report format does not mean every language has the same capabilities.

<!-- truncate -->

## Start with the languages you need

Download the signed CLI from the [2.0.0 release](https://github.com/techbhaskar/null-guard/releases/tag/v2.0.0) and follow its verification instructions. The analyzer runs on Java 21. Each language pack also needs its own supported runtime.

Install only the packs you plan to use. Java analysis is built into the JVM distribution; JavaScript and TypeScript share the ECMAScript pack.

```bash
java -jar nullguard-cli.jar packs install python ecmascript csharp
java -jar nullguard-cli.jar packs list
java -jar nullguard-cli.jar . \
  --languages=java,python,javascript,typescript,csharp \
  --output=nullguard-out
```

Scanning does not download packs, restore application dependencies, import Python application modules, or execute application tests. Preparing dependencies and running tests are separate actions.

Go and Rust are opt-in too. Install their packs and select those languages explicitly, or use `--languages=auto` after provisioning all the packs needed by the repository.

## Read coverage before counting findings

Open `nullguard-out/nullguard-dashboard-latest.html`. The CLI also writes JSON schema 2.0 and SARIF output.

Before treating the result as useful evidence, check:

- Which projects and files were discovered and analyzed?
- Were any files unsupported or failed?
- Are dependency assets missing or symbols unresolved?
- Did an analysis budget run out?
- Which capabilities does each project report?

Exit code `0` means projects completed, not that they have no findings. Exit code `3` indicates partial coverage or an analysis/runtime failure. Other projects can still have retained reports; preserve the failure when presenting those results.

## Keep project boundaries honest

Java's engine builds control-flow and call graphs, follows risk through callers, and can connect supported API entry points to downstream methods. Its stability score and hotspot policy belong to that Java analysis path.

The experimental packs provide language-specific findings and coverage. They do not provide Java-style stability grades, mandatory finding gates, cross-language call graphs, framework OpenAPI generation or advisory reachability.

Matching method names or HTTP routes in two projects does not establish a call between them. A complete scan also does not resolve every runtime behavior or prove safety.

## Move from a report to a useful fix

Choose a finding, inspect the producer and dereference in the actual source, and check existing guards and contracts. Decide what a missing value should mean before inserting a fallback. Then add a meaningful regression test, make a focused change, and rerun the same analysis configuration.

The [language support guide](/null-guard/docs/languages/) explains the runtime matrix and language boundaries. The [full multi-language reference](/null-guard/docs/reference/multilang/) covers installation, reports and exit statuses. For a repeatable agent workflow, try the [free NullGuard audit skill](/null-guard/docs/agent-skill/).
