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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.common.PlatformTestBase;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.storage.ConfigurationState.BridgePhase;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clientbound config queue only drains when the client finishes configuration, so it has to be bounded for
 * clients that never do.
 */
class ConfigurationStateQueueLimitTest extends PlatformTestBase {

    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(90);

    private final AtomicLong clock = new AtomicLong(1_000L);
    private EmbeddedChannel channel;
    private UserConnectionImpl user;

    @BeforeEach
    void setUp() {
        // Netty 4.0 refuses an EmbeddedChannel without handlers
        channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        // Client side disconnects by closing the channel directly instead of going through the platform kick
        user = new UserConnectionImpl(channel, true);
    }

    @Test
    void queuesWithinLimits() {
        final ConfigurationState state = state(10, 1024);
        state.setBridgePhase(BridgePhase.REENTERING_CONFIGURATION);

        for (int i = 0; i < 10; i++) {
            state.addClientboundPacketToQueue(packet(16));
        }

        assertEquals(10, state.queuedClientboundPackets());
        assertTrue(channel.isOpen());
        assertFalse(user.isPendingDisconnect());
    }

    @Test
    void disconnectsPastPacketLimit() {
        final ConfigurationState state = state(10, 1024 * 1024);
        state.setBridgePhase(BridgePhase.REENTERING_CONFIGURATION);

        for (int i = 0; i < 11; i++) {
            state.addClientboundPacketToQueue(packet(16));
        }

        assertEquals(10, state.queuedClientboundPackets());
        assertTrue(user.isPendingDisconnect());
        assertFalse(channel.isOpen());
    }

    @Test
    void disconnectsPastByteLimit() {
        final ConfigurationState state = state(1000, 100);
        state.setBridgePhase(BridgePhase.CONFIGURATION);

        state.addClientboundPacketToQueue(packet(60));
        state.addClientboundPacketToQueue(packet(60));

        assertEquals(1, state.queuedClientboundPackets());
        assertTrue(user.isPendingDisconnect());
    }

    @Test
    void disconnectsWhenClientNeverFinishes() {
        final ConfigurationState state = state(1000, 1024 * 1024);
        state.setBridgePhase(BridgePhase.PROFILE_SENT);
        state.addClientboundPacketToQueue(packet(16));
        // Moving on to CONFIGURATION does not restart the clock: the client has been waiting since PROFILE_SENT
        state.setBridgePhase(BridgePhase.CONFIGURATION);

        clock.addAndGet(TIMEOUT_NANOS + 1);
        state.addClientboundPacketToQueue(packet(16));

        assertEquals(1, state.queuedClientboundPackets());
        assertTrue(user.isPendingDisconnect());
    }

    @Test
    void stopsQueueingOnceDisconnectIsPending() {
        final ConfigurationState state = state(1, 1024);
        state.setBridgePhase(BridgePhase.REENTERING_CONFIGURATION);
        state.addClientboundPacketToQueue(packet(16));
        state.addClientboundPacketToQueue(packet(16)); // trips the limit

        state.addClientboundPacketToQueue(packet(16));
        state.addClientboundPacketToQueue(packet(16));

        assertEquals(1, state.queuedClientboundPackets());
    }

    @Test
    void clearResetsLimitsForTheNextConfigurationPhase() {
        final ConfigurationState state = state(5, 1024);
        state.setBridgePhase(BridgePhase.REENTERING_CONFIGURATION);
        for (int i = 0; i < 5; i++) {
            state.addClientboundPacketToQueue(packet(16));
        }
        state.clear();

        // Long after the previous phase started, a new one gets its own budget and clock
        clock.addAndGet(TIMEOUT_NANOS * 10);
        state.setBridgePhase(BridgePhase.REENTERING_CONFIGURATION);
        for (int i = 0; i < 5; i++) {
            state.addClientboundPacketToQueue(packet(16));
        }

        assertEquals(5, state.queuedClientboundPackets());
        assertFalse(user.isPendingDisconnect());
    }

    @Test
    void serverboundCountResetsBetweenConfigurationPhases() {
        // The serverbound packet count used to survive clear(), so a connection that went through enough
        // configuration phases was kicked for "too many packets" no matter how few each phase queued
        final ConfigurationState state = state(1000, 1024 * 1024);
        for (int phase = 0; phase < 3; phase++) {
            state.setBridgePhase(BridgePhase.CONFIGURATION);
            for (int i = 0; i < 600; i++) {
                state.addServerboundPacketToQueue(packet(8));
            }
            state.clear();
        }

        assertFalse(user.isPendingDisconnect());
    }

    private ConfigurationState state(final int maxPackets, final long maxBytes) {
        return new ConfigurationState(maxPackets, maxBytes, TIMEOUT_NANOS, clock::get);
    }

    private PacketWrapper packet(final int size) {
        return PacketWrapper.create(0, Unpooled.buffer(size).writeZero(size), user);
    }
}
