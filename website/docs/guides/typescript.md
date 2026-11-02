# TypeScript and TSX developer guide

Use the ECMAScript pack to investigate nullable values and unsafe operations in TypeScript and TSX. It uses the TypeScript compiler API alongside NullGuard's analysis. Findings remain **experimental and report-only**.

## Requirements and installation

Use a verified NullGuard 2.0.0 executable CLI, Java 21+, Node 22+ and GPG/GPGV for signed-pack installation. This release's pack pins TypeScript 6.0.3; installing it does not require changing your application's TypeScript dependency.

```bash
node --version
java -jar nullguard-cli.jar packs install ecmascript
java -jar nullguard-cli.jar packs list
```

The same pack also handles JavaScript and JSX. Follow [CLI setup](../getting-started.md) to obtain and verify the executable.

## Analyze your project

Point NullGuard at the project directory containing its `tsconfig.json` and sources:

```bash
java -jar nullguard-cli.jar /path/to/typescript-project \
  --languages=typescript --output=nullguard-out/typescript
```

Project compiler settings, including strict-null settings and file inclusion, are preserved. Files outside the selected configuration can be reported unsupported. For JS and TS together, select `--languages=javascript,typescript`. Use `examples/multilang/web` to scan the repository fixture.

Scanning does not run package installation, generators or application tests. Prepare missing application assets separately using the project's existing workflow.

## Review a finding

The [TypeScript fixture](../../../examples/multilang/web/service.ts) makes the nullable contract explicit:

```typescript
export function unsafe(name: string | null) {
  return name.length;
}

export function guarded(name: string | null) {
  return name?.length ?? 0;
}
```

The example chooses length zero for a missing name. Check that this matches the real application's contract; a type assertion or non-null assertion alone does not supply a runtime guard. Add appropriate tests, run the project's normal type check/tests and rerun analysis.

## Read the output

Open `nullguard-out/typescript/nullguard-dashboard-latest.html`. JSON findings use `NG-ECMA-` rule IDs and a language field; review `coverage.typescript`, configuration diagnostics, evidence and unresolved symbols.

Exit `0` means completion, not zero findings. Exit `3` means failure or partial coverage. No TypeScript stability grade or finding-enforcement gate is available.

## Boundaries

TSX is source-analyzed without verifying React or browser lifecycle behavior. Angular component TypeScript can be analyzed, but Angular HTML templates are excluded. Generated source and dynamic dispatch remain limitations. Read the [JavaScript guide](javascript.md) for JS/JSX and the [shared pack reference](../../../docs/multilang/README.md) for capability and coverage semantics.
