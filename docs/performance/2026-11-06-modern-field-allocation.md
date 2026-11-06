# Modern-fluid field storage and float allocation

The native production allocation capture [TQfDtIx5cC](https://spark.lucko.me/TQfDtIx5cC)
on Grid build 143 and ViaVersion arch.10 records 0.9146 GiB/s at 164.33 sampled
average players, or 5.699 MiB/s/player. Exclusive `FieldBuffer.add` allocations
account for 37.52 MiB/s; 33.36 MiB/s originate in ViaBackwards' 26.3→26.2 particle
writer. `Float.valueOf` accounts for 29.65 MiB/s across read and write paths.

Connection-local hints learn peak live field count per source packet ID and
direction. On a later packet, the first field array is sized once to that bounded
hint instead of growing 4→8→16 fields. Hints are discarded across source-state
changes and shrink after a smaller packet. They never retain or share field
values, buffers, wrappers or recipient payloads. Metadata is limited to two
256-entry byte arrays per connection; IDs outside the table use ordinary growth.
The initial storage hint is capped at 64 fields, while larger packets retain
normal growth and all existing ring/rewind semantics. No array is allocated until
a field is written. The extra peak-demand counter adds overhead to each wrapper;
measure the net effect, not just removed resize arrays.

Boxed float reads reuse only the immutable representations of +0, -0 and 1.
Signed zero remains distinct, all other values follow normal boxing, and the
primitive APIs are unchanged. This does not remove boxes created by callers
before `wrapper.write`, nor does every sampled Float allocation match these
three values.

Run:

```sh
./gradlew :viaversion-common:test :viaversion:shadowJar
./gradlew :viaversion-common:benchmarkAdaptiveFields
```

All 100 common tests pass, including the all-version dust matrix, randomized field
cursor/rewind tests, original-buffer transformations in both directions,
out-of-range IDs, state transitions, shrinking hints, and 100,000 random float
bit patterns plus explicit signed-zero/NaN payload cases. The universal jar builds.

The standalone fixture replays 15 integer fields over eight protocol stages and
escapes the wrapper to a volatile sink. Local aarch64 JDK 25 allocation counters
measured 352 bytes/packet with ordinary growth including the new demand counter,
and 224 with a learned 16-field hint. A separate clean checkout of baseline `a2843261e`, running the existing
`PacketFieldStorageBenchmark` with 15 fields and the same eight stages, measured
344 bytes/packet. The 224-byte result is 34.9% below that baseline, including
the new counter overhead. These are separate JVMs on the same local JDK.

A second fixture reads twelve zero/unit floats into an escaping sink: normal
boxing allocates 192 bytes/packet, cached reads allocate zero. This intentionally
isolates common values and is not an estimate for arbitrary float distributions.
Live totals must be measured after deployment at a comparable workload.
