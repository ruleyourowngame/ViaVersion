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
package com.viaversion.viaversion.common;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.AbstractProtocol;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import com.viaversion.viaversion.protocol.legacy.LegacyDustFastPath;
import com.viaversion.viaversion.protocols.v1_20_2to1_20_3.Protocol1_20_2To1_20_3;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.Protocol1_20To1_20_2;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.storage.ConfigurationState.BridgePhase;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.storage.ProtocolStorables1_20_2;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.CodecException;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyDustFastPathTest extends PlatformTestBase {
    @Test
    void matchesFullOrdinaryPipelineForEveryReleasedVersionAndRandomFinitePayloads() throws Exception {
        final Random random = new Random(0xD057);
        int versions = 0;
        for (ProtocolVersion version : ProtocolVersion.getProtocols()) {
            if (!version.isKnown() || version.olderThan(ProtocolVersion.v1_9) || version.newerThan(ProtocolVersion.v26_3)) continue;
            try (DustUser ordinary = new DustUser(version, false); DustUser fast = new DustUser(version, true)) {
                for (int sample = 0; sample < 128; sample++) {
                    final ByteBuf input = packet(random, sample), expected = input.copy(), actual = input.copy();
                    try {
                        ordinary.transformClientbound(expected, CodecException::new);
                        fast.transformClientbound(actual, CodecException::new);
                        assertEquals(ByteBufUtil.hexDump(expected), ByteBufUtil.hexDump(actual), version + " sample " + sample);
                        assertEquals(sample + 1, fast.legacyDustFastPathPackets(), version.toString());
                    } finally { input.release(); expected.release(); actual.release(); }
                }
                assertEquals(0, ordinary.legacyDustFastPathPackets());
                versions++;
            }
        }
        assertTrue(versions >= 45, "Exercise the full registered version matrix");
    }

    @Test
    void malformedAndNonCanonicalBodiesAreUntouched() {
        reject(body -> body.setInt(1, 29));
        reject(body -> body.setInt(34, 1));
        reject(body -> body.setByte(5, 2));
        reject(body -> body.writerIndex(body.writerIndex() - 1));
        reject(body -> body.writeByte(0));
        for (int offset = 6; offset <= 30; offset += 4) {
            final int at = offset;
            reject(body -> body.setFloat(at, Float.NaN));
            reject(body -> body.setFloat(at, Float.POSITIVE_INFINITY));
            reject(body -> body.setFloat(at, Float.NEGATIVE_INFINITY));
        }
    }

    @Test
    void sharedReadOnlyLittleEndianAndCapacityLimitedBuffersFallBack() {
        try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
            final ByteBuf input = packet(new Random(1), 0);
            input.readerIndex(1);
            try {
                input.retain();
                assertRejected(user, input);
                input.release();
                final ByteBuf readOnly = Unpooled.unmodifiableBuffer(input);
                assertRejected(user, readOnly);
                assertRejected(user, readOnly.duplicate());
                assertRejected(user, readOnly.slice().readerIndex(0));
                assertRejected(user, input.order(ByteOrder.LITTLE_ENDIAN));
                final ByteBuf small = Unpooled.buffer(38, 38).writeBytes(input, 0, 38).readerIndex(1);
                try { assertRejected(user, small); } finally { small.release(); }
            } finally { input.release(); }
        }
    }

    @Test
    void connectionStatesAndDebugKeepOrdinaryTranslation() {
        try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
            final ByteBuf input = packet(new Random(1), 0).readerIndex(1);
            try {
                user.setActive(false); assertRejected(user, input); user.setActive(true);
                user.pending = true; assertRejected(user, input); user.pending = false;
                user.getProtocolInfo().setServerState(State.CONFIGURATION); assertRejected(user, input);
                user.getProtocolInfo().setServerState(State.PLAY);
                user.getProtocolInfo().setClientState(State.CONFIGURATION); assertRejected(user, input);
                user.getProtocolInfo().setClientState(State.PLAY);
                user.getProtocolInfo().setServerProtocolVersion(ProtocolVersion.v1_9); assertRejected(user, input);
                user.getProtocolInfo().setServerProtocolVersion(ProtocolVersion.v1_8);
                final var bridge = user.getProtocolInfo().getPipeline().getProtocol(Protocol1_20To1_20_2.class);
                final ProtocolStorables1_20_2 storage = user.storables(bridge);
                storage.configurationState().setBridgePhase(BridgePhase.CONFIGURATION); assertRejected(user, input);
                storage.configurationState().setBridgePhase(BridgePhase.NONE);
                Via.getManager().debugHandler().setEnabled(true);
                try { assertRejected(user, input); } finally { Via.getManager().debugHandler().setEnabled(false); }
                assertTrue(new LegacyDustFastPath().tryTransform(user, 42, input));
            } finally { input.release(); }
        }
    }

    @Test
    void customHandlerBeforeOrAfterCompilationRunsExactlyOnceForTheRealPacket() throws Exception {
        for (boolean warm : new boolean[]{false, true}) {
            try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
                if (warm) transform(user);
                final var protocol = user.getProtocolInfo().getPipeline().getProtocol(Protocol1_20_2To1_20_3.class);
                final var incoming = protocol.clientboundMapping(State.PLAY,
                    com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ClientboundPackets1_20_2.LEVEL_PARTICLES.getId());
                final var original = incoming.handler();
                final AtomicInteger calls = new AtomicInteger();
                try {
                    incoming.setHandler(wrapper -> { calls.incrementAndGet(); original.handle(wrapper); });
                    transform(user);
                    assertEquals(1, calls.get());
                    assertEquals(warm ? 1 : 0, user.legacyDustFastPathPackets());
                } finally { incoming.setHandler(original); }
            }
        }
    }

    @Test
    void customReaderMutationFallsBackBeforeCallingAnySyntheticReader() throws Exception {
        for (boolean warm : new boolean[]{false, true}) {
            try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
                if (warm) transform(user);
                final var protocol = user.getProtocolInfo().getPipeline().getProtocol(com.viaversion.viaversion.protocols.v1_21_2to1_21_4.Protocol1_21_2To1_21_4.class);
                final var type = protocol.types().particle();
                final int dust = protocol.getMappingData().getParticleMappings().id("dust");
                final var original = type.reader(dust);
                final AtomicInteger calls = new AtomicInteger();
                try {
                    type.rawFiller().reader(dust, (buffer, particle) -> { calls.incrementAndGet(); original.read(buffer, particle); });
                    transform(user);
                    // The full pipeline carries a decoded Particle across these stages, so its reader is not invoked.
                    assertEquals(0, calls.get());
                    assertEquals(warm ? 1 : 0, user.legacyDustFastPathPackets());
                } finally { type.rawFiller().reader(dust, original); }
            }
        }
    }

    @Test
    void mappingAndPipelineMutationInvalidateCompiledPlanWithoutTouchingTheBody() {
        try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
            final LegacyDustFastPath fast = new LegacyDustFastPath();
            final ByteBuf first = packet(new Random(1), 0).readerIndex(1);
            try { assertTrue(fast.tryTransform(user, 42, first)); } finally { first.release(); }
            final var protocol = user.getProtocolInfo().getPipeline().getProtocol(Protocol1_20_2To1_20_3.class);
            final var particles = protocol.getMappingData().getParticleMappings();
            final int dust = particles.id("dust"), original = particles.getNewId(dust);
            final ByteBuf input = packet(new Random(1), 0).readerIndex(1);
            try {
                particles.setNewId(dust, original + 1);
                assertRejected(fast, user, input);
            } finally { particles.setNewId(dust, original); input.release(); }
        }
        try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
            final LegacyDustFastPath fast = new LegacyDustFastPath();
            final ByteBuf first = packet(new Random(1), 0).readerIndex(1);
            try { assertTrue(fast.tryTransform(user, 42, first)); } finally { first.release(); }
            ((ProtocolPipelineImpl) user.getProtocolInfo().getPipeline()).add(new AbstractProtocol<>() { });
            final ByteBuf input = packet(new Random(1), 0).readerIndex(1);
            try { assertRejected(fast, user, input); } finally { input.release(); }
        }
    }

    @Test
    void unknownTransformOverrideRunsOnlyForRealPackets() throws Exception {
        try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
            final AtomicInteger calls = new AtomicInteger();
            final AbstractProtocol<?, ?, ?, ?> custom = new AbstractProtocol<>() {
                @Override
                public void transform(com.viaversion.viaversion.api.protocol.packet.Direction direction, State state,
                    com.viaversion.viaversion.api.protocol.packet.PacketWrapper wrapper)
                    throws com.viaversion.viaversion.exception.InformativeException,
                    com.viaversion.viaversion.exception.CancelException {
                    calls.incrementAndGet();
                    super.transform(direction, state, wrapper);
                }
            };
            ((ProtocolPipelineImpl) user.getProtocolInfo().getPipeline()).add(custom);
            transform(user);
            assertEquals(1, calls.get());
            assertEquals(0, user.legacyDustFastPathPackets());
        }
    }

    @Test
    void unknownAndChangedClientVersionsFallBack() {
        try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
            final LegacyDustFastPath fast = new LegacyDustFastPath();
            final ByteBuf first = packet(new Random(1), 0).readerIndex(1);
            try { assertTrue(fast.tryTransform(user, 42, first)); } finally { first.release(); }
            final ByteBuf input = packet(new Random(1), 0).readerIndex(1);
            try {
                user.getProtocolInfo().setProtocolVersion(ProtocolVersion.getProtocol(99999));
                assertRejected(fast, user, input);
                user.getProtocolInfo().setProtocolVersion(ProtocolVersion.v1_8);
                assertRejected(fast, user, input);
                user.getProtocolInfo().setProtocolVersion(ProtocolVersion.v26_2);
                assertRejected(fast, user, input);
            } finally { input.release(); }
        }
    }

    @Test
    void changingDustClassificationInvalidatesThePlan() throws Exception {
        try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
            final LegacyDustFastPath fast = new LegacyDustFastPath();
            final ByteBuf first = packet(new Random(1), 0).readerIndex(1);
            try { assertTrue(fast.tryTransform(user, 42, first)); } finally { first.release(); }
            final var protocol = user.getProtocolInfo().getPipeline().getProtocol(Protocol1_20_2To1_20_3.class);
            final var particles = protocol.getMappingData().getParticleMappings();
            final ByteBuf input = packet(new Random(1), 0).readerIndex(1);
            // The public API can add classifications but cannot remove them; restore shared test state explicitly.
            final var field = particles.getClass().getDeclaredField("blockParticleIds");
            field.setAccessible(true);
            final var ids = (it.unimi.dsi.fastutil.ints.IntList) field.get(particles);
            final int originalSize = ids.size();
            try {
                assertTrue(particles.addBlockParticle("dust"));
                assertRejected(fast, user, input);
                assertRejected(user, input);
            } finally { ids.size(originalSize); input.release(); }
        }
    }

    static void transform(DustUser user) throws Exception {
        final ByteBuf input = packet(new Random(1), 0);
        try { user.transformClientbound(input, CodecException::new); } finally { input.release(); }
    }

    static ByteBuf packet(Random random, int sample) {
        final ByteBuf buffer = Unpooled.buffer().writeByte(42).writeInt(30).writeBoolean((sample & 1) == 0);
        for (int i = 0; i < 7; i++) {
            final float value;
            if (sample < 8) value = switch (sample) {
                case 0 -> 0F; case 1 -> -0F; case 2 -> 1F; case 3 -> -1F;
                case 4 -> Float.MAX_VALUE; case 5 -> -Float.MAX_VALUE;
                case 6 -> Float.MIN_VALUE; default -> -Float.MIN_VALUE;
            };
            else {
                float candidate;
                do { candidate = Float.intBitsToFloat(random.nextInt()); } while (!Float.isFinite(candidate));
                value = candidate;
            }
            buffer.writeFloat(value);
        }
        return buffer.writeInt(0);
    }

    private static void reject(Consumer<ByteBuf> mutation) {
        try (DustUser user = new DustUser(ProtocolVersion.v26_3, true)) {
            final ByteBuf input = packet(new Random(1), 0);
            try { mutation.accept(input); input.readerIndex(1); assertRejected(user, input); }
            finally { input.release(); }
        }
    }

    private static void assertRejected(DustUser user, ByteBuf input) { assertRejected(new LegacyDustFastPath(), user, input); }
    private static void assertRejected(LegacyDustFastPath fast, DustUser user, ByteBuf input) {
        final int reader = input.readerIndex(), writer = input.writerIndex(), refs = input.refCnt();
        final String before = ByteBufUtil.hexDump(input, 0, writer);
        assertFalse(fast.tryTransform(user, 42, input));
        assertEquals(reader, input.readerIndex()); assertEquals(writer, input.writerIndex()); assertEquals(refs, input.refCnt());
        assertEquals(before, ByteBufUtil.hexDump(input, 0, writer));
    }

    static final class DustUser extends UserConnectionImpl implements AutoCloseable {
        boolean pending;
        DustUser(ProtocolVersion version, boolean enabled) {
            super(new EmbeddedChannel(new ChannelInboundHandlerAdapter()), false, enabled);
            getProtocolInfo().setProtocolVersion(version);
            getProtocolInfo().setServerProtocolVersion(ProtocolVersion.v1_8);
            getProtocolInfo().setClientState(State.PLAY);
            getProtocolInfo().setServerState(State.PLAY);
            final ProtocolPipelineImpl pipeline = new ProtocolPipelineImpl(this);
            Via.getManager().getProtocolManager().getProtocolPath(version, ProtocolVersion.v1_8).forEach(entry -> pipeline.add(entry.protocol()));
        }
        @Override public boolean isPendingDisconnect() { return pending; }
        @Override public void close() { getChannel().close(); }
    }
}
