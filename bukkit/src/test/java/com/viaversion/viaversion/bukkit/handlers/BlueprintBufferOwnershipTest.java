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
package com.viaversion.viaversion.bukkit.handlers;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.exception.CancelEncoderException;
import io.netty.handler.codec.EncoderException;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BlueprintBufferOwnershipTest {
    @Test
    void optInTransfersExclusiveBufferWithIndependentReference() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(AttributeKey.valueOf("blueprint:outbound-buffer-ownership")).set(1);
        ByteBuf input = Unpooled.buffer().writeInt(123);
        ByteBuf prepared = BlueprintBufferOwnership.prepare(channel, input, channel.alloc());
        try {
            assertSame(input, prepared);
            assertEquals(2, input.refCnt());
            prepared.setInt(0, 456);
            assertEquals(456, input.getInt(0));
        } finally {
            prepared.release();
            assertEquals(1, input.refCnt());
            input.release();
            channel.finish();
        }
    }

    @Test
    void retainedAndNonzeroReaderBuffersStayIsolated() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(AttributeKey.valueOf("blueprint:outbound-buffer-ownership")).set(1);
        ByteBuf input = Unpooled.buffer().writeInt(123).writeInt(789);
        input.retain();
        ByteBuf prepared = BlueprintBufferOwnership.prepare(channel, input, channel.alloc());
        try {
            assertNotSame(input, prepared);
            prepared.setInt(0, 456);
            assertEquals(123, input.getInt(0));
        } finally { prepared.release(); input.release(); }
        input.readerIndex(4);
        prepared = BlueprintBufferOwnership.prepare(channel, input, channel.alloc());
        try {
            assertNotSame(input, prepared);
            assertEquals(789, prepared.readInt());
            assertEquals(8, input.readerIndex());
        } finally { prepared.release(); input.release(); channel.finish(); }
    }

    @Test
    void readOnlyDerivedCompositeAndFixedCapacityBuffersCopy() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(AttributeKey.valueOf("blueprint:outbound-buffer-ownership")).set(1);
        ByteBuf[] inputs = {
            Unpooled.buffer().writeInt(123).asReadOnly(),
            Unpooled.buffer().writeInt(123).slice(),
            Unpooled.compositeBuffer().addComponent(true, Unpooled.buffer().writeInt(123)),
            Unpooled.buffer(4, 4).writeInt(123)
        };
        try {
            for (ByteBuf input : inputs) {
                ByteBuf prepared = BlueprintBufferOwnership.prepare(channel, input, channel.alloc());
                try {
                    assertNotSame(input, prepared);
                    assertEquals(123, prepared.readInt());
                    assertEquals(1, input.refCnt());
                } finally {
                    prepared.release();
                    input.release();
                }
            }
        } finally {
            channel.finish();
        }
    }

    @Test
    void encoderBalancesReferencesOnSuccessCancellationAndFailure() throws Exception {
        for (boolean capability : new boolean[] { false, true }) {
            for (int outcome = 0; outcome < 3; outcome++) {
                final int result = outcome;
                UserConnection connection = (UserConnection) Proxy.newProxyInstance(
                    UserConnection.class.getClassLoader(), new Class<?>[] { UserConnection.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("checkClientboundPacket") || method.getName().equals("shouldTransformPacket")) return true;
                        if (method.getName().equals("transformClientbound")) {
                            if (result == 1) throw CancelEncoderException.generate(null);
                            if (result == 2) throw new IllegalArgumentException("transform failed");
                            ((ByteBuf) args[0]).setInt(0, 456);
                            return null;
                        }
                        throw new AssertionError(method.getName());
                    });
                BukkitEncodeHandler encoder = new BukkitEncodeHandler(connection);
                // This test covers translation ownership; compression ordering has its own state.
                Field compression = BukkitEncodeHandler.class.getDeclaredField("handledCompression");
                compression.setAccessible(true);
                compression.setBoolean(encoder, true);
                EmbeddedChannel channel = new EmbeddedChannel(encoder);
                if (capability) channel.attr(AttributeKey.valueOf("blueprint:outbound-buffer-ownership")).set(1);
                ByteBuf input = Unpooled.buffer().writeInt(123);
                try {
                    if (outcome == 0) {
                        assertTrue(channel.writeOutbound(input));
                        ByteBuf output = channel.readOutbound();
                        try {
                            assertEquals(456, output.readInt());
                            assertEquals(1, output.refCnt());
                            if (capability) assertSame(input, output);
                            else assertNotSame(input, output);
                        } finally {
                            output.release();
                        }
                    } else {
                        assertThrows(EncoderException.class, () -> channel.writeOutbound(input));
                        assertNull(channel.readOutbound());
                    }
                    assertEquals(0, input.refCnt());
                } finally {
                    channel.finish();
                }
            }
        }
    }

    @Test
    void absentCapabilityCopiesEvenExclusiveBuffers() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ByteBuf input = Unpooled.buffer().writeInt(123);
        ByteBuf prepared = BlueprintBufferOwnership.prepare(channel, input, channel.alloc());
        try {
            assertNotSame(input, prepared);
            assertEquals(1, input.refCnt());
        } finally { prepared.release(); input.release(); channel.finish(); }
    }
}
