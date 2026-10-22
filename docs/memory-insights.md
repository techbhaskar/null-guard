# Memory and retention insights

Newly generated dashboards include a Memory & retention tab. The JSON report carries the same information in `memoryInsights`.

The allocation chart counts explicit object and array creation expressions in available method bodies. The loop count is a subset of these counts, not an additional allocation total. Counts describe source sites, not execution frequency or bytes. Constructor expansion can include initializer expressions in multiple modeled bodies.

The retention map shows static collection fields, ThreadLocal fields, and allocation sites nested inside loops. Each candidate includes its source location and review guidance. Loop locations identify the enclosing method. These syntax signals do not prove unbounded growth, missing cleanup, or a memory leak. Collection subclasses, aliases, library allocations, asynchronous lifetimes and arbitrary runtime retention are not resolved by this inventory. Missing method bodies are reported separately.

Heap used, GC activity and confirmed leaks remain unmeasured. For those measurements, capture runtime evidence such as JFR recordings or heap dumps and inspect live-set growth and GC-root retention paths. This feature does not currently import runtime recordings.
