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

import com.viaversion.viaversion.protocols.v1_8to1_9.storage.MovementTracker;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BukkitViaMovementTransmitter.PlayerTicker} against stand-ins with the NMS shapes it binds to: a
 * {@code getHandle()} on the player, a {@code playerConnection} field on the handle, and an {@code a(flying)} on the
 * connection.
 */
class BukkitIdlePlayerTickerTest {

    static final class Flying {
    }

    static final class Connection {
        final List<Object> handled = new ArrayList<>();
        RuntimeException failWith;

        public void a(final Flying packet) {
            if (failWith != null) throw failWith;
            handled.add(packet);
        }
    }

    static final class Handle {
        public Connection playerConnection;
    }

    static final class CraftPlayer {
        final Handle handle = new Handle();

        public Handle getHandle() {
            return handle;
        }
    }

    private final List<Throwable> failures = new ArrayList<>();
    private final BukkitViaMovementTransmitter.PlayerTicker ticker =
        new BukkitViaMovementTransmitter.PlayerTicker(CraftPlayer.class, Handle.class, Connection.class, Flying.class, failures::add);

    @Test
    void handsThePacketToThePlayersConnectionAndAdvancesTheTracker() {
        final CraftPlayer player = new CraftPlayer();
        player.handle.playerConnection = new Connection();
        final MovementTracker tracker = new MovementTracker();
        final long before = tracker.getNextIdlePacket();
        final Flying packet = new Flying();

        ticker.tick(player, tracker, packet);

        assertEquals(List.of(packet), player.handle.playerConnection.handled);
        assertTrue(tracker.getNextIdlePacket() > before, "the next idle packet is scheduled");
        assertEquals(List.of(), failures);
    }

    @Test
    void aPlayerWithoutAConnectionIsSkippedAndKeepsItsSchedule() {
        final CraftPlayer player = new CraftPlayer();
        final MovementTracker tracker = new MovementTracker();
        final long before = tracker.getNextIdlePacket();

        ticker.tick(player, tracker, new Flying());

        assertEquals(before, tracker.getNextIdlePacket());
        assertEquals(List.of(), failures);
    }

    // Method#invoke wrapped this and the transmitter logged it; it must not escape into the task's loop
    @Test
    void aFailingTickIsReportedNotThrown() {
        final CraftPlayer player = new CraftPlayer();
        player.handle.playerConnection = new Connection();
        player.handle.playerConnection.failWith = new IllegalStateException("tick failed");
        final MovementTracker tracker = new MovementTracker();
        final long before = tracker.getNextIdlePacket();

        assertDoesNotThrow(() -> ticker.tick(player, tracker, new Flying()));

        assertEquals(1, failures.size());
        assertEquals("tick failed", failures.get(0).getMessage());
        assertEquals(before, tracker.getNextIdlePacket(), "a failed tick is retried next time");
    }

    @Test
    void anObjectThatIsNotACraftPlayerIsReportedNotThrown() {
        assertDoesNotThrow(() -> ticker.tick(new Object(), new MovementTracker(), new Flying()));
        assertEquals(1, failures.size());
        assertInstanceOf(ClassCastException.class, failures.get(0));
    }
}
