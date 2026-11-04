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
package com.viaversion.viaversion.bukkit.providers;

import com.viaversion.viaversion.ViaVersionPlugin;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.bukkit.platform.MicroServersSupport;
import com.viaversion.viaversion.bukkit.tasks.v1_18_2to1_19.AckSequenceTask;
import com.viaversion.viaversion.protocols.v1_18_2to1_19.Protocol1_18_2To1_19;
import com.viaversion.viaversion.protocols.v1_18_2to1_19.packet.ClientboundPackets1_19;
import com.viaversion.viaversion.protocols.v1_18_2to1_19.provider.AckSequenceProvider;
import com.viaversion.viaversion.protocols.v1_18_2to1_19.storage.SequenceStorage;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.entity.Player;

public final class BukkitAckSequenceProvider extends AckSequenceProvider {

    /**
     * Blueprint parks a connection's outgoing packets until the end of the tick that handled its
     * packets, and with MicroServers that tick runs on the player's game thread, on its own clock.
     * The stock ack (a main-thread task one tick out, written straight to the channel) then often
     * reaches the client before the block update it acknowledges, and the client shows the block
     * reverted until the update lands - up to ~90 ms on MicroServers, long enough to fall through
     * a bridge. Start with {@code -Dviaversion.blueprint.stockBlockAcks=true} to use the stock
     * timing anyway.
     */
    private static final boolean BLUEPRINT_ORDERING = MicroServersSupport.isBlueprint()
        && !Boolean.getBoolean("viaversion.blueprint.stockBlockAcks");

    private final ViaVersionPlugin plugin;

    public BukkitAckSequenceProvider(final ViaVersionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void handleSequence(final UserConnection connection, final int sequence) {
        if (BLUEPRINT_ORDERING) {
            handleSequenceBlueprint(connection, sequence);
            return;
        }

        final SequenceStorage sequenceStorage = connection.get(SequenceStorage.class);
        final int previousSequence = sequenceStorage.setSequenceId(sequence);
        if (previousSequence == -1) {
            final ProtocolVersion serverProtocolVersion = connection.getProtocolInfo().serverProtocolVersion();
            final long delay = serverProtocolVersion.newerThan(ProtocolVersion.v1_8) && serverProtocolVersion.olderThan(ProtocolVersion.v1_14) ? 2 : 1;

            if (plugin.isEnabled()) {
                plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, new AckSequenceTask(connection, sequenceStorage), delay);
            }
        }
    }

    /**
     * Coalesce arrivals before the main-thread hop, then freeze that batch before waiting two
     * game ticks. Packets arriving during that wait need their own hop and ordering delay.
     */
    private void handleSequenceBlueprint(final UserConnection connection, final int sequence) {
        final UUID uuid = connection.getProtocolInfo().getUuid();
        if (uuid == null || !plugin.isEnabled()) return;

        AckedSequence acked = connection.get(AckedSequence.class);
        if (acked == null) {
            // Only this connection's Netty thread installs the coordinator.
            acked = new AckedSequence();
            connection.put(acked);
        }
        if (acked.offer(sequence)) scheduleBatch(connection, acked, uuid);
    }

    private void scheduleBatch(final UserConnection connection, final AckedSequence acked, final UUID uuid) {
        if (!plugin.isEnabled() || !connection.isActive() || connection.isPendingDisconnect()) {
            acked.onRemove();
            return;
        }
        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            if (!plugin.isEnabled() || !connection.isActive() || connection.isPendingDisconnect()) {
                acked.onRemove();
                return;
            }
            final int sequence = acked.takeBatch();
            if (sequence == -1) return;
            final Runnable send = () -> {
                try {
                    if (connection.isActive() && !connection.isPendingDisconnect()) sendAck(connection, acked, sequence);
                } finally {
                    if (acked.finishBatch()) scheduleBatch(connection, acked, uuid);
                }
            };
            // Bukkit lookup stays on the main thread; the frozen sequence was received before this hop.
            final Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !MicroServersSupport.runOnGameThread(player.getWorld(), send, 2L)) {
                plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, send, 2L);
            }
        }, 1L);
    }

    private static void sendAck(final UserConnection connection, final AckedSequence acked, final int sequence) {
        if (!acked.raiseTo(sequence)) {
            return; // a later sequence was already acked, which covers this one
        }
        try {
            final PacketWrapper ackPacket = PacketWrapper.create(ClientboundPackets1_19.BLOCK_CHANGED_ACK, connection);
            ackPacket.write(Types.VAR_INT, sequence);
            ackPacket.scheduleSend(Protocol1_18_2To1_19.class);
        } catch (final Exception e) {
            Via.getPlatform().getLogger().log(Level.WARNING, "Failed to send block changed ack packet", e);
        }
    }

    /** At most one main-thread hop or ordered game-thread send is pending per connection. */
    static final class AckedSequence implements StorableObject {
        private int highest = -1;
        private int waiting = -1;
        private int inFlight = -1;
        private boolean scheduled;
        private boolean removed;

        synchronized boolean offer(final int sequence) {
            if (removed || sequence <= highest || sequence <= waiting || sequence <= inFlight) return false;
            waiting = sequence;
            if (scheduled) return false;
            scheduled = true;
            return true;
        }

        synchronized int takeBatch() {
            if (removed) return -1;
            inFlight = waiting;
            waiting = -1;
            return inFlight;
        }

        synchronized boolean raiseTo(final int sequence) {
            if (removed || sequence <= highest) return false;
            highest = sequence;
            return true;
        }

        synchronized boolean finishBatch() {
            inFlight = -1;
            if (!removed && waiting > highest) return true;
            scheduled = false;
            return false;
        }

        @Override
        public synchronized void onRemove() {
            removed = true;
            waiting = -1;
        }
    }
}
