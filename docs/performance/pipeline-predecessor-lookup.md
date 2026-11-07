# Pipeline predecessor lookup

The Nov 7 legacy-fluid allocation profile `zslMZQSIBG` attributes about 2.24 MiB/s
of `Arrays.copyOf` to `PipelineUtil.getPreviousContext` through Netty's pipeline
name snapshots. That is one sampled stack, not the complete cost of the lookup.

Netty has no public predecessor accessor. Resolve its internal volatile `prev`
field once through a method handle, then read the link for each lookup. Verify
that both target and predecessor are still registered in the supplied pipeline.
No channel contexts or handler topology are cached.

If access is unavailable, the field type or volatility differs, the context is
from an unsupported implementation, or membership validation fails, retain the
public name-snapshot lookup. The existing first-handler boundary behavior, missing
names, null names, case sensitivity and internal tail behavior remain covered by
comparison against the original map-based reference. Tests also simulate a
removed target, wrapped contexts and a disabled accessor.

## Reproduce

Use JDK 25. The default test dependency is Netty 4.0.20.Final; `testNettyVersion`
overrides only the common test runtime's `netty-all` dependency.

```sh
./gradlew :viaversion-common:test :viaversion-bukkit:test :viaversion:shadowJar
./gradlew :viaversion-common:test -PtestNettyVersion=4.1.118.Final
./gradlew :viaversion-common:test -PtestNettyVersion=4.2.9.Final
./gradlew :viaversion-common:benchmarkPipelineLinks -PtestNettyVersion=4.2.9.Final
```

The benchmark compares the unchanged immediate-parent names-based algorithm with
the guarded link lookup. It warms both paths, alternates measurement order, keeps
results observable and reports the median of seven rounds. It checks early,
middle and late targets in pipelines of 8, 16 and 32 handlers. Allocation uses
`ThreadMXBean`; timings are illustrative, not unit-test thresholds.

Example local aarch64 JDK 25 result with Netty 4.2.9.Final, last public target:

| Handlers | Names bytes/call | Link bytes/call | Names ns/call | Link ns/call |
| --- | ---: | ---: | ---: | ---: |
| 8 | 56 | 0 | 42.5 | 36.0 |
| 16 | 240 | 0 | 76.7 | 66.3 |
| 32 | 392 | 0 | 180.2 | 155.6 |

The fallback retains its existing allocation cost. These fixture results do not
establish live CPU savings, player latency improvements or a fleet-wide
allocation reduction. Production comparison needs a reviewed release and
comparable traffic on the deployed x86 machines.
