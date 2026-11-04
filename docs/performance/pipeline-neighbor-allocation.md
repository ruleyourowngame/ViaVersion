# Pipeline neighbor lookup allocations

The legacy-fluid allocation capture [vfLU6lWI9Y](https://spark.lucko.me/vfLU6lWI9Y) on 2026-11-04 attributed about 3.34 MiB/s to `PipelineUtil.getPreviousContext`. Its dominant children were `DefaultChannelPipeline.toMap` and linked-map key iteration, reached while sending translated inventory packets back toward the server. This is a small part of total server allocation, not a whole-server reduction claim.

`getPreviousContext` only needs ordered handler names. Use `pipeline.names()` and resolve the predecessor from that snapshot, rather than creating a linked map and one node per handler. Resolve the target context as well: Netty's names snapshot contains its internal tail, which `toMap` excludes and `context(String)` does not expose. No contexts or handler names are cached across calls.

The differential test compares the old map implementation after 200 deterministic additions, removals and replacements, and covers empty pipelines, exact case matching, absent/null targets, the internal tail and the existing first-handler exception. It passed on Netty 4.0.20.Final, 4.1.100.Final and 4.2.9.Final. As before, callers should serialize pipeline changes on the event loop; these snapshots do not make concurrent mutation atomic. A target removed before its context lookup returns null.

Standalone `PipelineLookupBenchmark` uses the common test runtime classpath, JDK 21, 100,000 warmup lookups and 1,000,000 measured lookups, with `ThreadMXBean` allocation accounting and a volatile result sink. It measures the last of 8/16/32 configured handlers in an EmbeddedChannel. The same allocation results were observed on all three Netty versions in one local run:

| Handlers | Map bytes/lookup | Names bytes/lookup |
| --- | ---: | ---: |
| 8 | 488 | 56 |
| 16 | 952 | 240 |
| 32 | 1864 | 392 |

These are fixture measurements. Escape analysis can change the measured allocation, and latency/CPU results depend on workload. The 40% legacy-fluid allocation target requires other changes and live verification.
