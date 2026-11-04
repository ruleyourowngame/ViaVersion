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
import com.viaversion.viaversion.api.type.types.IntType;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

final class PacketFieldCursorTest {
    @Test
    void wrappedPartialStagesGrowAndSerializeInOrder() {
        PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
        for (int i = 0; i < 4; i++) packet.write(Types.INT, i);
        packet.resetReader();
        assertEquals(0, packet.read(Types.INT));
        assertEquals(1, packet.passthrough(Types.INT));
        for (int i = 4; i < 100; i++) packet.write(Types.INT, i);
        packet.rewindReader(2);
        assertEquals(98, packet.read(Types.INT));
        assertEquals(99, packet.read(Types.INT));
        packet.resetReader();
        ByteBuf output = Unpooled.buffer();
        try {
            packet.writeProcessedValues(output);
            assertEquals(1, output.readInt());
            for (int i = 4; i < 98; i++) assertEquals(i, output.readInt());
            assertEquals(2, output.readInt());
            assertEquals(3, output.readInt());
            assertFalse(output.isReadable());
        } finally { output.release(); }
    }

    @Test
    void clearingInputDiscardsUnreadFieldsAndPreservesWrittenOnes() {
        PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
        packet.write(Types.STRING, "discard");
        packet.write(Types.INT, 2);
        packet.resetReader();
        packet.write(Types.INT, 3);
        packet.clearInputBuffer();
        assertEquals(3, packet.get(Types.INT, 0));
        assertFalse(packet.isReadable(Types.STRING, 0));
        assertThrows(IllegalArgumentException.class, () -> packet.rewindReader(2));
        assertEquals(3, packet.get(Types.INT, 0));
        packet.resetReader();
        assertEquals(3, packet.read(Types.INT));
        assertTrue(packet.areStoredPacketValuesEmpty());
    }

    @Test
    void consumedAndClearedFieldsReleaseTheirReferences() throws Exception {
        PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
        for (int i = 0; i < 100; i++) packet.write(Types.STRING, new String(new char[100]));
        packet.resetReader();
        for (int i = 0; i < 100; i++) packet.read(Types.STRING);
        java.lang.reflect.Field storageField = PacketWrapperImpl.class.getDeclaredField("packetValues");
        storageField.setAccessible(true);
        Object storage = storageField.get(packet);
        java.lang.reflect.Field fieldsField = storage.getClass().getDeclaredField("fields");
        fieldsField.setAccessible(true);
        for (Object value : (Object[]) fieldsField.get(storage)) assertNull(value);
        packet.write(Types.STRING, "clear");
        packet.clearPacket();
        for (Object value : (Object[]) fieldsField.get(storage)) assertNull(value);
    }

    @Test
    void compatiblePassthroughKeepsOriginalStoredType() {
        PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
        IntType compatible = new IntType();
        packet.write(Types.INT, 7);
        packet.resetReader();
        assertEquals(7, packet.passthrough(compatible));
        assertTrue(packet.is(Types.INT, 0));
        assertFalse(packet.is(compatible, 0));
        assertEquals(7, packet.get(Types.INT, 0));
    }

    @Test
    void packedFieldsMatchDequeModelAcrossRandomOperations() {
        Random random = new Random(913791);
        for (int trial = 0; trial < 250; trial++) {
            PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
            ArrayList<Integer> written = new ArrayList<>();
            ArrayDeque<Integer> readable = new ArrayDeque<>();
            for (int step = 0; step < 250; step++) {
                switch (random.nextInt(7)) {
                    case 0 -> {
                        int value = random.nextInt();
                        packet.write(Types.INT, value); written.add(value);
                    }
                    case 1 -> {
                        packet.resetReader();
                        for (int i = written.size() - 1; i >= 0; i--) readable.addFirst(written.get(i));
                        written.clear();
                    }
                    case 2 -> {
                        if (!readable.isEmpty()) assertEquals(readable.removeFirst(), packet.read(Types.INT));
                    }
                    case 3 -> {
                        if (!readable.isEmpty()) {
                            int value = readable.removeFirst();
                            assertEquals(value, packet.passthrough(Types.INT)); written.add(value);
                        }
                    }
                    case 4 -> {
                        int count = random.nextInt(written.size() + 1);
                        packet.rewindReader(count);
                        for (int i = 0; i < count; i++) readable.addFirst(written.remove(written.size() - 1));
                    }
                    case 5 -> {
                        if (!written.isEmpty()) {
                            int index = random.nextInt(written.size()), value = random.nextInt();
                            assertEquals(written.get(index), packet.get(Types.INT, index));
                            packet.set(Types.INT, index, value); written.set(index, value);
                        }
                    }
                    case 6 -> {
                        if (random.nextInt(10) == 0) { packet.clearPacket(); written.clear(); readable.clear(); }
                    }
                }
                assertEquals(written.isEmpty() && readable.isEmpty(), packet.areStoredPacketValuesEmpty());
            }
            ByteBuf output = Unpooled.buffer();
            try {
                packet.writeProcessedValues(output);
                for (int value : written) assertEquals(value, output.readInt());
                for (int value : readable) assertEquals(value, output.readInt());
                assertFalse(output.isReadable());
            } finally { output.release(); }
        }
    }

    @Test
    void mixedNullableFieldsAndReadOnlyActionsKeepTypesAndValues() {
        PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
        packet.write(Types.STRING, "first");
        packet.write(Types.OPTIONAL_STRING, null);
        packet.write(Types.INT, 42);
        assertNull(packet.get(Types.OPTIONAL_STRING, 0));
        packet.set(Types.STRING, 0, "changed");
        packet.resetReader();
        packet.consumeReadsOnly(() -> {
            assertEquals("changed", packet.passthrough(Types.STRING));
            packet.write(Types.INT, 999);
            packet.rewindReader(100);
        });
        assertFalse(packet.is(Types.STRING, 0));
        assertFalse(packet.is(Types.INT, 0));
        assertNull(packet.passthrough(Types.OPTIONAL_STRING));
        packet.passthrough(Types.INT);
        ByteBuf output = Unpooled.buffer();
        try {
            packet.writeProcessedValues(output);
            assertFalse(output.readBoolean());
            assertEquals(42, output.readInt());
            assertFalse(output.isReadable());
        } finally { output.release(); }
    }

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
    void unchangedStagesKeepUnreadTailAfterDiscardedFields() {
        PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
        packet.write(Types.INT, 1);
        packet.write(Types.INT, 2);
        packet.write(Types.INT, 3);
        packet.resetReader();
        assertEquals(1, packet.read(Types.INT));
        for (int i = 0; i < 100; i++) {
            packet.setId(i);
            packet.resetReader();
        }
        assertEquals(2, packet.read(Types.INT));
        packet.resetReader();
        assertEquals(3, packet.read(Types.INT));
        assertTrue(packet.areStoredPacketValuesEmpty());
        packet.resetReader();
        packet.write(Types.INT, 4);
        packet.resetReader();
        assertEquals(4, packet.read(Types.INT));
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
