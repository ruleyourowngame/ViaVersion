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
package com.viaversion.viaversion.common;

import com.sun.management.ThreadMXBean;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.CodecException;
import java.lang.management.ManagementFactory;

/** Full connection transform fixture; setup and first-plan compilation are outside measurement. */
public final class LegacyDustAllocationBenchmark {
    private static volatile int sink;

    private static void run(LegacyDustFastPathTest.DustUser user, ByteBuf buffer, int count) throws Exception {
        for (int i = 0; i < count; i++) {
            buffer.clear().writeByte(42).writeInt(30).writeBoolean(true)
                .writeFloat(i & 255).writeFloat(64).writeFloat(-8)
                .writeFloat(.25F).writeFloat(.5F).writeFloat(.75F).writeFloat(1F).writeInt(0);
            user.transformClientbound(buffer, CodecException::new);
            sink = buffer.getInt(buffer.writerIndex() - 4);
        }
    }

    public static void main(String[] args) {
        int exit = 0;
        try {
            PlatformTestLoader.ensureLoaded();
            final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
            if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation accounting unavailable");
            bean.setThreadAllocatedMemoryEnabled(true);
            final long thread = Thread.currentThread().getId();
            final int count = args.length > 0 ? Integer.parseInt(args[0]) : 300000;
            for (ProtocolVersion version : new ProtocolVersion[]{ProtocolVersion.v1_12_2, ProtocolVersion.v1_13,
                ProtocolVersion.v1_20_5, ProtocolVersion.v26_2, ProtocolVersion.v26_3}) {
                for (boolean fast : new boolean[]{false, true}) {
                    try (var user = new LegacyDustFastPathTest.DustUser(version, fast)) {
                        final ByteBuf buffer = PooledByteBufAllocator.DEFAULT.buffer(256);
                        try {
                            run(user, buffer, 150000);
                            final long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                            run(user, buffer, count);
                            final long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(thread) - before;
                            if (fast && user.legacyDustFastPathPackets() != 150000L + count) throw new AssertionError("Fast path was skipped");
                            System.out.printf("version=%s fast=%s bytes/packet=%.2f ns/packet=%.2f%n",
                                version.getName(), fast, allocated / (double) count, elapsed / (double) count);
                        } finally { buffer.release(); }
                    }
                }
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            exit = 1;
        }
        // The platform bootstrap owns executor threads; propagate failures instead of leaving the fixture running.
        System.exit(exit);
    }
}
