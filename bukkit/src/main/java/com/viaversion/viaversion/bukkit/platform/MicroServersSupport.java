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
package com.viaversion.viaversion.bukkit.platform;

import com.viaversion.viaversion.api.Via;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Support for Blueprint's MicroServers, which tick game worlds on their own threads and fire
 * those worlds' events there. A listener of a plugin not marked safe is run on the main thread
 * while the game thread waits, so without this every ViaVersion listener on a hot event
 * (interact, block place, inventory click, held item) costs each game a blocking round trip
 * (~350/s on a 415-player server).
 *
 * <p>ViaVersion's listeners may run on game threads: they touch the event's player, its world,
 * and per-connection storage, which is already shared with the connection's netty thread. Each
 * player's events fire on one thread at a time (its world's game thread, or the main thread
 * during its exclusive tick). Work for the main thread goes through the Bukkit scheduler, whose
 * tasks run in that exclusive tick. State shared across players uses concurrent collections.
 *
 * <p>Start the server with {@code -Dviaversion.microservers.mainThreadListeners=true} to keep
 * the listeners on the main thread.
 */
public final class MicroServersSupport {

    private static final String MICRO_SERVERS = "dev.reximian9k.spigot.micro.MicroServers";
    private static final AtomicBoolean WARNED_DOMAINS = new AtomicBoolean();

    private MicroServersSupport() {
    }

    /**
     * True on Blueprint, whether or not MicroServers is enabled there. False (with one warning)
     * on a Blueprint whose MicroServers API no longer matches.
     */
    public static boolean isBlueprint() {
        if (Domains.FAILURE != null) {
            warnDomainsOnce("Blueprint's MicroServers API did not match what ViaVersion expects", Domains.FAILURE);
        }
        return Domains.OF != null;
    }

    /**
     * Main thread. Runs {@code task} on the game MicroServer thread that ticks {@code world},
     * {@code delayTicks} of that thread's ticks from now.
     *
     * @return false when {@code world} is ticked by the main thread (or this is not Blueprint):
     * nothing was scheduled
     */
    public static boolean runOnGameThread(final World world, final Runnable task, final long delayTicks) {
        if (Domains.OF == null) {
            return false;
        }
        try {
            final Object domain = Domains.OF.invoke(null, world);
            if (domain == null || (Boolean) Domains.IS_GLOBAL.invoke(domain)) {
                return false;
            }
            Domains.RUN_TASK_TIMER.invoke(domain, task, delayTicks, 0L);
            return true;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            warnDomainsOnce("Could not schedule on a Blueprint MicroServers game thread", e);
            return false;
        }
    }

    /** The main-thread fallback can't order block acks behind a game thread, so say so once. */
    private static void warnDomainsOnce(final String reason, final Throwable cause) {
        if (WARNED_DOMAINS.compareAndSet(false, true)) {
            Via.getPlatform().getLogger().log(Level.WARNING, reason + "; block place/break acks fall back to "
                + "main-thread timing, so 1.19+ clients in game worlds may see placed blocks flicker", cause);
        }
    }

    /** {@code MicroServers.of(World)} and the two {@code MicroServer} methods, resolved once. */
    private static final class Domains {
        static final Method OF;
        static final Method IS_GLOBAL;
        static final Method RUN_TASK_TIMER;
        /** Set when Blueprint is present but its API did not resolve; null off Blueprint. */
        static final Throwable FAILURE;

        static {
            Method of = null;
            Method isGlobal = null;
            Method runTaskTimer = null;
            Throwable failure = null;
            Class<?> microServers = null;
            try {
                microServers = Class.forName(MICRO_SERVERS);
            } catch (final ClassNotFoundException | LinkageError notBlueprint) {
                // not Blueprint: nothing to resolve, nothing to report
            }
            if (microServers != null) {
                try {
                    final Class<?> microServer = Class.forName("dev.reximian9k.spigot.micro.MicroServer");
                    isGlobal = microServer.getMethod("isGlobal");
                    runTaskTimer = microServer.getMethod("runTaskTimer", Runnable.class, long.class, long.class);
                    of = microServers.getMethod("of", World.class);
                } catch (final ReflectiveOperationException | LinkageError e) {
                    of = null;
                    failure = e;
                }
            }
            OF = of;
            IS_GLOBAL = isGlobal;
            RUN_TASK_TIMER = runTaskTimer;
            FAILURE = failure;
        }
    }

    /** Marks {@code plugin}'s listeners safe on game threads; does nothing off Blueprint MicroServers. */
    public static void markListenersSafe(final Plugin plugin) {
        if (Boolean.getBoolean("viaversion.microservers.mainThreadListeners")) {
            return;
        }

        final Class<?> microServers;
        try {
            microServers = Class.forName(MICRO_SERVERS);
        } catch (final ClassNotFoundException e) {
            return;
        }

        try {
            microServers.getMethod("markSafe", Plugin.class).invoke(null, plugin);
            plugin.getLogger().info("Blueprint MicroServers: listeners run on game threads");
        } catch (final ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().log(Level.WARNING, "Could not mark listeners safe for MicroServers; they will run on the main thread", e);
        }
    }
}
