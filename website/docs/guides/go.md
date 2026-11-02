# Go developer guide

Use the Go pack to investigate nil-state risks in Go source. Findings include operation locations and origin evidence. The pack is **experimental and report-only**; unresolved targets must remain unknown.

## Requirements and installation

Use a verified NullGuard 2.0.0 executable CLI, Java 21+, a compatible local Go toolchain, and GPG/GPGV for signed-pack installation. The release manifest declares Go 1.26+ and local-toolchain use; the analyzer pins `golang.org/x/tools` v0.52.0.

```bash
go version
java -jar nullguard-cli.jar packs install go
java -jar nullguard-cli.jar packs list
```

See [CLI setup](../getting-started.md) for verifying the executable and signed assets. Select the correct pack archive for your platform.

## Analyze your module

Supply the Go module directory with `go.mod` and its source tree:

```bash
java -jar nullguard-cli.jar /path/to/go-module \
  --languages=go --output=nullguard-out/go
```

Dependencies must already be available locally. Scanning does not automatically download modules; prepare dependencies separately using the project's established process. The selected target platform and build tags affect which code can be analyzed. Use `examples/multilang/go` for the repository fixture.

## Review a finding

The [Go fixture](../../../examples/multilang/go/app.go) shows a pointer dereference and a guarded alternative:

```go
func Unsafe(value *int) int { return *value }

func Guarded(value *int) int {
    if value == nil { return 0 }
    return *value
}
```

Returning zero is an example contract, not a universal fix. A real API may need an error or explicit absence instead. Verify the caller's expectations, add a regression test, run the project's tests (such as `go test ./...`) and rerun the same analysis.

## Read the output

Open `nullguard-out/go/nullguard-dashboard-latest.html`. Go findings use `NG-GO-`. Inspect `coverage.go`, missing dependencies, unresolved symbols and failed/unsupported files. An unresolved interface target does not establish that an operation is safe.

Exit `0` means completion, even with findings. Exit `3` means failure or partial coverage; useful partial results may remain. No Go stability grade or finding-enforcement gate is available.

## Boundaries

Test files are excluded from source analysis. Dynamic function values and unresolved interface dispatch remain unknown. Module resolution, build tags and platform-specific code constrain coverage. The coding-agent skill's five-language helper does not currently select Go; use the CLI directly. Read the [shared pack reference](../../../docs/multilang/README.md) for broader report semantics.
