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
        String previous = null;
        for (String entry : pipeline.toMap().keySet()) {
            if (entry.equals(name)) return pipeline.context(previous);
            previous = entry;
        }
        return null;
    }

    private static void run(ChannelPipeline pipeline, String name, boolean candidate, int count) {
        for (int i = 0; i < count; i++) {
            sink = candidate ? PipelineUtil.getPreviousContext(name, pipeline) : original(name, pipeline);
        }
    }

    public static void main(String[] args) {
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (int size : new int[]{8, 16, 32}) {
            final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
            try {
                for (String name : channel.pipeline().toMap().keySet()) channel.pipeline().remove(name);
                for (int i = 0; i < size; i++) {
                    channel.pipeline().addLast("handler-" + i, new ChannelInboundHandlerAdapter());
                }
                final String target = "handler-" + (size - 1);
                for (boolean candidate : new boolean[]{false, true}) {
                    run(channel.pipeline(), target, candidate, 100000);
                    final long thread = Thread.currentThread().getId();
                    final long before = bean.getThreadAllocatedBytes(thread);
                    final long start = System.nanoTime();
                    run(channel.pipeline(), target, candidate, 1000000);
                    final long elapsed = System.nanoTime() - start;
                    final long allocated = bean.getThreadAllocatedBytes(thread) - before;
                    System.out.printf("%d handlers, %s: %.1f bytes/lookup, %.1f ns/lookup%n",
                        size, candidate ? "names" : "map", allocated / 1000000.0, elapsed / 1000000.0);
                }
            } finally {
                channel.finish();
            }
        }
    }
}
