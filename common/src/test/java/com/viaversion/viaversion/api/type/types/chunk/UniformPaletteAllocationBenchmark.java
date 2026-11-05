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

import com.sun.management.ThreadMXBean;
import com.viaversion.viaversion.api.minecraft.chunks.DataPaletteImpl;
import java.lang.management.ManagementFactory;
import java.util.function.IntUnaryOperator;

/** Fresh decoded sections, with one or several protocol remaps; no timing gates in CI. */
public final class UniformPaletteAllocationBenchmark {
    private static final IntUnaryOperator REMAP = id -> id + 1;
    private static volatile Object sink;

    private static void run(int count, int stages) {
        for (int i = 0; i < count; i++) {
            DataPaletteImpl palette = new DataPaletteImpl(4096, 1);
            palette.addId(0);
            for (int stage = 0; stage < stages; stage++) {
                palette.replaceIds(REMAP);
            }
            if (palette.idAt(4095) != stages) throw new AssertionError("Bad remap");
            sink = palette;
        }
    }

    public static void main(String[] args) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation accounting unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().getId();
        int count = 200_000;
        for (int stages : new int[]{1, 8}) {
            run(100_000, stages);
            long before = bean.getThreadAllocatedBytes(thread);
            run(count, stages);
            long bytes = bean.getThreadAllocatedBytes(thread) - before;
            System.out.printf("stages=%d bytes/section=%.2f%n", stages, bytes / (double) count);
        }
    }
}
