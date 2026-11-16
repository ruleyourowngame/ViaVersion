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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.providers.ViaProviders;
import com.viaversion.viaversion.common.PlatformTestBase;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocols.v1_8to1_9.provider.MovementTransmitterProvider;
import com.viaversion.viaversion.protocols.v1_8to1_9.storage.MovementTracker;
import com.viaversion.viaversion.protocols.v1_8to1_9.task.IdlePacketTask;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdlePacketTaskTest extends PlatformTestBase {

    static final class RecordingTransmitter extends MovementTransmitterProvider {
        boolean ownPass;
        int passes;
        final List<UserConnection> sent = new ArrayList<>();

        @Override
        public boolean runIdlePass() {
            passes++;
            return ownPass;
        }

        @Override
        public void sendPlayer(final UserConnection connection, final MovementTracker tracker) {
            sent.add(connection);
        }
    }

    private final RecordingTransmitter transmitter = new RecordingTransmitter();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private MovementTransmitterProvider previous;

    @BeforeEach
    void useRecordingTransmitter() {
        final ViaProviders providers = Via.getManager().getProviders();
        previous = providers.get(MovementTransmitterProvider.class);
        providers.use(MovementTransmitterProvider.class, transmitter);
    }

    @AfterEach
    void restore() {
        channels.forEach(EmbeddedChannel::close); // their close listeners drop them from the connection manager
        channels.forEach(EmbeddedChannel::runPendingTasks);
        Via.getManager().getProviders().use(MovementTransmitterProvider.class, previous);
    }

    private UserConnection dueConnection() {
        final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        channels.add(channel);
        final UserConnectionImpl connection = new UserConnectionImpl(channel);
        connection.getProtocolInfo().setUuid(UUID.randomUUID());
        connection.put(new MovementTracker()); // next idle packet at 0: due
        Via.getManager().getConnectionManager().onLoginSuccess(connection);
        return connection;
    }

    @Test
    void theTaskTicksEveryDueConnectionByDefault() {
        final UserConnection due = dueConnection();

        new IdlePacketTask().run();

        assertEquals(1, transmitter.passes);
        assertTrue(transmitter.sent.contains(due));
    }

    @Test
    void aTransmitterThatRunsItsOwnPassReplacesTheTasks() {
        dueConnection();
        transmitter.ownPass = true;

        new IdlePacketTask().run();

        assertEquals(1, transmitter.passes);
        assertEquals(List.of(), transmitter.sent, "the task does not walk the connections");
    }
}
