# JavaScript and JSX developer guide

Use the ECMAScript pack to investigate null/undefined dereferences in JavaScript and JSX. JavaScript and TypeScript share the same pack, but can be selected independently. Findings are **experimental and report-only**.

## Requirements and installation

Use a verified NullGuard 2.0.1 executable CLI, Java 21+, Node 22+ and GPG/GPGV for signed-pack installation. The pack carries its pinned TypeScript compiler API; it does not require globally installing TypeScript.

```bash
node --version
java -jar nullguard-cli.jar packs install ecmascript
java -jar nullguard-cli.jar packs list
```

See [CLI setup](../getting-started.md) for obtaining and verifying `nullguard-cli.jar`.

## Analyze your project

Supply the project directory with its `package.json` and applicable `jsconfig.json` or `tsconfig.json`. The analyzer honors project file selection and compiler settings. Files outside the selected configuration can be reported unsupported; do not silently broaden the application's configuration to hide that diagnostic.

```bash
java -jar nullguard-cli.jar /path/to/web-project \
  --languages=javascript --output=nullguard-out/javascript
```

For a project containing both languages, use `--languages=javascript,typescript`. To scan the repository's example, pass `examples/multilang/web`. Scanning does not run npm install, build the application or launch a browser.

## Review a finding

The [JSX fixture](../../../examples/multilang/web/view.jsx) contains a definitely null receiver:

```jsx
export function unsafe() {
  const model = null;
  return <div>{model.name}</div>;
}

export function guarded(model) {
  return <div>{model?.name ?? "Unknown"}</div>;
}
```

The fallback is an illustrative UI contract. Check whether missing data should instead trigger loading, validation or an error state in the real component. Verify those behaviors with the project's tests before rerunning analysis.

## Read the output

Open `nullguard-out/javascript/nullguard-dashboard-latest.html`. Findings use `NG-ECMA-` rule IDs, with the language identifying JavaScript versus TypeScript. In JSON, inspect `coverage.javascript`, file/configuration diagnostics, source evidence and confidence.

Exit `0` means analysis completed, even if findings remain. Exit `3` means failure or partial coverage. The pack has no stability grade or mandatory finding gate.

## Boundaries

JSX source analysis does not establish React lifecycle correctness. Angular HTML templates, browser lifecycle behavior, generated source and dynamic dispatch verification are outside scope. Preserve strict-null settings and review unresolved symbols rather than assuming a safety contract. Read the [TypeScript guide](typescript.md) for TS/TSX and the [shared pack reference](../../../docs/multilang/README.md) for report semantics.
