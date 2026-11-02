# C# and .NET developer guide

Use the C# pack to investigate nullable values and unsafe dereferences in SDK-style C# projects. It uses Roslyn 5.0.0 and reports source evidence, confidence and coverage diagnostics. Analysis is **experimental and report-only**. VB.NET and F# are outside scope.

## Requirements and installation

Use a verified NullGuard 2.0.0 executable CLI, Java 21+, .NET SDK 10 and GPG/GPGV for signed-pack installation:

```bash
dotnet --version
java -jar nullguard-cli.jar packs install csharp
java -jar nullguard-cli.jar packs list
```

See [CLI setup](../getting-started.md) for distribution verification. Existing application target frameworks and SDK selection should remain unchanged unless the application itself needs an update; the analyzer runtime is a separate requirement.

## Prepare and analyze the project

Supply a directory containing an SDK-style `.csproj` and its sources. If restore assets are missing, prepare them using the application's existing restore process, for example:

```bash
# Explicit application preparation, separate from the scan.
dotnet restore /path/to/service/Service.csproj

java -jar nullguard-cli.jar /path/to/service \
  --languages=csharp --output=nullguard-out/csharp
```

NullGuard does not run restore, MSBuild targets or source generators during scanning. Missing project references, generated/framework assets or restore data produce analysis gaps. The repository fixture lives at `examples/multilang/csharp`.

## Review a finding

The [C# fixture](../../../examples/multilang/csharp/Example.cs) contrasts an unsafe nullable parameter with a guard:

```csharp
public static int Unsafe(string? name) => name.Length;

public static int Guarded(string? name)
{
    if (name is null) return 0;
    return name.Length;
}
```

Confirm the intended missing-value behavior before using a fallback. Check callers, nullable contracts and generated code, add a regression test, then run the project's usual `dotnet test` or equivalent tests and repeat the scan.

## Read the output

Open `nullguard-out/csharp/nullguard-dashboard-latest.html`. C# findings use `NG-CS-`. Inspect `coverage.csharp`, missing dependencies, unresolved symbols, source evidence and confidence in the JSON report.

Exit `0` means completion, not absence of findings. Exit `3` means failure or partial coverage. The C# pack does not provide a stability grade or mandatory finding gate.

## Boundaries

Only SDK-style C# is supported. MSBuild evaluation, source generators, dynamic dispatch and generated sources are not executed, and local/lambda CFG bodies are unsupported. Separate projects do not establish cross-service or cross-language call chains. Read the [shared pack reference](../../../docs/multilang/README.md) for reports, signatures and capability limits.
