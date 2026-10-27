# Mixed-repository quick start

These examples deliberately contain one unsafe function and a guarded counterpart
for each of the six preview languages. They are fixtures, not real-application
accuracy evidence. No example needs to be built or executed for analysis.

Build the preview CLI and install the five signed language pack archives following
[the multi-language guide](../../docs/multilang/README.md). Then:

```sh
java -jar nullguard-cli/target/nullguard-cli-2.0.0-preview.1-jar-with-dependencies.jar examples/multilang --languages=auto --output=preview-reports
```

Open `preview-reports/nullguard-dashboard-latest.html`. Filter by language and
compare each unsafe function with its guarded counterpart. The Java example is included alongside the preview language projects.

Individual scans use `--languages=python`, `--languages=csharp`,
`--languages=javascript,typescript`, `--languages=go`, or `--languages=rust` with
the corresponding project directory. The React example uses JSX without running
React. Angular component TypeScript is analyzed in the same way; Angular HTML
template expressions are excluded.
