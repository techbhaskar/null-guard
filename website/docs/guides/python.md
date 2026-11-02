# Python developer guide

Use the Python pack to investigate nullable values, unsafe attribute access and calls in Python source. Findings include source locations and origin/guard evidence. The pack is **experimental and report-only**; inspect findings before changing application behavior.

## Requirements and installation

Use the signed NullGuard 2.0.0 executable CLI with Java 21+, Python 3.11+ and GPG/GPGV for signed-pack installation. Follow the [CLI setup guide](../getting-started.md) to obtain and verify the distribution. In the commands below, `nullguard-cli.jar` refers to that verified executable.

```bash
python3 --version
java -jar nullguard-cli.jar packs install python
java -jar nullguard-cli.jar packs list
```

The pack uses the installed Python parser, so supported syntax depends on that interpreter. It does not import your application's modules or execute decorators.

## Analyze your project

Pass the project directory, including its source tree and available configuration such as `pyproject.toml` or `requirements.txt`:

```bash
java -jar nullguard-cli.jar /path/to/python-project \
  --languages=python --output=nullguard-out/python
```

For a runnable source fixture from a NullGuard checkout, use `examples/multilang/python` as the project path. No application execution is required for the scan. Dependency preparation and tests remain separate developer actions.

## Review a finding

This [repository fixture](../../../examples/multilang/python/app.py) contrasts a nullable argument dereference with a guard:

```python
def unsafe(name: str | None):
    return name.count("a")

def guarded(name: str | None):
    if name is None:
        return 0
    return name.count("a")
```

Returning zero is the fixture's chosen missing-value behavior. In a real application, check the caller's contract before substituting a default. Add a regression test for the missing-value path and the normal path, then rerun your tests and the same scan.

## Read the output

Open `nullguard-out/python/nullguard-dashboard-latest.html` or inspect `nullguard-report-latest.json`. Python rule IDs use `NG-PY-`. Review the finding's confidence and evidence alongside `coverage.python`, especially unresolved symbols, missing dependencies and failed/unsupported files.

Exit `0` means the selected projects completed; it does not mean there are no findings. Exit `3` means failure or partial analysis, and retained results must remain visibly incomplete. There is no Python stability grade or finding-enforcement gate.

## Boundaries

Dynamic attributes, imports and decorator execution are not inferred by running application code. Local and directly resolved source functions are supported within the pack's static model; unknown contracts do not establish safety. See the [shared report and pack reference](../../../docs/multilang/README.md) for budgets, advisory inputs and capability limits.
