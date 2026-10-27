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
import java.util.concurrent.atomic.AtomicInteger;
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
     * Netty thread, while the sequenced packet is being decoded. Acks exactly {@code sequence}
     * (not a later one that may arrive before this runs, which the server has not handled yet)
     * from the thread that handles the player's packets, two of that thread's ticks after the
     * hop to the main thread. By the hop the packet has been received, so it is handled in that
     * thread's next tick at the latest and its block update leaves in that tick's end-of-tick
     * flush; two ticks on, the ack follows both. The hop is there because Bukkit's player lookup
     * is not safe on netty threads.
     */
    private void handleSequenceBlueprint(final UserConnection connection, final int sequence) {
        final UUID uuid = connection.getProtocolInfo().getUuid();
        if (uuid == null || !plugin.isEnabled()) {
            return;
        }

        AckedSequence acked = connection.get(AckedSequence.class);
        if (acked == null) {
            // only this connection's netty thread gets here, so nothing else puts one
            acked = new AckedSequence();
            connection.put(acked);
        }
        final AckedSequence ackedSequence = acked;

        plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            final Runnable send = () -> sendAck(connection, ackedSequence, sequence);
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

    /** The highest sequence acked on this connection; acks are sent in increasing order only. */
    private static final class AckedSequence implements StorableObject {
        private final AtomicInteger highest = new AtomicInteger(-1);

        boolean raiseTo(final int sequence) {
            return highest.getAndAccumulate(sequence, Math::max) < sequence;
        }
    }
}
