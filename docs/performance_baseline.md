# Pipeline benchmark observations

Measured during the 2026-10-09 hardening pass on Windows 10, Java 21.0.9, with 4 available processors. These are observations from a synthetic workload, not release performance limits.

| Source methods | Median pipeline time | Three measured samples |
| ---: | ---: | --- |
| 10 | 223 ms | 178, 223, 345 ms |
| 100 | 892 ms | 791, 892, 1041 ms |
| 500 | 2,444 ms | 1,935, 2,444, 2,481 ms |

The workload contains guarded reference parameters and return values, one warmup per size, and three full pipeline measurements. It includes parsing, summaries, graph/scoring, suggestions, and exports. JaCoCo instrumentation is present when run through the normal verification build. It does not simulate framework proxies, dependency-heavy services, deep recursive call graphs, or 50,000-method projects.

Run `mvn verify -Dnullguard.benchmark=true` (quote the `-D` argument in PowerShell). Raw measurements and environment details are generated at `nullguard-maven-plugin/target/benchmark.json`. Sampled heap usage is recorded without forcing garbage collection and is not peak retained memory.

Future comparisons should use the same machine, JVM, instrumentation settings, and workload. Add larger representative corpora before adopting runtime or memory budgets.
