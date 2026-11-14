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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdleTickDeferralTest {

    /** Stand-in game loops: tasks queue until the test runs them. */
    static final class Loops implements IdleTickDeferral.Loops {
        final Map<Object, Object> owners = new HashMap<>();
        final Map<Object, List<Runnable>> queued = new HashMap<>();
        final Set<Object> stopped = new HashSet<>();
        final Set<Object> offline = new HashSet<>();

        @Override
        public Object ownerOf(final Object player) {
            return owners.get(player);
        }

        @Override
        public void execute(final Object loop, final Runnable task) {
            if (stopped.contains(loop)) throw new IllegalStateException("stopped");
            queued.computeIfAbsent(loop, key -> new ArrayList<>()).add(task);
        }

        @Override
        public boolean isOnline(final Object player) {
            return !offline.contains(player);
        }

        void runLoop(final Object loop) {
            final List<Runnable> tasks = queued.remove(loop);
            if (tasks != null) tasks.forEach(Runnable::run);
        }
    }

    record Tick(Object player, Object packet, boolean advance) {
    }

    private final Loops loops = new Loops();
    private final List<Tick> ticks = new ArrayList<>();
    private final IdleTickDeferral deferral = new IdleTickDeferral(loops, (player, tracker, packet, advance) -> {
        ticks.add(new Tick(player, packet, advance));
        if (advance) tracker.incrementIdlePacket();
    });
    private final Object gameA = "game-a", gameB = "game-b", packet = new Object();

    @Test
    void aPlayerTheMainThreadTicksIsTickedInline() {
        final MovementTracker tracker = new MovementTracker();
        deferral.tick("hub", tracker, packet);
        assertEquals(List.of(new Tick("hub", packet, true)), ticks);
        deferral.flush();
        assertTrue(loops.queued.isEmpty());
    }

    @Test
    void gameLoopPlayersAreHandedToTheirLoopInOneTaskEach() {
        loops.owners.put("a1", gameA);
        loops.owners.put("a2", gameA);
        loops.owners.put("b1", gameB);
        final MovementTracker a1 = new MovementTracker(), a2 = new MovementTracker(), b1 = new MovementTracker();
        final long before = a1.getNextIdlePacket();

        deferral.tick("a1", a1, packet);
        deferral.tick("a2", a2, packet);
        deferral.tick("b1", b1, packet);
        assertEquals(List.of(), ticks, "nothing runs on the main thread");
        assertTrue(a1.getNextIdlePacket() > before, "advanced when handed over, so the next pass does not queue it again");

        deferral.flush();
        assertEquals(1, loops.queued.get(gameA).size());
        assertEquals(1, loops.queued.get(gameB).size());
        assertEquals(List.of(), ticks, "the loops run them, not the flush");

        loops.runLoop(gameA);
        assertEquals(List.of(new Tick("a1", packet, false), new Tick("a2", packet, false)), ticks);
        loops.runLoop(gameB);
        assertEquals(new Tick("b1", packet, false), ticks.get(2));
    }

    @Test
    void aFlushHandsOverEachPassOnce() {
        loops.owners.put("a1", gameA);
        deferral.tick("a1", new MovementTracker(), packet);
        deferral.flush();
        deferral.flush();
        assertEquals(1, loops.queued.get(gameA).size());
    }

    @Test
    void aPlayerWhoLeftTheLoopOrWentOfflineBeforeItRanIsSkipped() {
        loops.owners.put("moved", gameA);
        loops.owners.put("quit", gameA);
        loops.owners.put("stays", gameA);
        deferral.tick("moved", new MovementTracker(), packet);
        deferral.tick("quit", new MovementTracker(), packet);
        deferral.tick("stays", new MovementTracker(), packet);
        deferral.flush();

        loops.owners.put("moved", gameB);
        loops.offline.add("quit");
        loops.runLoop(gameA);

        assertEquals(List.of(new Tick("stays", packet, false)), ticks);
    }

    @Test
    void aStoppedLoopsPlayersAreTickedOnTheCallingThread() {
        loops.owners.put("a1", gameA);
        loops.stopped.add(gameA);
        deferral.tick("a1", new MovementTracker(), packet);
        deferral.flush();
        assertEquals(List.of(new Tick("a1", packet, false)), ticks);
    }
}
