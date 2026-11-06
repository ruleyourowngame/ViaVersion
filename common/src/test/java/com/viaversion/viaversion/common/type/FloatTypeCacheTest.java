package com.viaversion.viaversion.common.type;

import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FloatTypeCacheTest {
    @Test
    void cachedReadsPreserveEveryRawBitIncludingSignedZeroAndNanPayloads() {
        ByteBuf buffer = Unpooled.buffer();
        Random random = new Random(143);
        try {
            int[] special = {0, 0x80000000, 0x3f800000, 0xbf800000, 0x7fc00042, 0x7f800001,
                0xffc00042, 0x7f800000, 0xff800000, 1, 0x7f7fffff};
            for (int bits : special) roundTrip(buffer, bits);
            for (int i = 0; i < 100000; i++) roundTrip(buffer, random.nextInt());
        } finally { buffer.release(); }
    }

    @Test
    void commonValuesShareOnlyImmutableBoxesAndDifferentZerosRemainDistinct() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int bits : new int[]{0, 0x80000000, 0x3f800000}) {
                buffer.clear().writeInt(bits).writeInt(bits);
                assertSame(Types.FLOAT.read(buffer), Types.FLOAT.read(buffer));
            }
            buffer.clear().writeInt(0).writeInt(0x80000000);
            assertNotEquals(Types.FLOAT.read(buffer), Types.FLOAT.read(buffer));
        } finally { buffer.release(); }
    }

    private static void roundTrip(ByteBuf buffer, int bits) {
        buffer.clear().writeInt(bits);
        Float value = Types.FLOAT.read(buffer);
        assertEquals(bits, Float.floatToRawIntBits(value));
        buffer.clear();
        Types.FLOAT.write(buffer, value);
        assertEquals(bits, buffer.readInt());
    }
}
