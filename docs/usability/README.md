# Unaided developer study

Status: recruitment prepared; no human sessions have been observed yet.
Recruit through the LinkedIn draft below. Do not substitute automated agent runs
for human usability evidence. Published version: 1.0.0; unreleased fixes:
1.0.1-SNAPSHOT. Record the exact version each volunteer uses.

## LinkedIn draft (not posted)

I’m looking for Java developers to try NullGuard, an early-stage null-safety
analysis tool with a Maven plugin and HTML/JSON/SARIF reports.

Version 1.0.0 is on Maven Central. I’d like feedback on the first-run experience:
can you follow the README, analyze a Java project, find the reports, and decide
whether a warning is actionable? False positives are expected; start in
report-only mode.

If you can spare about 15–20 minutes, comment or message me. No production code
needs to be shared. README: https://github.com/techbhaskar/null-guard

## Participant task

1. Choose a Java Maven project you are allowed to analyze.
2. Start from the README alone. Install/run NullGuard and find the output reports.
3. Choose one warning and decide whether it needs a code change. If possible,
   make the change and rerun; otherwise explain why the warning is not useful.
4. Complete feedback-template.md. You can stop whenever you want.

The suggested time is a study timebox, not a claim about tool setup speed.
Share commands, redacted errors and report excerpts only if permitted. Full
source code and full reports are optional, not required.

## Observer protocol

Recruit 3–5 volunteers with different Java/Maven experience. Give only the task
above and the README link. Record the release/source commit and README commit.
Ask the participant to think aloud if comfortable. Do not coach them through
setup or interpret warnings for them. If intervention is necessary, record its
time, reason and exact assistance; that session no longer counts as unaided.

Record: time to first successful analysis; time to locate a report; whether a
warning was interpreted correctly; whether a valid fix cleared it; where setup
failed; and whether assistance was needed. Do not equate zero findings with
success. Retain feedback only with participant consent; anonymize identifiers.

Success criteria per session: successful run, report found, one warning judged
with source evidence, and no assistance. If there are no warnings, record the
interpretation task as not evaluated. Report task completion counts and actual
timings; do not invent results for participants who have not responded.

After sessions, summarize repeated blockers and individual exceptions before
changing README or recommending CI enforcement. Recruitment has not been sent.
