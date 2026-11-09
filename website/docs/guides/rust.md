# Rust developer guide

Use the Rust pack to investigate panic risks such as unguarded `unwrap`/`expect` on established standard `Option` and `Result` values. This is **experimental, report-only** source analysis; it does not replace the Rust compiler or borrow checker.

## Requirements and installation

Use a verified NullGuard 2.0.1 executable CLI, Java 21+, a compatible stable Cargo toolchain and GPG/GPGV for signed-pack installation. The pack uses locked `syn` and `cargo_metadata` dependencies without `rustc_private`.

```bash
cargo --version
java -jar nullguard-cli.jar packs install rust
java -jar nullguard-cli.jar packs list
```

See [CLI setup](../getting-started.md) to obtain and verify the distribution. Choose the signed pack for your platform.

## Analyze your crate

Supply a crate or workspace directory containing its `Cargo.toml`, lockfile and sources:

```bash
java -jar nullguard-cli.jar /path/to/rust-project \
  --languages=rust --output=nullguard-out/rust
```

Cargo metadata is obtained offline with locked dependency resolution. Prepare missing dependency metadata using the project's existing process before scanning; analysis does not fetch dependencies or build/run the application. Use `examples/multilang/rust` for the repository fixture.

## Review a finding

The [Rust fixture](../../../examples/multilang/rust/src/main.rs) contrasts an unguarded unwrap with a guard:

```rust
fn unsafe_unwrap(value: Option<i32>) -> i32 { value.unwrap() }

fn guarded(value: Option<i32>) -> i32 {
    if value.is_none() { return 0; }
    value.unwrap()
}
```

Here the guarded example explicitly chooses zero for `None`. In an application, review whether to propagate `Option`/`Result`, return an error, pattern-match, or use a deliberate default. Add a regression test for both variants, run the project's tests (such as `cargo test --locked`) and rerun the scan.

## Read the output

Open `nullguard-out/rust/nullguard-dashboard-latest.html`. Findings use `NG-RS-`. Inspect `coverage.rust`, identity-resolution evidence, missing dependencies and unresolved targets before treating an unwrap as safe or unsafe.

Exit `0` means completion, not zero findings. Exit `3` means failure or partial coverage. The Rust pack does not produce a stability grade or mandatory finding gate.

## Boundaries

There is no macro expansion, complete trait-dispatch resolution, raw-pointer verification or borrow verification. Standard `Option`/`Result` identity must be established for unwrap rules; ambiguous imports, aliases and unknown targets remain unknown. The coding-agent skill's five-language helper does not currently select Rust; invoke the CLI directly. Read the [shared pack reference](../../../docs/multilang/README.md) for analysis budgets and capability limits.
