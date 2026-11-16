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

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.protocols.v1_8to1_9.storage.MovementTracker;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Runs the idle player check on the thread that ticks the player, on servers that tick worlds on their own threads
 * (Blueprint's MicroServers).
 *
 * <p>The idle packet task runs in every tick of the main thread, which on such a server is exclusive: every game loop
 * waits while it runs. Its pass over every connection cost the main thread about 7% of its tick at 455 players, even
 * after the ticks themselves were handed to the game loops. Here every game loop checks its own players instead, at
 * the start of each of its ticks, before its players' packets, which is where the handed-over tick used to run. The
 * main thread only checks the players in the worlds it ticks, and makes sure every game loop has its check scheduled.
 *
 * <p>A player is checked by the thread that owns their world at the time. Worlds and players only change hands in the
 * main thread's exclusive tick, while no game loop runs. A new game loop gets its check from the main thread's next
 * pass. A loop that stops drops its check, and its worlds go back to the main thread.
 */
final class OwnerLoopIdleChecks {

    /** Where players are ticked. */
    interface Domains {

        /** The game loops now running. Main thread. */
        Iterable<?> gameLoops();

        /** The players in the worlds the main thread ticks. Main thread. */
        Iterable<?> mainThreadPlayers();

        /** The players in {@code loop}'s worlds. On {@code loop}'s thread. */
        Iterable<?> players(Object loop);

        /** True on {@code loop}'s own thread, inside its tick. */
        boolean isOwnedByCurrentThread(Object loop);

        boolean isRunning(Object loop);

        /** Runs {@code task} at the start of each of {@code loop}'s ticks from its next one; throws once it has stopped. */
        void runEveryTick(Object loop, Runnable task);
    }

    /** Ticks {@code player} if their idle packet is due at {@code now}. */
    interface Check {
        void check(Object player, long now);
    }

    private final Domains domains;
    private final Check check;
    private final LongSupplier clock;
    private final Consumer<Throwable> failure;
    // main thread only
    private final Set<Object> scheduled = Collections.newSetFromMap(new IdentityHashMap<>());
    private final AtomicBoolean failed = new AtomicBoolean();

    OwnerLoopIdleChecks(final Domains domains, final Check check, final LongSupplier clock, final Consumer<Throwable> failure) {
        this.domains = domains;
        this.check = check;
        this.clock = clock;
        this.failure = failure;
    }

    /**
     * The main thread's pass, in place of the idle packet task's own. Schedules the check on every game loop that does
     * not have it yet, then checks the main thread's own players.
     *
     * @return false once a call into the server failed: the caller then checks every player itself, and the game
     * loops stop checking theirs
     */
    boolean mainPass() {
        if (failed.get()) {
            return false;
        }
        try {
            for (final Object loop : domains.gameLoops()) {
                if (!scheduled.contains(loop)) {
                    schedule(loop);
                }
            }

            final long now = clock.getAsLong();
            for (final Object player : domains.mainThreadPlayers()) {
                check.check(player, now);
            }
            return true;
        } catch (final RuntimeException e) {
            fail(e);
            return false;
        }
    }

    private void schedule(final Object loop) {
        // a loop never restarts, so the ones that stopped can go
        scheduled.removeIf(known -> !domains.isRunning(known));
        try {
            domains.runEveryTick(loop, () -> loopPass(loop));
        } catch (final IllegalStateException stopped) {
            return; // it stopped since it was listed; its worlds are the main thread's again
        }
        scheduled.add(loop);
    }

    /** {@code loop}'s pass over its own players, at the start of each of its ticks. */
    void loopPass(final Object loop) {
        // the main thread checks everyone once this has failed; a task the loop hands on when it stops is not its tick
        if (failed.get() || !domains.isOwnedByCurrentThread(loop)) {
            return;
        }
        try {
            final long now = clock.getAsLong();
            for (final Object player : domains.players(loop)) {
                check.check(player, now);
            }
        } catch (final RuntimeException e) {
            fail(e);
        }
    }

    /** Main thread. The game loops whose check is scheduled. */
    int scheduledLoops() {
        return scheduled.size();
    }

    private void fail(final RuntimeException e) {
        if (failed.compareAndSet(false, true)) {
            failure.accept(e);
        }
    }

    /**
     * {@code connection}'s tracker if its idle packet is due at {@code now}; {@code null} for a connection the idle
     * packet task skips. The same conditions as the task's.
     */
    static @Nullable MovementTracker dueTracker(final @Nullable UserConnection connection, final long now) {
        if (connection == null || !connection.isActive()) {
            return null;
        }
        final MovementTracker tracker = connection.get(MovementTracker.class);
        return tracker != null && tracker.getNextIdlePacket() <= now ? tracker : null;
    }
}
