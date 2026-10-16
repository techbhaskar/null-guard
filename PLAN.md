# Plan: finish analysis/call-resolution corrections and fix report OOM
_Locked via claudex-loop — by Claude + Bhaskar, 2026-10-16_

## Goal
Ship the staged analysis corrections (AST branch refinement, Java 17 parsing, framework contracts, symbol-only call
resolution), fix the precision regression they introduced, and make report generation stream so large services
(tou-rwa-access-core) no longer hit OutOfMemoryError.

## Key decisions
1. Unresolved calls never link to same-name project methods (Q1 — keep removed).
2. Report is held as an object model and streamed; HTML embeds it once, script-escaped; Gradle default heap 2g (Q2).
3. Receiver facts survive a statement unless that statement writes the receiver (replaces textual `contains("=")`).
4. Claude builds; Codex review and post-build inspection explicitly skipped by the user (Q3).

## Verification (run on the Mac)
`mvn -q install` in null-guard, then `./gradlew nullguard` (or the CLI jar) on tou-rwa-access-core.

## Review log
- Phase 2 (Codex plan review): skipped — user opted out, 2026-10-16.
- Post-build inspection: skipped — same opt-out.
