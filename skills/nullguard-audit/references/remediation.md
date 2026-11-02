# Remediation decisions

Trace the reported value to its producer and relevant guards before changing code. A guard must preserve the application's intended contract; silently dropping work or substituting a value can hide a defect. Prefer fixes at the boundary that owns validation, particularly when several callers share the same unsafe assumption.

| Language | Checks before fixing | Useful regression cases |
| --- | --- | --- |
| Java | Nullable returns, Optional contracts, Spring/JAX-RS validation, shared utility callers; do not turn optional input into a new exception accidentally | Missing record/client response, existing success path, affected endpoint behavior |
| C#/.NET | `T?` contracts, `Try*` APIs, nullable annotations, deserialization and dependency return values; `!` suppresses diagnostics without adding a runtime check | Null DTO/member, missing lookup result, expected HTTP/error result |
| Python | `None` from dict lookups, optional arguments and implicit returns; distinguish `None` from valid falsy values | `None`, empty/zero/false valid inputs, normal result |
| JavaScript | `null` versus `undefined`, missing properties and asynchronous responses; optional chaining must not silently skip required behavior | Missing object/property, rejected/missing response, valid falsy value |
| TypeScript | Narrowing and compiler null settings, generated/client types, non-null assertions; avoid assertions that merely silence analysis | Runtime null despite declared type, optional fields, normal typed input |

For JS/TS, keep the host project's compiler null settings. For C#, do not enable nullable checks across an entire codebase as an incidental fix. Missing framework/dependency/generated assets should be corrected or reported as analysis limitations before treating their consequences as application defects.

When fixes are requested: make the smallest behavior-preserving edit, add a regression test that exercises the actual failure, run relevant existing tests and rerun the same NullGuard scope/version. Compare individual findings and coverage, not only score changes. Record failures and unavailable commands. Do not suppress findings or weaken a gate to make the rerun appear successful.
