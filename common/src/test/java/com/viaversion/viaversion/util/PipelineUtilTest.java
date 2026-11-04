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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PipelineUtilTest {
    private static ChannelHandlerContext original(String name, ChannelPipeline pipeline) {
        String previous = null;
        for (String entry : pipeline.toMap().keySet()) {
            if (entry.equals(name)) return pipeline.context(previous);
            previous = entry;
        }
        return null;
    }

    private static void compare(ChannelPipeline pipeline) {
        final ArrayList<String> targets = new ArrayList<>(pipeline.names());
        targets.add("missing");
        targets.add("HANDLER-1");
        targets.add(null);
        for (String target : targets) {
            final ChannelHandlerContext expected;
            try {
                expected = original(target, pipeline);
            } catch (NullPointerException firstHandler) {
                // Preserve the existing first-handler boundary behavior.
                Assertions.assertThrows(NullPointerException.class,
                    () -> PipelineUtil.getPreviousContext(target, pipeline));
                continue;
            }
            Assertions.assertSame(expected, PipelineUtil.getPreviousContext(target, pipeline), target);
        }
    }

    @Test
    void matchesMapLookupAcrossPipelineChangesAndInternalTail() {
        final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        try {
            final ChannelPipeline pipeline = channel.pipeline();
            for (String name : pipeline.toMap().keySet()) pipeline.remove(name);
            compare(pipeline);
            final Random random = new Random(0x50495045);
            for (int i = 0; i < 200; i++) {
                final ArrayList<String> names = new ArrayList<>(pipeline.toMap().keySet());
                if (names.isEmpty() || random.nextBoolean()) {
                    final String name = "handler-" + i;
                    if (names.isEmpty() || random.nextBoolean()) {
                        pipeline.addLast(name, new ChannelInboundHandlerAdapter());
                    } else {
                        pipeline.addBefore(names.get(random.nextInt(names.size())), name,
                            new ChannelInboundHandlerAdapter());
                    }
                } else if (random.nextBoolean()) {
                    pipeline.remove(names.get(random.nextInt(names.size())));
                } else {
                    pipeline.replace(names.get(random.nextInt(names.size())), "handler-" + i,
                        new ChannelInboundHandlerAdapter());
                }
                compare(pipeline);
            }
        } finally {
            channel.finish();
        }
    }
}
