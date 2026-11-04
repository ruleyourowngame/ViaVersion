# Entity metadata event allocation

The legacy-fluid allocation capture [nVkhC4147l](https://spark.lucko.me/nVkhC4147l), taken on 2026-11-04 with 322 average players, attributed about 16.17 MiB/s of self allocation to `EntityRewriter.handleEntityData`. This is evidence of a hotspot, not a prediction that every byte in that frame can be removed.

An initial fixture showed that the JVM already eliminated the filter iterator allocation. No iterator rewrite was applied. The remaining event wrapper is required for arbitrary callbacks, which may retain it or add extra metadata. Reusing an event across fields or packets would change those callbacks' behavior.

`EntityDataHandler.Direct` is an explicit opt-in for transformations that only need the connection, current entity type and data entry. It returns cancellation instead of receiving an event. The rewriter creates an event only when an ordinary callback matches. The default interface bridge still supports callers that invoke a direct handler through the existing event-based `handle` method. Built-in type mapping, index shifts, cancellation and item/block/particle/component rewrites use the direct path. Other callbacks keep per-entry events.

Cancellation updates any event already seen by a custom callback, and extra metadata is still appended after the entry's filter loop. Exceptions keep the existing logging/removal behavior. Filter order and the original iterator semantics remain unchanged. No events, entries or handler contexts are pooled.

## Validation

- 69 common and 5 Bukkit tests passed; the clean shadow JAR build embeds version `5.12.2-arch.6` and the new direct handler interface.
- 1,000 deterministic randomized pipelines compare direct execution against ordinary event dispatch of the same operations.
- Explicit tests cover index shifts, removal, type mapping, skipped types for tracked/untracked entities, cancellation visible through retained callbacks, extra entries, exceptions, legacy interface calls, connection identity, entity type and sent-metadata tracking.
- `EntityDataDirectBenchmark` uses twelve prebuilt metadata entries, built-in transforms and one ordinary callback which retains its event. The reference fixture wraps the direct operations in ordinary event callbacks to force the existing event-dispatch path. Fixture setup and entry allocation are outside measurement.

JDK 25.0.4.1, compact object headers, 256 MiB initial heap / 512 MiB maximum heap, 100,000 warmup lists and 1,000,000 measured lists:

| Run | Event dispatch bytes/list | Direct dispatch bytes/list | Event dispatch ns/list | Direct dispatch ns/list |
| --- | ---: | ---: | ---: | ---: |
| 1 | 480 | 40 | 271.49 | 248.34 |
| 2 | 480 | 40 | 284.40 | 263.80 |

Run the benchmark main class on the common test runtime classpath. Allocation is measured with `ThreadMXBean`; timing is illustrative and not a test threshold. The ordinary callback still receives a distinct event each time. Real traffic with more custom callbacks saves less. The numbers do not establish a whole-server allocation or CPU reduction, and deployment still requires allocation, tick latency and CPU checks. The further 40% allocation target remains open.
