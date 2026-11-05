# Decode legacy chunk payloads without intermediate copies

Allocation capture [LLqITLyTfm](https://spark.lucko.me/LLqITLyTfm), 2026-11-04 at 358 average players, attributes about 14.36 MiB/s of self allocation to `BulkChunkType1_8.ChunkBulkSection.<init>`. Each bulk chunk allocates a byte array, copies its full payload and wraps the array for deserialization. Single-chunk decoding performs the same intermediate copy.

Both normal paths now deserialize synchronously from a bounded borrowed `readSlice`. Bulk metadata uses three small primitive arrays instead of per-chunk headers with payload arrays. The public byte-array deserialize method and eager `ChunkBulkSection` helper remain compatible; the byte-array wrapper is now released in a finally block on failure too. No decoded chunk retains the input or a view: block palettes, light arrays and biome arrays still own their data.

The slice covers the complete declared payload, so a decoder cannot read into a following packet. Its parent has already advanced over that payload, including trailing bytes, just as the previous copy did. Single-chunk unload detection and invalid-data fallback remain unchanged. Bulk zero-section chunks still decode biome data instead of becoming unload packets. There is no cross-packet cache or pooling.

## Validation

101 tests pass (92 common, 9 Bukkit), and the clean shadow build embeds 5.12.2-arch.9. Differential tests compare a copying bulk reference over 120 deterministic batches with sparse/dense masks and both light modes. Single-chunk tests cover full/partial/unload cases and ignored trailing payload bytes. Tests overwrite/release input after decoding, exercise direct/read-only/composite/shared buffers, check caller ownership and verify truncated data cannot consume a following packet. The public eager helper is also covered.

`LegacyChunkAllocationBenchmark` runs full decoding with four chunks per batch, masks 1/0x81/0x5555/0xffff and 128 block states. Input construction is outside measurement; each decoded result escapes through a volatile sink. It compares the copying bulk reference against the new read path in two JDK 25 processes, Netty 4.2.9, compact headers, 256 MiB initial/512 MiB maximum heap, 150 warmup and 500 measured batches:

| Skylight | Copy bytes/batch | Borrowed bytes/batch | Copy ns/batch, runs1/2 | Borrowed ns/batch, runs1/2 |
| --- | ---: | ---: | ---: | ---: |
| False | 632024.85 | 354328.00 | 459991.50 / 481560.33 | 478999.83 / 471470.33 |
| True | 743480.00 | 410488.00 | 449930.58 / 438909.25 | 487144.75 / 451568.58 |

The fixture measures roughly 44–45% less allocation for these chunk batches. CPU timing is mixed; no CPU improvement is claimed. Raw results are in [legacy-chunk-benchmark.json](legacy-chunk-benchmark.json). Run the benchmark main `com.viaversion.viaversion.api.type.types.chunk.LegacyChunkAllocationBenchmark` on the common test runtime classpath. Its optional argument is the measured batch count; replace the compile-time Netty 4.0 jar with 4.2.9 common/buffer/transport/resolver/codec-base/codec/handler jars to reproduce the recorded runtime.

This targets a component, not the requested 40% whole-server reduction. Review/merge, clean-build merged source and publish arch.9 before a forced legacy-fluid rebuild. Verify artifact hashes and preserve the Polar overlay; capture warmed allocations and check CPU, tick latency, memory and protocol errors. A full rollback uses the previous known-good arch.8 image; there is no per-server toggle for this decoder change.
