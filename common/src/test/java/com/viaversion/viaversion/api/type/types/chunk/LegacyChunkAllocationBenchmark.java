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
import io.netty.buffer.ByteBuf;
import java.lang.management.ManagementFactory;

/** Standalone full-decode fixture; no performance thresholds in unit tests. */
public final class LegacyChunkAllocationBenchmark {
    private static volatile Object sink;
    private static void run(ByteBuf input, boolean copy, int count) {
        for (int i = 0; i < count; i++) {
            input.readerIndex(0);
            sink = copy ? LegacyChunkBufferTest.referenceBulk(input) : BulkChunkType1_8.TYPE.read(input);
        }
    }
    public static void main(String[] args) {
        final int count = args.length == 0 ? 500 : Integer.parseInt(args[0]);
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        final long thread = Thread.currentThread().getId();
        for (boolean sky : new boolean[]{false, true}) {
            final ByteBuf input = LegacyChunkBufferTest.bulk(sky, new int[]{1, 0x81, 0x5555, 0xffff});
            try {
                for (boolean copy : new boolean[]{true, false}) {
                    run(input, copy, 150);
                    final long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                    run(input, copy, count);
                    final long elapsed = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(thread) - before;
                    System.out.printf("sky=%s copying=%s bytes/packet=%.2f ns/packet=%.2f%n", sky, copy, bytes/(double) count, elapsed/(double) count);
                }
            } finally { input.release(); }
        }
    }
}
