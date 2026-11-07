package com.viaversion.viaversion.common.protocol;

import com.viaversion.viaversion.api.protocol.AbstractProtocol;
import com.viaversion.viaversion.api.protocol.packet.*;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.common.PlatformTestBase;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CodecException;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveFieldCapacityTest extends PlatformTestBase {
    @Test
    void hintsDoNotChangeFieldOrderAcrossReadWriteAndRewindStages() {
        for (int hint : new int[]{Integer.MIN_VALUE, -1, 0, 4, 5, 8, 16, 31, 64, Integer.MAX_VALUE}) {
            PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null, hint);
            assertEquals(0, packet.fieldCapacityHint(), "hint must not eagerly allocate storage");
            for (int i = 0; i < 100; i++) packet.write(Types.INT, i);
            for (int stage = 0; stage < 8; stage++) {
                packet.resetReader();
                for (int i = 0; i < 100; i++) {
                    assertEquals(i, packet.passthrough(Types.INT));
                    packet.rewindReader(1);
                    assertEquals(i, packet.read(Types.INT));
                    packet.write(Types.INT, i);
                }
            }
            assertEquals(64, packet.fieldCapacityHint(), "learned hints must stay bounded");
            packet.resetReader();
            for (int i = 0; i < 100; i++) assertEquals(i, packet.read(Types.INT));
            assertTrue(packet.areStoredPacketValuesEmpty());
        }
    }

    @Test
    void failedPassthroughConsumesOnlyTheMismatchedFieldAndDoesNotWriteIt() {
        UserConnectionImpl user = user();
        try {
            PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, user, 0);
            packet.write(Types.STRING, "discard");
            packet.write(Types.INT, 42);
            packet.resetReader();
            var failure = assertThrows(com.viaversion.viaversion.exception.InformativeException.class,
                () -> packet.passthrough(Types.INT));
            assertTrue(failure.getCause().getMessage().contains("Unable to read type"));
            assertFalse(packet.is(Types.STRING, 0));
            assertEquals(42, packet.passthrough(Types.INT));
            assertEquals(42, packet.get(Types.INT, 0));
            packet.resetReader();
            assertEquals(42, packet.read(Types.INT));
            assertTrue(packet.areStoredPacketValuesEmpty());
        } finally { user.getChannel().close(); }
    }

    @Test
    void connectionLearnsSeparateDirectionsAndFallsBackForOutOfRangeIds() throws Exception {
        UserConnectionImpl user = user();
        try {
            for (int id : new int[]{141, 999}) {
                transform(user, id, false);
                transform(user, id, false);
                transform(user, id, true);
                transform(user, id, true);
            }
            for (String name : new String[]{"clientboundFieldCapacities", "serverboundFieldCapacities"}) {
                Field field = UserConnectionImpl.class.getDeclaredField(name);
                field.setAccessible(true);
                byte[] hints = (byte[]) field.get(user);
                assertEquals(256, hints.length);
                assertEquals(16, hints[141]);
                assertEquals(0, hints[140]);
            }
        } finally { user.getChannel().close(); }
    }

    @Test
    void aStaleLargeHintLearnsActualDemandAndStateChangesDiscardOldSchemaHints() throws Exception {
        PacketWrapperImpl small = new PacketWrapperImpl(141, null, null, 64);
        small.write(Types.INT, 7);
        assertEquals(4, small.fieldCapacityHint());
        UserConnectionImpl user = user();
        try {
            transform(user, 141, false);
            Field clientbound = UserConnectionImpl.class.getDeclaredField("clientboundFieldCapacities");
            clientbound.setAccessible(true);
            assertEquals(16, ((byte[]) clientbound.get(user))[141]);
            user.getProtocolInfo().setServerState(State.CONFIGURATION);
            ByteBuf body = Unpooled.buffer().writeByte(1);
            try { user.transformClientbound(body, CodecException::new); } finally { body.release(); }
            assertNull(clientbound.get(user));
            user.getProtocolInfo().setServerState(State.PLAY);
            transform(user, 141, false);
            assertEquals(16, ((byte[]) clientbound.get(user))[141]);
        } finally { user.getChannel().close(); }
    }

    static UserConnectionImpl user() {
        UserConnectionImpl user = new UserConnectionImpl(new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter()));
        user.getProtocolInfo().setProtocolVersion(ProtocolVersion.v26_3);
        user.getProtocolInfo().setServerProtocolVersion(ProtocolVersion.v26_3);
        user.getProtocolInfo().setClientState(State.PLAY);
        user.getProtocolInfo().setServerState(State.PLAY);
        FieldsProtocol protocol = new FieldsProtocol();
        protocol.setServerVersion(ProtocolVersion.v26_3);
        protocol.setClientVersion(ProtocolVersion.v26_3);
        protocol.initialize();
        new ProtocolPipelineImpl(user).add(protocol);
        return user;
    }

    private static void transform(UserConnectionImpl user, int id, boolean serverbound) throws Exception {
        ByteBuf body = Unpooled.buffer();
        try {
            Types.VAR_INT.writePrimitive(body, id);
            for (int i = 0; i < 15; i++) body.writeInt(i);
            if (serverbound) user.transformServerbound(body, CodecException::new);
            else user.transformClientbound(body, CodecException::new);
            assertEquals(id, Types.VAR_INT.readPrimitive(body));
            for (int i = 0; i < 15; i++) assertEquals(i, body.readInt());
            assertFalse(body.isReadable());
        } finally { body.release(); }
    }

    static final class FieldsProtocol extends AbstractProtocol<ClientboundPacketType, ClientboundPacketType,
            ServerboundPacketType, ServerboundPacketType> {
        @Override protected void applySharedRegistrations() {
            // The fixture has only integer fields, with no versioned packet registry to rewrite.
        }
        @Override protected void registerPackets() {
            for (int id : new int[]{141, 999}) {
                registerClientbound(State.PLAY, id, id, FieldsProtocol::fields);
                registerServerbound(State.PLAY, id, id, FieldsProtocol::fields);
            }
        }
        private static void fields(PacketWrapper wrapper) {
            for (int i = 0; i < 15; i++) wrapper.passthrough(Types.INT);
            for (int stage = 0; stage < 8; stage++) {
                wrapper.resetReader();
                for (int i = 0; i < 15; i++) wrapper.passthrough(Types.INT);
            }
        }
    }
}
