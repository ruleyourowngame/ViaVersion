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
package com.viaversion.viaversion.common.protocol;

import com.sun.management.ThreadMXBean;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/** Run identical fixture bytecode against baseline/candidate classes in separate JVMs. */
public final class PacketFieldForwardingBenchmark {
    private static volatile Object sink;
    private static volatile long checksum;

    private static void run(String mode, int fields, int hint, int packets, ByteBuf input) {
        long result = 0;
        for (int n = 0; n < packets; n++) {
            if (input != null) input.readerIndex(0);
            PacketWrapperImpl packet = new PacketWrapperImpl(-1, input, null, hint);
            for (int i = 0; i < fields; i++) {
                if (input != null) result += packet.passthrough(Types.INT);
                else packet.write(Types.INT, i & 127);
            }
            if (mode.equals("raw")) {
                // First decode stage only; no stored-field forwarding to optimize.
            } else if (mode.equals("partial")) {
                for (int stage = 0; stage < 8; stage++) {
                    packet.resetReader();
                    for (int i = 0; i < fields / 2; i++) result += packet.passthrough(Types.INT);
                    packet.rewindReader(1);
                    result += packet.passthrough(Types.INT);
                }
            } else if (mode.equals("overlapRewind")) {
                // These shapes leave one empty slot; rewinding eight fields overlaps the source.
                for (int i = 0; i < fields; i++) {
                    packet.rewindReader(8);
                    for (int j = 0; j < 8; j++) result += packet.read(Types.INT);
                    for (int j = 0; j < 8; j++) packet.write(Types.INT, (i + j) & 127);
                }
            } else if (mode.equals("rewind")) {
                for (int i = 0; i < fields; i++) {
                    packet.rewindReader(1);
                    result += packet.read(Types.INT);
                    packet.write(Types.INT, i & 127);
                }
            } else {
                for (int stage = 0; stage < 8; stage++) {
                    packet.resetReader();
                    for (int i = 0; i < fields; i++) result += packet.passthrough(Types.INT);
                }
            }
            sink = packet; // Both wrappers and storage escape, as they do in packet handlers.
        }
        checksum = result;
    }

    public static void main(String[] args) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        System.out.println("JDK " + System.getProperty("java.version") + " " + System.getProperty("os.arch"));
        System.out.println("PacketWrapper source: " + PacketWrapperImpl.class.getProtectionDomain().getCodeSource().getLocation());
        for (String mode : args.length == 0 ? new String[]{"full", "fullZero", "partial", "rewind", "raw", "overlapRewind"} : args) {
            if (!java.util.Set.of("full", "fullZero", "partial", "rewind", "raw", "overlapRewind").contains(mode)) {
                throw new IllegalArgumentException("Unknown benchmark mode: " + mode);
            }
            int[] sizes = mode.equals("full") ? new int[]{-1, 0, 1, 4, 15, 64, 4096}
                : mode.equals("fullZero") ? new int[]{1, 4, 15, 64, 4096}
                : mode.equals("raw") ? new int[]{0, 1, 4, 64, 4096} : mode.equals("overlapRewind") ? new int[]{63, 4095} : new int[]{63, 64, 65, 4095, 4096, 4097};
            for (int requested : sizes) {
                int fields = Math.max(0, requested);
                int hint = mode.equals("fullZero") || requested == 0 ? 0 : (fields == 15 ? 16 : 4);
                ByteBuf input = mode.equals("raw") ? Unpooled.buffer(fields * 4) : null;
                if (input != null) for (int i = 0; i < fields; i++) input.writeInt(i & 127);
                try {
                    int packets = Math.max(100, 1000000 / Math.max(1, fields));
                    for (int round = 0; round < 5; round++) run(mode, fields, hint, packets, input);
                    long[] nanos = new long[9], bytes = new long[9];
                    long id = Thread.currentThread().getId();
                    for (int round = 0; round < 9; round++) {
                        long before = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
                        run(mode, fields, hint, packets, input);
                        nanos[round] = System.nanoTime() - start;
                        bytes[round] = bean.getThreadAllocatedBytes(id) - before;
                    }
                    Arrays.sort(nanos); Arrays.sort(bytes);
                    System.out.printf("mode=%s fields=%d hint=%d bytes/packet=%.1f ns/packet=%.1f checksum=%d%n",
                        mode, fields, hint, bytes[4] / (double) packets, nanos[4] / (double) packets, checksum);
                } finally { if (input != null) input.release(); }
            }
        }
    }
}
