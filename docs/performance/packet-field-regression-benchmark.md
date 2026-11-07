# Packet-field regression benchmark

The legacy allocation capture `zslMZQSIBG` attributes about 15.84 MiB/s to
`PacketWrapperImpl$FieldBuffer.add` stacks. That is evidence for investigating
field storage, not a prediction that all of those bytes can be removed. These
fixtures measure allocation **and CPU**, including paths an optimization does
not target. No production packet code changes in this benchmark PR.

`PacketFieldForwardingBenchmark` measures complete escaping wrappers with bounded
hints, growth to 4,096 fields, eight full translation stages, partially consumed
stages, overlapping rewinds and a first decode stage with no stored fields.
Partial/reset and single-rewind cases include 63/64/65 and 4,095/4,096/4,097
fields. `overlapRewind` moves eight fields across a one-field gap at 63 and 4,095
fields, exercising the copying path rather than only full-ring cursor moves.
`fullZero` deliberately underestimates demand. Integer payloads use cached boxed
values so storage costs are isolated. The wrapper escapes through a volatile
sink; read checksums prevent unused reads from disappearing. Correctness remains
covered by the separate randomized cursor model and serialized-output tests.

Each case has five warmup and nine measurement batches, reporting medians from
`ThreadMXBean` allocation counters and `nanoTime`. Integers, a warmed allocator
and one thread are synthetic assumptions. These fixtures do not model transport,
compression, arbitrary boxing, multithreaded contention or whole-server latency.
Timing is never a unit-test assertion.

## Compare an unchanged baseline with a candidate

Use identical JDK/GC/Netty settings and alternate process order over several
forks. Separate JVMs preserve the normal monomorphic wrapper call profile;
loading both versions into one caller changes JIT decisions. Build the baseline
once in its own checkout and prepend its **production** classes, retaining the
candidate's fixture bytecode. The benchmark prints the actual wrapper source.
Supply an absolute directory with all baseline production classes and nested
classes; the task rejects a missing `PacketWrapperImpl.class`.

```sh
# In a separate baseline checkout at fdedbc0db (or the candidate's actual base):
./gradlew :viaversion-common:classes

# In the candidate checkout, JDK25 + ZGC + compact headers, Netty4.2.9:
./gradlew :viaversion-common:benchmarkFieldForwarding -PtestNettyVersion=4.2.9.Final
./gradlew :viaversion-common:benchmarkFieldForwarding -PtestNettyVersion=4.2.9.Final \
  -PfieldBaselineClasses=/absolute/baseline/common/build/classes/java/main
# Reverse order for the next fork. Use -PbenchmarkJavaVersion=21 to check JDK21.
```

Compare output checksums and bytes for every case, then CPU distributions.
Account for warmup and fork variance; do not accept a gain in one selected shape
while hiding a repeatable regression in raw decoding or large rewinds. The
optional Netty version property affects test runtime only; shipped dependencies remain unchanged.

## Rejected example: full-ring forwarding

The [archived patch](rejected-full-ring-forwarding.patch) applies to `fdedbc0db`
and is **not applied to production sources**. It advances a full field ring's
head instead of clearing and re-adding the same field. Its tests pass, but the
larger compiled `passthrough` method can prevent caller inlining. Local JDK25.0.4.1
on aarch64 with ZGC, compact headers and Netty4.2.9 showed:

| Case | Original ns/packet | Rejected ns/packet | Allocated bytes, both |
| --- | ---: | ---: | ---: |
| Full replay, 4 fields | 156.7 | 92.5 | 168 |
| Full replay, 4,096 fields | 165,786.7 | 97,038.1 | 131,272 |
| Raw decode, 4 fields | 26.6 | 36.5 | 168 |
| Raw decode, 4,096 fields | 25,343.8 | 26,337.6 | 131,272 |

These were captured with the initial fixture at `14b49a01e`, before adding the
non-power-of-two and eight-field overlap cases. They are medians across three
JVM forks, each reporting its nine-batch median.
The [complete data](rejected-full-ring-forwarding-results.csv) includes individual
fork timings; the small raw cases have noticeable compilation variance. No
allocation reduction occurred. The raw-path cost caused rejection despite the
replay gains. Reproduce only in an isolated experimental checkout: apply the
archived patch, run the tests and compare both builds with the same fixture.
This result is local fixture evidence, not a deployed server improvement.
