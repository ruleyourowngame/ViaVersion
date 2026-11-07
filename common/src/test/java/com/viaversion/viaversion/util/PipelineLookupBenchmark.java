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

import com.sun.management.ThreadMXBean;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.management.ManagementFactory;

/** Standalone allocation fixture; timing is illustrative, never a test threshold. */
public final class PipelineLookupBenchmark {
    private static volatile Object sink;

    private static ChannelHandlerContext original(String name, ChannelPipeline pipeline) {
        return PipelineUtil.getPreviousContext(name, pipeline, null);
    }

    private static void run(ChannelPipeline pipeline, String name, boolean candidate, int count) {
        for (int i = 0; i < count; i++) {
            sink = candidate ? PipelineUtil.getPreviousContext(name, pipeline) : original(name, pipeline);
        }
    }

    public static void main(String[] args) {
        System.out.println("Netty runtime: " + io.netty.util.Version.identify());
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        final int count = 200000;
        for (int size : new int[]{8, 16, 32}) {
            final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
            try {
                for (String name : channel.pipeline().toMap().keySet()) channel.pipeline().remove(name);
                for (int i = 0; i < size; i++) {
                    channel.pipeline().addLast("handler-" + i, new ChannelInboundHandlerAdapter());
                }
                for (int position : new int[]{1, size / 2, size - 1}) {
                    final String target = "handler-" + position;
                    for (int warmup = 0; warmup < 4; warmup++) {
                        run(channel.pipeline(), target, false, 100000);
                        run(channel.pipeline(), target, true, 100000);
                    }
                    final long[][] allocated = new long[2][7], elapsed = new long[2][7];
                    for (int round = 0; round < 7; round++) {
                        for (int order = 0; order < 2; order++) {
                            final int candidate = (round + order) & 1;
                            final long thread = Thread.currentThread().getId();
                            final long before = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
                            run(channel.pipeline(), target, candidate == 1, count);
                            elapsed[candidate][round] = System.nanoTime() - start;
                            allocated[candidate][round] = bean.getThreadAllocatedBytes(thread) - before;
                        }
                    }
                    for (int candidate = 0; candidate < 2; candidate++) {
                        java.util.Arrays.sort(allocated[candidate]);
                        java.util.Arrays.sort(elapsed[candidate]);
                        System.out.printf("%d handlers target=%d %s: %.1f bytes/lookup, %.1f ns/lookup%n",
                            size, position, candidate == 1 ? "links" : "names",
                            allocated[candidate][3] / (double) count, elapsed[candidate][3] / (double) count);
                    }
                }
            } finally {
                channel.finish();
            }
        }
    }
}
