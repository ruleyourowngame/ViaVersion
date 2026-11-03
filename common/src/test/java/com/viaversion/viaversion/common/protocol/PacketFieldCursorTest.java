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

import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class PacketFieldCursorTest {
    @Test
    void repeatedTransformsPreserveUnreadTail() {
        PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
        packet.write(Types.INT, 1);
        packet.write(Types.INT, 2);
        packet.write(Types.INT, 3);
        packet.resetReader();
        for (int i = 0; i < 100; i++) {
            assertEquals(i + 1, packet.read(Types.INT));
            packet.write(Types.INT, i + 2);
            assertTrue(packet.isReadable(Types.INT, 1));
            assertFalse(packet.isReadable(Types.INT, 2));
            packet.resetReader();
        }
        assertEquals(101, packet.read(Types.INT));
        assertEquals(2, packet.read(Types.INT));
        assertEquals(3, packet.read(Types.INT));
        assertTrue(packet.areStoredPacketValuesEmpty());
    }

    @Test
    void rewindKeepsWrittenPrefixAndUnreadTail() {
        PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
        for (int i = 1; i <= 4; i++) packet.write(Types.INT, i);
        packet.resetReader();
        packet.passthrough(Types.INT);
        packet.passthrough(Types.INT);
        packet.passthrough(Types.INT);
        packet.rewindReader(2);
        assertEquals(1, packet.get(Types.INT, 0));
        assertEquals(2, packet.read(Types.INT));
        assertEquals(3, packet.read(Types.INT));
        assertEquals(4, packet.read(Types.INT));
    }

    @Test
    void writesStoredFieldsThenRawTailAndClearsBothBuffers() {
        ByteBuf input = Unpooled.buffer().writeInt(30);
        ByteBuf output = Unpooled.buffer();
        try {
            PacketWrapperImpl packet = new PacketWrapperImpl(-1, input, null);
            packet.write(Types.INT, 10);
            packet.write(Types.INT, 20);
            packet.resetReader();
            packet.passthrough(Types.INT);
            packet.writeToBuffer(output);
            assertEquals(10, output.readInt());
            assertEquals(20, output.readInt());
            assertEquals(30, output.readInt());
            assertFalse(output.isReadable());
            packet.clearPacket();
            assertTrue(packet.areStoredPacketValuesEmpty());
            packet.write(Types.INT, 40);
            packet.resetReader();
            assertEquals(40, packet.read(Types.INT));
        } finally {
            input.release();
            output.release();
        }
    }
}
