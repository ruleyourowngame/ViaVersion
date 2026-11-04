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
package com.viaversion.viaversion.protocols.v1_20to1_20_2.storage;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.common.PlatformTestBase;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import com.viaversion.viaversion.protocols.v1_19_3to1_19_4.packet.ClientboundPackets1_19_4;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.packet.ClientboundPackets1_20_2;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationStateReplayTest extends PlatformTestBase {
    private CapturingUser user;
    private CapturingState state;

    @BeforeEach
    void setUp() {
        user = new CapturingUser();
        user.getProtocolInfo().setProtocolVersion(ProtocolVersion.v26_2);
        user.getProtocolInfo().setServerProtocolVersion(ProtocolVersion.v1_8);
        final ProtocolPipelineImpl pipeline = new ProtocolPipelineImpl(user);
        Via.getManager().getProtocolManager().getProtocolPath(ProtocolVersion.v26_2, ProtocolVersion.v1_8)
            .forEach(entry -> pipeline.add(entry.protocol()));
        user.getProtocolInfo().setServerState(State.CONFIGURATION);
        user.getProtocolInfo().setClientState(State.PLAY);
        state = new CapturingState();
        state.setBridgePhase(ConfigurationState.BridgePhase.CONFIGURATION);
    }

    @AfterEach
    void close() {
        state.onRemove();
        user.getChannel().close();
    }

    private void queueEntityEvent() {
        final ByteBuf input = Unpooled.buffer().writeInt(1).writeByte(28);
        try {
            state.addClientboundPacketToQueue(PacketWrapper.create(ClientboundPackets1_19_4.ENTITY_EVENT, input, user));
        } finally {
            input.release();
        }
    }

    private void assertDrainedAndReleased(int count) {
        assertEquals(0, state.queuedClientboundPackets());
        assertEquals(count, state.lastBatch.length);
        for (ConfigurationState.QueuedPacket packet : state.lastBatch) assertEquals(0, packet.buf().refCnt());
    }

    @Test
    void activeConnectionMapsEntityEventAcrossFullProtocolPath() {
        queueEntityEvent();
        state.sendQueuedPackets(user);
        assertEquals(List.of("22000000011c"), user.sent); // ENTITY_EVENT in 26.2, same entity/status body.
        assertDrainedAndReleased(1);
    }

    @Test
    void inactiveConnectionDoesNotMisreadEntityEventAsDisguisedChat() {
        queueEntityEvent();
        queueEntityEvent();
        user.setActive(false);
        // Before the guard this reproduces the exact live DISGUISED_CHAT / readerIndex(5)+length(28) error.
        assertDoesNotThrow(() -> state.sendQueuedPackets(user));
        assertTrue(user.sent.isEmpty());
        assertDrainedAndReleased(2);
    }

    @Test
    void pendingDisconnectDropsBatchAndQueuedJoinGame() {
        queueEntityEvent();
        final ByteBuf input = Unpooled.buffer();
        try { state.setJoinGamePacket(PacketWrapper.create(ClientboundPackets1_20_2.LOGIN, input, user)); }
        finally { input.release(); }
        user.pending = true;
        state.sendQueuedPackets(user);
        assertTrue(user.sent.isEmpty());
        assertDrainedAndReleased(2);
    }

    @Test
    void deactivationDuringSendReleasesUnsentRemainder() {
        queueEntityEvent();
        queueEntityEvent();
        user.afterSend = () -> user.setActive(false);
        state.sendQueuedPackets(user);
        assertEquals(1, user.sent.size());
        assertDrainedAndReleased(2);
    }

    @Test
    void sendFailureAlsoReleasesUnsentRemainder() {
        queueEntityEvent();
        queueEntityEvent();
        queueEntityEvent();
        user.afterSend = () -> { throw new IllegalStateException("injected send failure"); };
        assertThrows(InformativeException.class, () -> state.sendQueuedPackets(user));
        assertEquals(1, user.sent.size());
        assertDrainedAndReleased(3);
    }

    @Test
    void reentrantQueueAdditionsRemainOwnedByTheNextBatch() {
        queueEntityEvent();
        user.afterSend = () -> { user.afterSend = null; queueEntityEvent(); };
        state.sendQueuedPackets(user);
        assertEquals(1, user.sent.size());
        assertEquals(1, state.queuedClientboundPackets());
        assertEquals(0, state.lastBatch[0].buf().refCnt());
        state.sendQueuedPackets(user);
        assertEquals(2, user.sent.size());
        assertDrainedAndReleased(1);
    }

    static final class CapturingState extends ConfigurationState {
        QueuedPacket[] lastBatch;
        @Override QueuedPacket[] drainQueue() {
            lastBatch = super.drainQueue();
            return lastBatch;
        }
    }

    static final class CapturingUser extends UserConnectionImpl {
        final List<String> sent = new ArrayList<>();
        boolean pending;
        Runnable afterSend;
        CapturingUser() { super(new EmbeddedChannel(new ChannelInboundHandlerAdapter())); }
        @Override public boolean isPendingDisconnect() { return pending; }
        @Override public void sendRawPacket(ByteBuf packet) {
            try {
                sent.add(ByteBufUtil.hexDump(packet));
                if (afterSend != null) afterSend.run();
            } finally { packet.release(); }
        }
    }
}
