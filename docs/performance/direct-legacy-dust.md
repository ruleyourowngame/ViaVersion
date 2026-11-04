# Direct encoding of canonical legacy dust packets

The legacy-fluid allocation capture [BZ9JHt3yHL](https://spark.lucko.me/BZ9JHt3yHL), 2026-11-04 22:22–22:25 UTC at 328 average players, still attributes allocation to particle conversion: `ParticleIdMappings1_13.rewriteParticle` is about 2.75 MiB/s inclusive and its redstone-dust handler about 1.62 MiB/s inclusive. These frames overlap and must not be added. The complete Via transform is about 128.70 MiB/s, but most of that is other traffic. This change targets a measured part of the workload; it cannot establish the requested 40% whole-server reduction by itself.

## Behavior and compatibility

`-Dviaversion.legacyDustFastPath=true` opts in; the default is false. The property is read when a connection is constructed. Only active server-side 1.8 connections, with client/server state PLAY and a known 1.9–26.3 client, qualify. The source must be a complete 37-byte canonical particle body: particle 30, count zero, boolean 0/1 and seven finite floats. Shared, read-only, little-endian and insufficient-capacity buffers fall back unchanged. Non-finite values and every other particle/count use the existing pipeline.

A connection-local plan snapshots the full clientbound path. Built-in handlers explicitly promise the existing count-zero dust behavior through `LegacyDustHandler`; an appended/replaced unmarked handler disables the plan. Unknown transform overrides, custom particle rewriters, mapping implementations or particle types/readers do not opt in automatically. Mapping identities, dust id mappings and block/item classifications, reader identities and pipeline membership are checked again before each direct write. Configuration bridge phases and debug logging force ordinary translation. Later plan invalidation keeps that connection on the ordinary path.

Before accepting a plan, two sample payloads are compared byte for byte against the complete ordinary protocol path, after all callbacks have been proven eligible. The direct encoder preserves the existing red-substitution and color-quantization helper, float-to-double coordinates, packed ARGB and the 26.3 field order. It reads all input primitives before rewriting the same buffer. No payload is shared across players; native/Netty listeners outside Via still receive each real packet. `UserConnectionImpl.legacyDustFastPathPackets()` exposes its event-loop-owned successful packet count.

The wire layouts are intentionally bounded at 26.3. A new version or change to a marked handler requires reviewing its dust behavior and extending the equivalence matrix. Registering a custom handler, reader or protocol remains the supported way to regain ordinary processing; extensions must only apply the marker if they uphold its documented promise. Registrations follow the ordinary pipeline's threading rules.

## Connection task lifetime and outbound ownership

Blueprint's capability path borrows a verified exclusive input until `BukkitEncodeHandler.encode` returns. It does not retain before translation. Netty's enclosing `MessageToMessageEncoder` releases that input; only a copied buffer is released by Via's `finally`. Output retains its own reference. This lets the ref-count-one guard run on the intended outbound path while arbitrary shared input still takes an isolated copy. Success, cancellation and failure ownership tests remain in place, and the actual outbound encoder is tested against ordinary wire output with the fast-path counter asserted for exclusive and shared inputs.

Blueprint block acknowledgements keep at most one main-thread hop or delayed send pending per connection. Arrivals before the hop coalesce into the highest sequence, which is frozen before the two-game-tick ordering wait. Later arrivals wait for a new batch and its own delay, so a callback cannot acknowledge work received after its hop. Duplicate/older sequences schedule nothing. Connection removal invalidates queued batches. This can conservatively delay later acknowledgements by another batch interval; it preserves the minimum ordering delay behind block updates.

The configuration bridge keeps its scheduled deadline future and cancels it on completion, clear or storage removal. Generation checks still protect against a stale callback from an older phase. Cancelling releases the callback's captured connection/state rather than retaining it for the rest of the deadline.

## Validation

The clean JDK 21 shadow build and 95 tests (86 common, 9 Bukkit) pass. `plugin.yml` embeds `5.12.2-arch.8`.

- The Bukkit outbound encoder runs the real protocol path for 1.13, 1.20.5 and 26.3 with/without the ownership capability and with/without sharing. It checks identical wire bytes, fast-path use and reference counts. Acknowledgement tests cover large bursts, late arrivals and removal; deadline tests assert cancellation at every exit.
- For every registered client version from 1.9 through 26.3, 128 deterministic payloads compare enabled and ordinary full connection transforms, including signed zero, maximum finite values, subnormals and random finite bit patterns. Every eligible packet must increment the fast-path counter, so an unnoticed fallback cannot pass the comparison.
- Fallback tests preserve bytes, indexes and reference counts for malformed data, NaN/infinities, sharing, read-only/derived buffers, byte order, capacity, configuration, inactive/pending connections, debug logging and unknown/changed versions.
- Tests exercise custom handlers and readers before and after plan creation, unknown transform overrides, changed particle ids/classifications and changed pipeline membership. Synthetic validation must not invoke custom callbacks.

`LegacyDustAllocationBenchmark` measures the full `UserConnectionImpl.transformClientbound` call, including the guards, with a reused pooled buffer. It writes varying source coordinates, consumes output and asserts that all enabled packets used the direct path. Setup, connection creation and first-plan compilation are outside measurement. Two separate JDK 25 processes, compact object headers, Netty 4.2.9.Final, 150,000 warmup and 300,000 measured packets per variant:

| Client | Ordinary bytes/packet | Direct bytes/packet | Ordinary ns/packet, runs 1 / 2 | Direct ns/packet, runs 1 / 2 |
| --- | ---: | ---: | ---: | ---: |
| 1.12.2 | 56 | 0 | 66.67 / 78.14 | 57.34 / 53.69 |
| 1.13 | 712 | 0 | 305.86 / 292.34 | 74.55 / 79.15 |
| 1.20.5-1.20.6 | 952 | 0 | 2014.16 / 2090.97 | 221.75 / 224.32 |
| 26.2 | 984 | 0 | 2934.21 / 2806.52 | 474.55 / 486.67 |
| 26.3 | 1104 | 0 | 3128.43 / 3038.13 | 506.28 / 532.78 |

Raw measurements and JVM flags are in [legacy-dust-benchmark.json](legacy-dust-benchmark.json). Allocation uses `ThreadMXBean`; timing is not a test threshold. Zero means zero measured steady-state allocation on the transforming thread for this fixture, not zero server allocation.

Run the tests/build with `./gradlew clean :viaversion-common:test :viaversion-bukkit:test :viaversion:shadowJar`. Run the benchmark main `com.viaversion.viaversion.common.LegacyDustAllocationBenchmark` on the common test runtime classpath; the optional first argument is the measured packet count. To reproduce the recorded runtime, replace the compile-time Netty 4.0.20.Final jar on that classpath with Netty 4.2.9.Final common, buffer, transport, resolver, codec-base, codec and handler jars, then use the JDK/flags in the JSON.

## Rollout

Publish a clean artifact from the reviewed merge commit first. Enable the property only on legacy-fluid and rebuild/redeploy with Neo `-f`. Verify the manifest version/hash, preserve the deployed Polar overlay and compare every other artifact hash. Capture warmed allocation with comparable client/game/activity mix, plus CPU, tick latency, memory and protocol errors. Remove the property and redeploy to disable the optimization. The configuration queue fix in arch.7 is independent and should remain deployed.
