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

import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.common.PlatformTestBase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyChunkBufferTest extends PlatformTestBase {
    @Test
    void bulkMatchesCopyingReferenceAndOwnsAllDecodedData() {
        final Random random = new Random(427);
        for (boolean sky : new boolean[]{false, true}) {
            for (int trial = 0; trial < 60; trial++) {
                final int[] masks = new int[trial % 5];
                for (int i = 0; i < masks.length; i++) masks[i] = trial == 1 ? 0 : random.nextInt(65536);
                final ByteBuf input = bulk(sky, masks), reference = input.copy();
                final Chunk[] expected, actual;
                try {
                    expected = referenceBulk(reference);
                    input.writeInt(0x13579BDF);
                    actual = BulkChunkType1_8.TYPE.read(input);
                    assertEquals(0x13579BDF, input.readInt());
                    assertFalse(input.isReadable());
                    assertEquals(1, input.refCnt());
                    input.setZero(0, input.writerIndex());
                } finally { input.release(); reference.release(); }
                assertEquals(expected.length, actual.length);
                for (int i = 0; i < actual.length; i++) same(expected[i], actual[i]);
            }
        }
    }

    @Test
    void singleMatchesReferenceForSparseFullPartialAndUnloadChunks() {
        for (boolean sky : new boolean[]{false, true}) for (boolean full : new boolean[]{false, true}) {
            for (int mask : new int[]{0, 1, 0x8000, 0x5555, 0xffff}) {
                final byte[] data = body(mask, sky, full);
                final ByteBuf input = Unpooled.buffer().writeInt(-31).writeInt(81).writeBoolean(full).writeShort(mask);
                Types.VAR_INT.writePrimitive(input, data.length + 7);
                input.writeBytes(data).writeZero(7).writeInt(0x13579BDF);
                try {
                    final Chunk actual = new ChunkType1_8(sky).read(input);
                    assertEquals(0x13579BDF, input.readInt());
                    input.setZero(0, input.writerIndex());
                    if (full && mask == 0) {
                        assertEquals(0, actual.getBitmask()); assertNull(actual.getBiomeData());
                    } else same(ChunkType1_8.deserialize(-31, 81, full, sky, mask, data), actual);
                    assertEquals(1, input.refCnt());
                } finally { input.release(); }
            }
        }
    }

    @Test
    void malformedSinglePayloadCannotReadIntoFollowingPacket() {
        final ByteBuf input = Unpooled.buffer().writeInt(2).writeInt(3).writeBoolean(true).writeShort(1);
        Types.VAR_INT.writePrimitive(input, 5);
        input.writeZero(5).writeInt(0x13579BDF);
        try {
            final Chunk empty = new ChunkType1_8(true).read(input);
            assertEquals(2, empty.getX()); assertEquals(3, empty.getZ());
            assertEquals(0x13579BDF, input.readInt()); assertEquals(1, input.refCnt());
        } finally { input.release(); }
    }

    @Test
    void truncatedBulkPayloadFailsWithoutReleasingOrAdvancingPastItsHeader() {
        final ByteBuf input = Unpooled.buffer().writeBoolean(true);
        Types.VAR_INT.writePrimitive(input, 1);
        input.writeInt(1).writeInt(2).writeShort(1).writeZero(5);
        final int payloadStart = 12;
        try {
            assertThrows(IndexOutOfBoundsException.class, () -> BulkChunkType1_8.TYPE.read(input));
            assertEquals(payloadStart, input.readerIndex()); assertEquals(1, input.refCnt());
        } finally { input.release(); }
    }

    @Test
    void publicMetadataHelperKeepsItsEagerArrayContract() {
        final ByteBuf input = Unpooled.buffer().writeInt(-1).writeInt(2).writeShort(1).writeZero(12544);
        try {
            final var header = new BulkChunkType1_8.ChunkBulkSection(input, true);
            final byte[] data = header.data();
            assertEquals(12544, data.length);
            header.readData(input); assertSame(data, header.data());
            assertEquals(-1, header.chunkX()); assertEquals(2, header.chunkZ());
        } finally { input.release(); }
    }

    @Test
    void directReadOnlyCompositeAndSharedInputsRemainCallerOwned() {
        final ByteBuf source = bulk(true, new int[]{1, 0x8000});
        final byte[] bytes = new byte[source.readableBytes()];
        source.getBytes(0, bytes);
        final Chunk[] expected;
        try { expected = referenceBulk(source); } finally { source.release(); }
        final ByteBuf[] inputs = {
            Unpooled.directBuffer().writeBytes(bytes),
            Unpooled.unmodifiableBuffer(Unpooled.wrappedBuffer(bytes.clone())),
            Unpooled.wrappedBuffer(java.util.Arrays.copyOfRange(bytes, 0, 13),
                java.util.Arrays.copyOfRange(bytes, 13, bytes.length))
        };
        for (ByteBuf input : inputs) {
            input.retain();
            try {
                final Chunk[] actual = BulkChunkType1_8.TYPE.read(input);
                assertEquals(2, input.refCnt());
                assertFalse(input.isReadable());
                for (int i = 0; i < actual.length; i++) same(expected[i], actual[i]);
            } finally { input.release(); input.release(); }
        }
    }

    static Chunk[] referenceBulk(ByteBuf input) {
        final boolean sky = input.readBoolean(); final int count = Types.VAR_INT.readPrimitive(input);
        final var headers = new BulkChunkType1_8.ChunkBulkSection[count];
        final Chunk[] result = new Chunk[count];
        for (int i = 0; i < count; i++) headers[i] = new BulkChunkType1_8.ChunkBulkSection(input, sky);
        for (int i = 0; i < count; i++) {
            final var h = headers[i]; h.readData(input);
            result[i] = ChunkType1_8.deserialize(h.chunkX(), h.chunkZ(), true, sky, h.bitmask(), h.data());
        }
        return result;
    }

    static ByteBuf bulk(boolean sky, int[] masks) {
        final ByteBuf input = Unpooled.buffer().writeBoolean(sky);
        Types.VAR_INT.writePrimitive(input, masks.length);
        for (int i = 0; i < masks.length; i++) input.writeInt(i * -17).writeInt(i * 29).writeShort(masks[i]);
        for (int mask : masks) input.writeBytes(body(mask, sky, true));
        return input;
    }

    static byte[] body(int mask, boolean sky, boolean full) {
        final ByteBuf out = Unpooled.buffer();
        try {
            for (int section = 0; section < 16; section++) if ((mask & 1 << section) != 0) {
                for (int i = 0; i < 4096; i++) { int id = (i * 31 + section) & 127; out.writeByte(id).writeByte(id >>> 8); }
            }
            for (int i = 0; i < Integer.bitCount(mask) * 2048; i++) out.writeByte(i * 7);
            if (sky) for (int i = 0; i < Integer.bitCount(mask) * 2048; i++) out.writeByte(i * 13);
            if (full) for (int i = 0; i < 256; i++) out.writeByte(i);
            final byte[] result = new byte[out.readableBytes()];out.readBytes(result);return result;
        } finally { out.release(); }
    }

    private static void same(Chunk expected, Chunk actual) {
        assertEquals(expected.getX(), actual.getX()); assertEquals(expected.getZ(), actual.getZ());
        assertEquals(expected.isFullChunk(), actual.isFullChunk()); assertEquals(expected.getBitmask(), actual.getBitmask());
        assertArrayEquals(expected.getBiomeData(), actual.getBiomeData());
        assertArrayEquals(ChunkType1_8.serialize(expected), ChunkType1_8.serialize(actual));
    }
}
