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

import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocols.v1_8to1_9.storage.MovementTracker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OwnerLoopIdleChecksTest {

    /** Stand-in tick domains: game loops with players, and a "current thread" the test sets. */
    static final class Domains implements OwnerLoopIdleChecks.Domains {
        final Map<Object, List<Object>> loops = new LinkedHashMap<>(); // running game loops and their players
        final List<Object> mainThreadPlayers = new ArrayList<>();
        final Map<Object, List<Runnable>> everyTick = new HashMap<>();
        final Set<Object> stopped = new HashSet<>();
        Object currentThread = "main";
        RuntimeException failure;

        @Override
        public Iterable<?> gameLoops() {
            if (failure != null) throw failure;
            return new ArrayList<>(loops.keySet());
        }

        @Override
        public Iterable<?> mainThreadPlayers() {
            return mainThreadPlayers;
        }

        @Override
        public Iterable<?> players(final Object loop) {
            assertSame(loop, currentThread, "a loop's players are read on its own thread");
            return loops.getOrDefault(loop, List.of());
        }

        @Override
        public boolean isOwnedByCurrentThread(final Object loop) {
            return loop == currentThread;
        }

        @Override
        public boolean isRunning(final Object loop) {
            return !stopped.contains(loop);
        }

        @Override
        public void runEveryTick(final Object loop, final Runnable task) {
            if (stopped.contains(loop)) throw new IllegalStateException("stopped");
            everyTick.computeIfAbsent(loop, key -> new ArrayList<>()).add(task);
        }

        /** One tick of {@code loop}: its every-tick tasks, on its thread. */
        void tick(final Object loop) {
            final Object previous = currentThread;
            currentThread = loop;
            try {
                everyTick.getOrDefault(loop, List.of()).forEach(Runnable::run);
            } finally {
                currentThread = previous;
            }
        }
    }

    record Checked(Object player, Object thread, long now) {
    }

    private final Domains domains = new Domains();
    private final List<Checked> checked = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private long clock = 1000;
    private final OwnerLoopIdleChecks checks = new OwnerLoopIdleChecks(domains,
        (player, now) -> checked.add(new Checked(player, domains.currentThread, now)), () -> clock, failures::add);
    private final Object gameA = "game-a", gameB = "game-b";

    @Test
    void theMainThreadChecksOnlyThePlayersInItsOwnWorlds() {
        domains.loops.put(gameA, List.of("a1", "a2"));
        domains.loops.put(gameB, List.of("b1"));
        domains.mainThreadPlayers.add("hub");

        assertTrue(checks.mainPass());

        assertEquals(List.of(new Checked("hub", "main", 1000)), checked);
    }

    @Test
    void eachGameLoopChecksItsOwnPlayersOnItsOwnThreadEveryTick() {
        domains.loops.put(gameA, List.of("a1", "a2"));
        domains.loops.put(gameB, List.of("b1"));
        checks.mainPass();

        domains.tick(gameA);
        clock = 1050;
        domains.tick(gameB);
        domains.tick(gameA);

        assertEquals(List.of(
            new Checked("a1", gameA, 1000), new Checked("a2", gameA, 1000),
            new Checked("b1", gameB, 1050),
            new Checked("a1", gameA, 1050), new Checked("a2", gameA, 1050)), checked);
    }

    @Test
    void eachGameLoopIsScheduledOnce() {
        domains.loops.put(gameA, List.of("a1"));
        checks.mainPass();
        checks.mainPass();
        checks.mainPass();

        assertEquals(1, domains.everyTick.get(gameA).size());
        domains.tick(gameA);
        assertEquals(List.of(new Checked("a1", gameA, 1000)), checked);
    }

    @Test
    void aGameLoopCreatedLaterIsScheduledByTheNextMainPass() {
        checks.mainPass();
        domains.loops.put(gameA, List.of("a1"));
        domains.tick(gameA);
        assertEquals(List.of(), checked, "not scheduled yet");

        checks.mainPass();
        domains.tick(gameA);
        assertEquals(List.of(new Checked("a1", gameA, 1000)), checked);
    }

    @Test
    void aPlayerIsCheckedByWhicheverLoopOwnsTheirWorldAtTheTime() {
        final List<Object> inA = new ArrayList<>(List.of("mover", "a1"));
        final List<Object> inB = new ArrayList<>(List.of("b1"));
        domains.loops.put(gameA, inA);
        domains.loops.put(gameB, inB);
        checks.mainPass();

        // a cross-world move, in the main thread's exclusive tick
        inA.remove("mover");
        inB.add("mover");
        domains.tick(gameA);
        domains.tick(gameB);

        assertEquals(List.of(
            new Checked("a1", gameA, 1000),
            new Checked("b1", gameB, 1000), new Checked("mover", gameB, 1000)), checked);
    }

    @Test
    void aPassThatDoesNotRunOnItsLoopsThreadChecksNobody() {
        domains.loops.put(gameA, List.of("a1"));
        checks.mainPass();

        // e.g. a stopping loop's leftover tasks handed to the main thread
        domains.everyTick.get(gameA).forEach(Runnable::run);

        assertEquals(List.of(), checked);
    }

    @Test
    void aGameLoopThatStoppedBeforeItWasScheduledIsLeftToTheMainThread() {
        domains.loops.put(gameA, List.of("a1"));
        domains.stopped.add(gameA);

        assertTrue(checks.mainPass());

        assertNull(domains.everyTick.get(gameA));
        assertEquals(List.of(), failures);
    }

    @Test
    void stoppedLoopsAreForgottenWhenANewOneIsScheduled() {
        domains.loops.put(gameA, List.of());
        checks.mainPass();
        domains.loops.remove(gameA);
        domains.stopped.add(gameA);
        domains.loops.put(gameB, List.of());
        checks.mainPass();

        assertEquals(1, checks.scheduledLoops());
    }

    @Test
    void aFailedCallHandsEveryCheckBackToTheIdlePacketTask() {
        domains.loops.put(gameA, List.of("a1"));
        domains.mainThreadPlayers.add("hub");
        checks.mainPass();
        domains.failure = new IllegalArgumentException("MicroServers.games failed");

        assertFalse(checks.mainPass());
        assertFalse(checks.mainPass(), "and it stays with it");
        domains.tick(gameA);

        assertEquals(List.of(new Checked("hub", "main", 1000)), checked, "the game loop no longer checks its players");
        assertEquals(List.of(domains.failure), failures, "reported once");
    }

    @Test
    void aDueTrackerIsOneTheIdlePacketTaskWouldTick() {
        final UserConnectionImpl connection = new UserConnectionImpl(null, false);
        assertNull(OwnerLoopIdleChecks.dueTracker(null, 0), "no connection: not a Via player");
        assertNull(OwnerLoopIdleChecks.dueTracker(connection, 0), "no tracker: not a 1.9+ client");

        final MovementTracker tracker = new MovementTracker();
        connection.put(tracker);
        tracker.incrementIdlePacket();
        final long next = tracker.getNextIdlePacket();
        assertNull(OwnerLoopIdleChecks.dueTracker(connection, next - 1), "not due yet");
        assertSame(tracker, OwnerLoopIdleChecks.dueTracker(connection, next));
        assertSame(tracker, OwnerLoopIdleChecks.dueTracker(connection, next + 1));

        connection.setActive(false);
        assertNull(OwnerLoopIdleChecks.dueTracker(connection, next), "disconnected");
    }
}
