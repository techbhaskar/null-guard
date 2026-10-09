# Pipeline benchmark observations

Measured during the 2026-10-09 hardening pass on Windows 10, Java 21.0.9, with 4 available processors. These are observations from a synthetic workload, not release performance limits.

| Source methods | Median pipeline time | Three measured samples |
| ---: | ---: | --- |
| 10 | 223 ms | 178, 223, 345 ms |
| 100 | 892 ms | 791, 892, 1041 ms |
| 500 | 2,444 ms | 1,935, 2,444, 2,481 ms |

The original workload contains guarded reference parameters and return values in a single class, one warmup per size, and three full pipeline measurements. It includes parsing, summaries, graph/scoring, suggestions, and exports. JaCoCo instrumentation is present when run through the normal verification build. It does not simulate framework proxies, dependency-heavy services, or deep recursive call graphs.

Run `mvn verify -Dnullguard.benchmark=true` (quote the `-D` argument in PowerShell). Raw measurements and environment details are generated at `nullguard-maven-plugin/target/benchmark.json`. Sampled heap usage is recorded without forcing garbage collection and is not peak retained memory.

Future comparisons should use the same machine, JVM, instrumentation settings, and workload. Add larger representative corpora before adopting runtime or memory budgets.

## Extended workload through 50,000 methods

The harness now accepts `nullguard.benchmark.sizes=10,100,500,1000,5000,10000,25000,50000`. It generates up to 500 source classes with at most 100 methods per class. This layout differs from the initial single-class baseline above, so timings are not directly comparable. The default remains the small smoke workload; see the README for the full command and manual CI dispatch.

The extended report records the requested sizes and `running`/`completed` status, one warmup and three measured samples by default, chronological sample times, median, sampled heap, maximum heap, and environment metadata. Every run asserts the expected number of analyzed methods. Completed sizes are checkpointed. Only a `completed` report containing the 50,000-method measurement demonstrates completion of that synthetic size; it does not demonstrate arbitrary real-world 50k-method support.

### Completed local extended run

Measured on 2026-10-09 using Windows 10, Java 21.0.9, 4 available processors, a 6 GiB maximum JVM heap, and JaCoCo instrumentation. The test passed with `status: completed`, one warmup and three measured runs at every size. Total test time, including fixture generation and warmups, was 802 seconds.

| Source methods | Source classes | Median pipeline time | Samples in execution order |
| ---: | ---: | ---: | --- |
| 10 | 1 | 257 ms | 247, 257, 332 ms |
| 100 | 1 | 674 ms | 708, 674, 582 ms |
| 500 | 5 | 2,469 ms | 2,416, 2,469, 3,041 ms |
| 1,000 | 10 | 3,907 ms | 3,907, 4,221, 1,696 ms |
| 5,000 | 50 | 9,297 ms | 9,297, 9,811, 7,904 ms |
| 10,000 | 100 | 16,376 ms | 16,487, 15,592, 16,376 ms |
| 25,000 | 250 | 50,779 ms | 45,909, 50,779, 51,632 ms |
| 50,000 | 500 | 118,228 ms | 110,025, 118,228, 131,230 ms |

The 50k workload completed without a test failure. This establishes a measured synthetic baseline, not a runtime service-level guarantee. The guarded methods are independent: this run does not exercise dense call graphs, large dependency trees, endpoint reachability, recursive contract propagation, or many emitted findings. The report's final sampled heap value is affected by garbage collection and must not be interpreted as peak memory or a minimum heap requirement. Hosted CI has not yet reproduced this local run.
