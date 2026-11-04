/*
 * This file is part of ViaVersion - https://github.com/ViaVersion/ViaVersion
 * Copyright (C) 2016-2026 ViaVersion and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.viaversion.viaversion.util;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConversionCacheTest {

    @Test
    void hitsDoNotRecompute() {
        final ConversionCache<String, String> cache = new ConversionCache<>(4);
        final AtomicInteger calls = new AtomicInteger();

        Assertions.assertEquals("A", cache.get("a", key -> { calls.incrementAndGet(); return "A"; }));
        Assertions.assertEquals("A", cache.get("a", key -> { calls.incrementAndGet(); return "other"; }));
        Assertions.assertEquals(1, calls.get());
    }

    @Test
    void previousGenerationIsPromotedThenEvicted() {
        final ConversionCache<Integer, Integer> cache = new ConversionCache<>(2);
        final AtomicInteger calls = new AtomicInteger();

        // Filling past capacity rotates: 0..2 become the previous generation.
        for (int i = 0; i < 3; i++) {
            cache.get(i, key -> { calls.incrementAndGet(); return key; });
        }
        Assertions.assertEquals(3, calls.get());

        // A hit in the previous generation is served without recomputing and copied forward.
        Assertions.assertEquals(0, cache.get(0, key -> { calls.incrementAndGet(); return -1; }));
        Assertions.assertEquals(3, calls.get());

        // Two more rotations drop everything not touched since.
        for (int i = 10; i < 16; i++) {
            cache.get(i, key -> key);
        }
        Assertions.assertEquals(-1, cache.get(1, key -> { calls.incrementAndGet(); return -1; }));
        Assertions.assertEquals(4, calls.get());
    }

    @Test
    void nullsAndExceptionsAreNotCached() {
        final ConversionCache<String, String> cache = new ConversionCache<>(4);
        final AtomicInteger calls = new AtomicInteger();

        Assertions.assertNull(cache.get("a", key -> { calls.incrementAndGet(); return null; }));
        Assertions.assertThrows(IllegalStateException.class, () -> cache.get("a", key -> {
            calls.incrementAndGet();
            throw new IllegalStateException();
        }));
        Assertions.assertEquals("A", cache.get("a", key -> { calls.incrementAndGet(); return "A"; }));
        Assertions.assertEquals(3, calls.get());
    }
}
