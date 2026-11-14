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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hands idle player ticks to the loop that ticks the player's world, on servers that tick worlds on their own
 * threads (Blueprint's MicroServers).
 *
 * <p>The idle packet task runs in the main thread's tick, which on such a server is exclusive: every game loop waits
 * while it runs. A player whose world a game loop owns gets their packets handled on that loop, so the idle tick is
 * handed to it too, in one task per loop per pass, and runs at the start of that loop's next tick, before its
 * players' packets. Players in worlds the main thread ticks are still ticked inline.
 *
 * <p>The tracker is advanced when the tick is handed over, so the next pass does not hand the same tick over again
 * before the loop has run it. If the player has left the loop's worlds or gone offline by then, that one tick is
 * dropped. Main thread only.
 */
final class IdleTickDeferral {

    /** Where a player's world is ticked. */
    interface Loops {

        /** The game loop that ticks {@code player}'s world, or {@code null} when the main thread does. */
        Object ownerOf(Object player);

        /** Runs {@code task} on {@code loop}'s thread at the start of its next tick; throws once it has stopped. */
        void execute(Object loop, Runnable task);

        boolean isOnline(Object player);
    }

    /** Ticks a player; {@code advance} is false when the tracker was already advanced. */
    interface Ticker {
        void tick(Object player, MovementTracker tracker, Object packet, boolean advance);
    }

    private record Pending(Object player, MovementTracker tracker, Object packet) {
    }

    private final Loops loops;
    private final Ticker ticker;
    private final Map<Object, List<Pending>> pending = new IdentityHashMap<>();

    IdleTickDeferral(final Loops loops, final Ticker ticker) {
        this.loops = loops;
        this.ticker = ticker;
    }

    /** Ticks {@code player} now, or queues the tick for the loop that owns their world. */
    void tick(final Object player, final MovementTracker tracker, final Object packet) {
        final Object owner = loops.ownerOf(player);
        if (owner == null) {
            ticker.tick(player, tracker, packet, true);
            return;
        }

        tracker.incrementIdlePacket();
        pending.computeIfAbsent(owner, key -> new ArrayList<>()).add(new Pending(player, tracker, packet));
    }

    /** Hands this pass's queued ticks to their loops. */
    void flush() {
        if (pending.isEmpty()) {
            return;
        }

        for (final Map.Entry<Object, List<Pending>> entry : pending.entrySet()) {
            final Object owner = entry.getKey();
            final List<Pending> players = entry.getValue();
            try {
                loops.execute(owner, () -> {
                    for (final Pending queued : players) {
                        if (loops.ownerOf(queued.player) == owner && loops.isOnline(queued.player)) {
                            ticker.tick(queued.player, queued.tracker, queued.packet, false);
                        }
                    }
                });
            } catch (final RuntimeException stopped) {
                // The loop shut down since its world was looked up. This is still the main thread's exclusive tick,
                // so the players can be ticked right here.
                for (final Pending queued : players) {
                    if (loops.isOnline(queued.player)) {
                        ticker.tick(queued.player, queued.tracker, queued.packet, false);
                    }
                }
            }
        }
        pending.clear();
    }
}
