---
title: "From a null warning to a tested fix with your coding agent"
description: "Use the free NullGuard audit skill to review source evidence, preserve missing-value behavior and verify a focused fix."
slug: null-warning-to-tested-fix
authors: [nullguard]
tags: [agent-skills, remediation]
---

A null warning identifies a place to investigate. It does not decide whether the application should reject missing input, return an empty result, or propagate the absence to a caller.

The free **nullguard-audit** skill gives a coding agent a repeatable path through that decision: run analysis, inspect the source, explain the risk, and make a tested fix when requested.

<!-- truncate -->

## Ask for evidence first

Install the complete skill folder using the [installation guide](/null-guard/docs/agent-skill/). The package contains instructions, a report-only runner, references and CI templates. Engine binaries, runtimes and language packs are separate dependencies.

Start with a bounded request:

```text
Use nullguard-audit to audit this Python and TypeScript repository.
Show the source evidence for important findings and explain coverage gaps.
Do not change application code yet.
```

The helper covers Java, Python, JavaScript, TypeScript and C#, including mixed repositories. The engine's additional Go/Rust packs are outside this helper's current language scope. End-user qualification across agent products remains separately recorded; a portable format alone does not establish that every agent has been tested.

## Decide what absence means

Consider this illustrative Python function:

```python
def initial(name: str | None) -> str:
    return name.strip()[:1]
```

If `name` is `None`, the dereference fails. But adding any guard is not automatically the right application fix.

For this example, suppose the intended contract is explicit: a missing display name should produce an empty initial. Then the focused change is:

```python
def initial(name: str | None) -> str:
    if name is None:
        return ""
    return name.strip()[:1]
```

If the real contract requires a name, rejecting the input at its boundary may be correct instead. The agent should inspect the callers and existing tests rather than inventing that policy.

## Verify behavior and rerun analysis

For the example's chosen contract, a small regression test exercises both the missing-value path and the existing behavior:

```python
import unittest

class InitialTests(unittest.TestCase):
    def test_missing_name_has_no_initial(self):
        self.assertEqual(initial(None), "")

    def test_present_name_is_trimmed(self):
        self.assertEqual(initial(" Ada "), "A")

    def test_blank_name_has_no_initial(self):
        self.assertEqual(initial("   "), "")
```

These snippets are illustrative; adapt imports and test discovery to the application. Ask the agent to run the actual tests, rerun the same NullGuard command, and report which checks completed. A quieter report is useful evidence, but does not prove that every null path is safe.

## Introduce CI gradually

The skill includes GitHub Actions templates for Maven, Gradle and explicitly provisioned language packs. Start with report-only findings and retain reports after failure.

Report-only findings do not mean execution errors should be ignored. Missing packs, malformed reports and partial analysis must remain visible failures. Java's optional native hotspot gate is an explicit adoption decision; it is not a gate on every null warning, and it should not be invented for experimental pack findings.

Read the [CI adoption reference](/null-guard/docs/agent-skill/references/ci/) and [report interpretation guide](/null-guard/docs/agent-skill/references/reports/) before enabling enforcement.
