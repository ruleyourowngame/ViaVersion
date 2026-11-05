# Uniform chunk palette remapping

`DataPaletteImpl.replaceIds` previously created an inverse hash map even when the palette held
one ID. Such a palette cannot contain a mapping collision. Remap the ID directly and retain the
lazy inverse lookup. If a coordinate write already created the inverse map, update it so later
edits still find the new ID. Multi-entry palettes keep the existing collision and compaction path.

The live allocation sample [axlYLlcylS](https://spark.lucko.me/axlYLlcylS), at an average 505
players, attributed about 147 MiB/s inclusive to Via translation. This change addresses one
chunk-remapping allocation source; that inclusive number is not the expected saving.

Run `./gradlew :viaversion-common:test :viaversion-bukkit:test
:viaversion-common:benchmarkUniformPalette` with JDK 21. The standalone benchmark creates fresh
4096-cell uniform sections, then applies one or eight protocol-style remaps. Warmup precedes
thread-allocated-byte measurement; palettes escape through a volatile sink. There are no timing
or allocation thresholds in unit tests.

The local fixture measured 232 bytes per section before the fast path and 104 bytes after it,
for both one and eight remaps. That saves 128 bytes in this fixture. It does not measure whole
chunk decoding, network throughput, live allocation reduction or the 40% server target.

Tests cover later coordinate writes with an existing or absent inverse map, subsequent palette
growth and collision collapse, repeated block/biome-sized remaps, empty palettes and mapper
failure. Release version: `5.12.2-arch.10`.
