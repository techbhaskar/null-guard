# Installation and compatibility

Copy the complete `nullguard-audit` directory, including scripts, references, assets and LICENSE, into the skill directory supported by the agent surface. Keep a single canonical source in this repository; install copies without editing them independently. Name/frontmatter follows the open Agent Skills format. No engine JARs, runtimes or language pack binaries are bundled.

Typical project locations are `.agents/skills/nullguard-audit` for Codex, `.claude/skills/nullguard-audit` for Claude Code, `.cursor/skills/nullguard-audit` for Cursor, and `.github/skills/nullguard-audit` for Copilot. These are installation targets, not evidence of agent compatibility. Verify the current product/surface's official installation guidance before documenting an installation as tested. Some agents also recognize shared directories.

Example requests:

- “Audit this mixed Python and TypeScript repository with NullGuard. Explain analysis gaps.”
- “Investigate the C# findings, fix confirmed defects and run our tests.”
- “Add report-only NullGuard CI for this Java Gradle project.”

## Qualification record

See [qualification evidence](qualification.md) for executed helper, native integration and pack fixture tests. No Codex, Claude Code, Cursor or Copilot end-user installation/behavior run is certified by this document; helper tests are distinct from testing each agent's discovery and execution.

Before claiming tested compatibility, record agent/surface/version, installation location, engine/pack versions, runtime versions and outcomes for audit, requested fix/test/rerun and CI setup. For language coverage, exercise Java, Python, JavaScript, TypeScript, C# and a mixed repository; record partial coverage and unsupported behavior. Keep unavailable agent access marked untested. Do not claim universal agent or OS support from a portable file format.

The ZIP is a free MIT package. Keep its license notice. Test the extracted archive independently of the checkout and confirm all relative references/scripts resolve. Publish/upload only under explicit user instruction; listing preparation alone does not authorize publication.
