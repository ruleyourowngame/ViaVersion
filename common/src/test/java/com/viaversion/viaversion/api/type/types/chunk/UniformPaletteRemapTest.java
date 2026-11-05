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
package com.viaversion.viaversion.api.type.types.chunk;

import com.viaversion.viaversion.api.minecraft.chunks.DataPaletteImpl;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UniformPaletteRemapTest {
    @Test
    void remappedUniformSectionCanGrowAndFindOldAndNewIds() {
        for (boolean inverseAlreadyBuilt : new boolean[]{false, true}) {
            DataPaletteImpl palette = new DataPaletteImpl(4096, 1);
            palette.addId(7);
            if (inverseAlreadyBuilt) palette.setIdAt(0, 7);
            AtomicInteger calls = new AtomicInteger();
            palette.replaceIds(id -> { calls.incrementAndGet(); return id + 9; });
            assertEquals(1, calls.get());
            for (int i = 0; i < 4096; i++) assertEquals(16, palette.idAt(i));
            palette.setIdAt(0, 7);
            palette.setIdAt(1, 16);
            assertEquals(2, palette.size());
            assertEquals(7, palette.idAt(0));
            assertEquals(16, palette.idAt(1));
            assertEquals(16, palette.idAt(4095));
            palette.replaceIds(id -> 0);
            assertEquals(1, palette.size());
            for (int i = 0; i < 4096; i++) assertEquals(0, palette.idAt(i));
        }
    }

    @Test
    void repeatedRemapsAndLaterPaletteEditsKeepTheInverseConsistent() {
        DataPaletteImpl palette = new DataPaletteImpl(64, 1);
        palette.addId(20);
        for (int i = 0; i < 8; i++) palette.replaceIds(id -> id - 1);
        assertEquals(12, palette.idAt(63));
        palette.setIdAt(0, 12);
        palette.replaceIds(id -> -1);
        palette.setIdAt(1, 12);
        assertEquals(2, palette.size());
        assertEquals(-1, palette.idAt(0));
        assertEquals(12, palette.idAt(1));
        assertEquals(-1, palette.idAt(63));
    }

    @Test
    void emptyPaletteDoesNotCallMapperAndFailedMappingKeepsItsId() {
        DataPaletteImpl empty = new DataPaletteImpl(64, 1);
        empty.replaceIds(id -> { throw new AssertionError("Empty palette called mapper"); });
        DataPaletteImpl palette = new DataPaletteImpl(64, 1);
        palette.addId(7);
        assertThrows(IllegalStateException.class, () -> palette.replaceIds(id -> { throw new IllegalStateException("test"); }));
        assertEquals(7, palette.idAt(63));
    }
}
