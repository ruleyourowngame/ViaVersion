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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.bukkit.platform.MicroServersSupport;
import com.viaversion.viaversion.bukkit.util.NMSUtil;
import com.viaversion.viaversion.protocols.v1_8to1_9.provider.MovementTransmitterProvider;
import com.viaversion.viaversion.protocols.v1_8to1_9.storage.MovementTracker;
import com.viaversion.viaversion.util.PipelineUtil;
import io.netty.channel.ChannelHandlerContext;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class BukkitViaMovementTransmitter extends MovementTransmitterProvider {
    private static boolean USE_NMS = true;
    // Used for packet mode
    private Object idlePacket;
    private Object idlePacket2;
    // Use for nms
    private PlayerTicker ticker;
    // Blueprint MicroServers: idle ticks handed to the loop that owns the player's world; null without them
    private IdleTickDeferral deferral;

    public BukkitViaMovementTransmitter() {
        USE_NMS = Via.getConfig().isNMSPlayerTicking();

        Class<?> idlePacketClass;
        try {
            idlePacketClass = NMSUtil.nms("PacketPlayInFlying");
        } catch (ClassNotFoundException e) {
            return; // We'll hope this is 1.9.4+
        }
        try {
            idlePacket = idlePacketClass.newInstance();
            idlePacket2 = idlePacketClass.newInstance();

            Field flying = idlePacketClass.getDeclaredField("f");
            flying.setAccessible(true);

            flying.set(idlePacket2, true);
        } catch (NoSuchFieldException | InstantiationException | IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException("Couldn't make player idle packet, help!", e);
        }
        if (USE_NMS) {
            final Class<?> craftPlayer;
            final Class<?> entityPlayer;
            final Class<?> playerConnection;
            try {
                craftPlayer = NMSUtil.obc("entity.CraftPlayer");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Couldn't find CraftPlayer", e);
            }
            try {
                entityPlayer = NMSUtil.nms("EntityPlayer");
                playerConnection = NMSUtil.nms("PlayerConnection");
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Couldn't find Player Connection", e);
            }
            ticker = new PlayerTicker(craftPlayer, entityPlayer, playerConnection, idlePacketClass,
                e -> Via.getPlatform().getLogger().log(Level.WARNING, "Failed to handle idle packet", e));
            final IdleTickDeferral.Loops loops = MicroServerLoops.detect();
            if (loops != null) {
                deferral = new IdleTickDeferral(loops, (player, tracker, packet, advance) -> ticker.tick(player, tracker, packet, advance));
                Via.getPlatform().getLogger().info("Idle player ticks run on the MicroServer that ticks the player's world");
            }
        }
    }

    public Object getFlyingPacket() {
        if (idlePacket == null)
            throw new NullPointerException("Could not locate flying packet");

        return idlePacket;
    }

    public Object getGroundPacket() {
        if (idlePacket == null)
            throw new NullPointerException("Could not locate flying packet");

        return idlePacket2;
    }

    @Override
    public void sendPlayer(UserConnection info) {
        sendPlayer(info, info.get(MovementTracker.class));
    }

    @Override
    public void sendPlayer(final UserConnection info, final MovementTracker movementTracker) {
        if (USE_NMS) {
            Player player = Bukkit.getPlayer(info.getProtocolInfo().getUuid());
            if (player != null) {
                final Object packet = movementTracker.isGround() ? idlePacket2 : idlePacket;
                if (deferral != null && Bukkit.isPrimaryThread()) {
                    deferral.tick(player, movementTracker, packet);
                } else {
                    ticker.tick(player, movementTracker, packet, true);
                }
            }
        } else {
            ChannelHandlerContext context = PipelineUtil.getContextBefore("decoder", info.getChannel().pipeline());
            if (context == null) {
                return;
            }

            info.getChannel().eventLoop().execute(() -> {
                MovementTracker tracker = info.get(MovementTracker.class);
                if (tracker == null) {
                    return;
                }

                if (tracker.isGround()) {
                    context.fireChannelRead(getGroundPacket());
                } else {
                    context.fireChannelRead(getFlyingPacket());
                }
                tracker.incrementIdlePacket();
            });
        }
    }

    @Override
    public void afterIdlePass() {
        if (deferral != null) {
            deferral.flush();
        }
    }

    /**
     * Ticks a player the way a 1.8 client's idle flying packet does: {@code PlayerConnection#a(PacketPlayInFlying)}
     * on the player's connection. The handles are resolved once; through {@link Method#invoke} every idle player
     * paid three reflective calls per tick, on the main thread.
     */
    static final class PlayerTicker {
        private final MethodHandle getHandle;    // (CraftPlayer) -> EntityPlayer
        private final MethodHandle connection;   // (Object) -> PlayerConnection
        private final MethodHandle handleFlying; // (Object, Object) -> void
        private final Consumer<Throwable> failure;

        PlayerTicker(final Class<?> craftPlayer, final Class<?> entityPlayer, final Class<?> playerConnection,
                     final Class<?> flyingPacket, final Consumer<Throwable> failure) {
            this.failure = failure;
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            try {
                final Method handle = craftPlayer.getDeclaredMethod("getHandle");
                handle.setAccessible(true);
                this.getHandle = lookup.unreflect(handle).asType(MethodType.methodType(Object.class, Object.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new RuntimeException("Couldn't find CraftPlayer", e);
            }
            try {
                final Field field = entityPlayer.getDeclaredField("playerConnection");
                field.setAccessible(true);
                this.connection = lookup.unreflectGetter(field).asType(MethodType.methodType(Object.class, Object.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new RuntimeException("Couldn't find Player Connection", e);
            }
            try {
                final Method flying = playerConnection.getDeclaredMethod("a", flyingPacket);
                flying.setAccessible(true);
                this.handleFlying = lookup.unreflect(flying).asType(MethodType.methodType(void.class, Object.class, Object.class));
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new RuntimeException("Couldn't find CraftPlayer", e);
            }
        }

        void tick(final Object player, final MovementTracker tracker, final Object packet, final boolean advance) {
            try {
                final Object entityPlayer = (Object) getHandle.invokeExact(player);
                final Object pc = (Object) connection.invokeExact(entityPlayer);
                if (pc != null) {
                    handleFlying.invokeExact(pc, packet);
                    // Tick world
                    if (advance) {
                        tracker.incrementIdlePacket();
                    }
                }
            } catch (Throwable e) {
                // Method#invoke wrapped whatever the tick threw, which was logged here instead of aborting the task's
                // pass over the remaining connections.
                failure.accept(e);
            }
        }
    }

    /**
     * {@link IdleTickDeferral.Loops} over Blueprint's MicroServers ({@link MicroServersSupport}). {@code null} off
     * Blueprint, or with {@code -Dviaversion.idleTicksOnOwnerLoop=false}.
     */
    static final class MicroServerLoops implements IdleTickDeferral.Loops {

        static IdleTickDeferral.Loops detect() {
            if (!Boolean.parseBoolean(System.getProperty("viaversion.idleTicksOnOwnerLoop", "true")) || !MicroServersSupport.isBlueprint()) {
                return null;
            }
            return new MicroServerLoops();
        }

        @Override
        public Object ownerOf(final Object player) {
            return MicroServersSupport.gameLoopOf(((Player) player).getWorld());
        }

        @Override
        public void execute(final Object loop, final Runnable task) {
            MicroServersSupport.execute(loop, task);
        }

        @Override
        public boolean isOnline(final Object player) {
            return ((Player) player).isOnline();
        }
    }
}
