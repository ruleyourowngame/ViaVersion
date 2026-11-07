package com.viaversion.viaversion.protocols.v1_12_2to1_13.storage;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockConnectionStorageTest {
    @Test
    void verticalColumnCanBeCompletelyRemoved() throws Exception {
        BlockConnectionStorage storage = new BlockConnectionStorage();
        for (int y = 0; y < 16; y++) storage.store(7, y, 9, y + 1);
        for (int y = 0; y < 16; y++) assertEquals(y + 1, storage.get(7, y, 9));
        for (int y = 0; y < 16; y++) storage.remove(7, y, 9);
        assertTrue(sections(storage).isEmpty());
    }

    @Test
    void replacingNonzeroStatesDoesNotKeepAnEmptySectionAlive() throws Exception {
        BlockConnectionStorage storage = new BlockConnectionStorage();
        storage.store(1, 2, 3, 10);
        storage.store(1, 2, 3, 20);
        storage.store(1, 2, 3, 30);
        storage.remove(1, 2, 3);
        assertTrue(sections(storage).isEmpty());
        assertEquals(0, storage.get(1, 2, 3), "cached section must also be cleared");
        storage.store(1, 2, 3, 40);
        assertEquals(40, storage.get(1, 2, 3));
    }

    @Test
    void replacementStormCannotOverflowOccupancyAndDiscardOtherLiveBlocks() throws Exception {
        BlockConnectionStorage storage = new BlockConnectionStorage();
        storage.store(0, 0, 0, 1);
        storage.store(1, 0, 0, 42);
        for (int i = 0; i < 65_535; i++) storage.store(0, 0, 0, 2 + (i & 1));
        storage.remove(0, 0, 0);
        assertEquals(42, storage.get(1, 0, 0), "replacement count must not wrap and remove live neighbors");
        storage.remove(1, 0, 0);
        assertTrue(sections(storage).isEmpty());
    }

    @Test
    void denseSectionAndAllCoordinateBoundariesRoundTrip() {
        for (int base : new int[]{-32, -16, 0, 16, 32}) {
            BlockConnectionStorage storage = new BlockConnectionStorage();
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                storage.store(base + x, base + y, base + z, 1 + (y << 8 | x << 4 | z));
            }
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                assertEquals(1 + (y << 8 | x << 4 | z), storage.get(base + x, base + y, base + z));
            }
            storage.unloadChunk(base >> 4, base >> 4);
            if (base >= 0) assertEquals(0, storage.get(base, base, base));
        }
    }

    @Test
    void randomizedUpdatesRemovalsAndCacheSwitchesMatchAMapOracle() {
        BlockConnectionStorage storage = new BlockConnectionStorage();
        Map<Position, Integer> expected = new HashMap<>();
        Random random = new Random(492471);
        for (int i = 0; i < 100_000; i++) {
            Position p = new Position(random.nextInt(64) - 32, random.nextInt(64) - 32, random.nextInt(64) - 32);
            int state = random.nextInt(4) == 0 ? 0 : random.nextInt(16383) + 1;
            if (state == 0) { storage.remove(p.x, p.y, p.z); expected.remove(p); }
            else { storage.store(p.x, p.y, p.z, state); expected.put(p, state); }
            assertEquals(expected.getOrDefault(p, 0), storage.get(p.x, p.y, p.z));
        }
        expected.forEach((p, value) -> assertEquals(value.intValue(), storage.get(p.x, p.y, p.z)));
        storage.clear();
        expected.keySet().forEach(p -> assertEquals(0, storage.get(p.x, p.y, p.z)));
    }

    @Test
    void unloadingOneSectionOrChunkDoesNotAffectNeighbors() throws Exception {
        BlockConnectionStorage storage = new BlockConnectionStorage();
        storage.store(-1, 3, -1, 5);
        storage.store(-1, 20, -1, 6);
        storage.store(0, 3, -1, 7);
        storage.unloadSection(-1, 0, -1);
        assertEquals(0, storage.get(-1, 3, -1));
        assertEquals(6, storage.get(-1, 20, -1));
        storage.unloadChunk(-1, -1);
        assertEquals(0, storage.get(-1, 20, -1));
        assertEquals(7, storage.get(0, 3, -1));
        storage.clear();
        assertTrue(sections(storage).isEmpty());
    }

    private static Map<?, ?> sections(BlockConnectionStorage storage) throws Exception {
        Field field = BlockConnectionStorage.class.getDeclaredField("blockStorage");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(storage);
    }

    private record Position(int x, int y, int z) { }
}
