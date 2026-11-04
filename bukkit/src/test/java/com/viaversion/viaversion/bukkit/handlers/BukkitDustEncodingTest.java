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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.common.PlatformTestLoader;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CodecException;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BukkitDustEncodingTest {
    @BeforeAll
    static void bootstrap() throws InterruptedException { PlatformTestLoader.ensureLoaded(); }

    @Test
    void actualOutboundEncoderRunsFastPathForVerifiedOwnershipAndIsolatedCopies() throws Exception {
        for (ProtocolVersion version : new ProtocolVersion[]{ProtocolVersion.v1_13, ProtocolVersion.v1_20_5, ProtocolVersion.v26_3}) {
            for (boolean capability : new boolean[]{false, true}) {
                for (boolean shared : new boolean[]{false, true}) {
                    final EmbeddedChannel channel = new EmbeddedChannel(), referenceChannel = new EmbeddedChannel();
                    final DustUser user = new DustUser(channel, version, true), ordinary = new DustUser(referenceChannel, version, false);
                    channel.pipeline().addLast(new BukkitEncodeHandler(user));
                    if (capability) channel.attr(AttributeKey.valueOf("blueprint:outbound-buffer-ownership")).set(1);
                    final ByteBuf input = Unpooled.buffer().writeByte(42).writeInt(30).writeBoolean(true)
                        .writeFloat(12.25F).writeFloat(-0F).writeFloat(-64.5F)
                        .writeFloat(.25F).writeFloat(.5F).writeFloat(.75F).writeFloat(1F).writeInt(0);
                    final ByteBuf expected = input.copy();
                    final String source = ByteBufUtil.hexDump(input);
                    if (shared) input.retain();
                    try {
                        ordinary.transformClientbound(expected, CodecException::new);
                        assertTrue(channel.writeOutbound(input));
                        final ByteBuf output = channel.readOutbound();
                        try {
                            assertEquals(ByteBufUtil.hexDump(expected), ByteBufUtil.hexDump(output));
                            assertEquals(1, user.legacyDustFastPathPackets(), version + " capability=" + capability + " shared=" + shared);
                            assertEquals(1, output.refCnt());
                            if (capability && !shared) assertSame(input, output);
                            else assertNotSame(input, output);
                            if (shared) {
                                assertEquals(1, input.refCnt());
                                assertEquals(source, ByteBufUtil.hexDump(input, 0, input.writerIndex()));
                            }
                        } finally { output.release(); }
                        assertEquals(shared ? 1 : 0, input.refCnt());
                    } finally {
                        expected.release();
                        if (shared) input.release();
                        channel.finish(); referenceChannel.finish();
                    }
                }
            }
        }
    }

    static final class DustUser extends UserConnectionImpl {
        DustUser(EmbeddedChannel channel, ProtocolVersion version, boolean enabled) {
            super(channel, false, enabled);
            getProtocolInfo().setProtocolVersion(version);
            getProtocolInfo().setServerProtocolVersion(ProtocolVersion.v1_8);
            getProtocolInfo().setClientState(State.PLAY); getProtocolInfo().setServerState(State.PLAY);
            final ProtocolPipelineImpl pipeline = new ProtocolPipelineImpl(this);
            Via.getManager().getProtocolManager().getProtocolPath(version, ProtocolVersion.v1_8).forEach(entry -> pipeline.add(entry.protocol()));
        }
    }
}
