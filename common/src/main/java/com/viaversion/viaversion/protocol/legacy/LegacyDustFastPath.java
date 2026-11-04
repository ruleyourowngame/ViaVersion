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

package com.viaversion.viaversion.protocol.legacy;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.MappingDataBase;
import com.viaversion.viaversion.api.data.ParticleMappings;
import com.viaversion.viaversion.api.minecraft.Particle;
import com.viaversion.viaversion.api.protocol.AbstractProtocol;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.packet.mapping.PacketMapping;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.misc.DynamicType.DataReader;
import com.viaversion.viaversion.api.type.types.misc.ParticleType;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.base.InitialBaseProtocol;
import com.viaversion.viaversion.protocols.v1_12_2to1_13.rewriter.WorldPacketRewriter1_13;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.Protocol1_20To1_20_2;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.storage.ConfigurationState.BridgePhase;
import com.viaversion.viaversion.protocols.v1_20to1_20_2.storage.ProtocolStorables1_20_2;
import com.viaversion.viaversion.rewriter.ParticleRewriter;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** A connection-local, guarded wire plan. No packet payload is cached across recipients. */
public final class LegacyDustFastPath {
    private static final Class<?> ID_MAPPING = PacketMapping.of(0, null).getClass();
    private static final Class<?> TYPE_MAPPING = PacketMapping.of((PacketType) null, null).getClass();
    private Plan plan;
    private boolean disabled;
    private long transformedPackets;

    public long transformedPackets() {
        return transformedPackets;
    }

    public boolean tryTransform(UserConnection user, int packetId, ByteBuf buffer) {
        if (disabled || packetId != 42 || !eligible(user, buffer)) return false;
        if (plan == null) {
            plan = compile(user);
            if (plan == null) {
                disabled = true;
                return false;
            }
        }
        if (!plan.current(user)) {
            // Custom registrations and pipeline changes always regain the ordinary pipeline.
            disabled = true;
            return false;
        }
        if (buffer.maxCapacity() < plan.outputBytes) return false;
        plan.write(buffer, buffer, buffer.readerIndex());
        transformedPackets++;
        return true;
    }

    private static boolean eligible(UserConnection user, ByteBuf buffer) {
        final var info = user.getProtocolInfo();
        if (!user.isActive() || user.isPendingDisconnect() || user.isClientSide()
            || info.serverProtocolVersion() != ProtocolVersion.v1_8
            || info.getClientState() != State.PLAY || info.getServerState() != State.PLAY
            || info.protocolVersion() == null || !info.protocolVersion().isKnown()
            || info.protocolVersion().olderThan(ProtocolVersion.v1_9)
            || info.protocolVersion().newerThan(ProtocolVersion.v26_3)
            || info.getPipeline().getClass() != ProtocolPipelineImpl.class
            || Via.getManager().debugHandler().enabled()
            || buffer.order() != ByteOrder.BIG_ENDIAN || !buffer.isWritable(0) || buffer.refCnt() != 1 || buffer.readableBytes() != 37) return false;
        final int start = buffer.readerIndex();
        if (buffer.getInt(start) != 30 || buffer.getInt(start + 33) != 0) return false;
        final byte distance = buffer.getByte(start + 4);
        if (distance != 0 && distance != 1) return false;
        for (int offset = 5; offset <= 29; offset += 4) {
            if (!Float.isFinite(buffer.getFloat(start + offset))) return false;
        }
        final Protocol1_20To1_20_2 bridge = info.getPipeline().getProtocol(Protocol1_20To1_20_2.class);
        if (bridge != null) {
            final ProtocolStorables1_20_2 storage = user.storables(bridge);
            if (storage.configurationState().bridgePhase() != BridgePhase.NONE) return false;
        }
        return true;
    }

    private static Plan compile(UserConnection user) {
        final ProtocolPipelineImpl pipeline = (ProtocolPipelineImpl) user.getProtocolInfo().getPipeline();
        final Protocol[] protocols = pipeline.reversedPipes().toArray(new Protocol[0]);
        final List<Step> steps = new ArrayList<>();
        final List<DustMap> maps = new ArrayList<>();
        final List<Reader> readers = new ArrayList<>();
        final PacketWrapperImpl ids = new PacketWrapperImpl(42, null, user);
        for (Protocol protocol : protocols) {
            if (!(protocol instanceof AbstractProtocol<?, ?, ?, ?> p)) return null;
            try {
                final Class<?> owner = p.getClass().getMethod("transform", Direction.class, State.class, PacketWrapper.class).getDeclaringClass();
                if (owner != AbstractProtocol.class && owner != InitialBaseProtocol.class && owner != Protocol1_20To1_20_2.class) return null;
            } catch (ReflectiveOperationException e) { return null; }
            final var rewriter = protocol.getParticleRewriter();
            if (rewriter != null && (!(rewriter instanceof ParticleRewriter<?> particles) || !particles.hasCanonicalLegacyDust())) return null;
            final int inputId = ids.getId();
            final PacketMapping mapping = p.clientboundMapping(State.PLAY, inputId);
            if (mapping != null && mapping.getClass() != ID_MAPPING && mapping.getClass() != TYPE_MAPPING) return null;
            final PacketHandler handler = mapping != null ? mapping.handler() : null;
            if (handler != null && !(handler instanceof LegacyDustHandler)) return null;
            steps.add(new Step(p, inputId, mapping, handler, rewriter));
            if (mapping != null) mapping.applyType(ids);

            final var data = protocol.getMappingData();
            final ParticleMappings particles = data != null ? data.getParticleMappings() : null;
            if (particles != null) {
                if (particles.getClass() != ParticleMappings.class) return null;
                try {
                    if (data.getClass().getMethod("getNewParticleId", int.class).getDeclaringClass() != MappingDataBase.class) return null;
                } catch (ReflectiveOperationException e) { return null; }
                final int dust = particles.id("dust");
                final int mappedDust = particles.mappedId("dust");
                if (dust >= 0 && mappedDust >= 0) {
                    if (particles.getNewId(dust) != mappedDust || particles.isBlockParticle(dust) || particles.isItemParticle(dust)) return null;
                    maps.add(new DustMap(p, data, particles, dust, mappedDust));
                    if (protocol.types() != null && !addReader(readers, p, false, protocol.types().particle(), dust)) return null;
                    if (protocol.mappedTypes() != null && !addReader(readers, p, true, protocol.mappedTypes().particle(), mappedDust)) return null;
                }
            }
        }
        final ProtocolVersion version = user.getProtocolInfo().protocolVersion();
        final int layout = layout(version);
        final ByteBuf input = sample(false), reference = Unpooled.buffer(), direct = Unpooled.buffer();
        try {
            final int id = reference(user, input, reference);
            if (id != ids.getId()) return null;
            reference.readerIndex(0);
            Types.VAR_INT.readPrimitive(reference); // Packet id
            final int dustId;
            if (layout < 3) dustId = reference.readInt();
            else if (layout == 3 || layout == 7) dustId = Types.VAR_INT.readPrimitive(reference);
            else {
                reference.skipBytes(layout >= 6 ? 46 : 45);
                dustId = Types.VAR_INT.readPrimitive(reference);
            }
            final Plan result = new Plan(version, protocols, steps.toArray(new Step[0]), maps.toArray(new DustMap[0]),
                readers.toArray(new Reader[0]), layout, id, dustId, reference.writerIndex());
            result.write(direct, input, 0);
            reference.readerIndex(0);
            if (!ByteBufUtil.equals(reference, direct)) return null;
            final ByteBuf second = sample(true);
            try {
                reference.clear(); direct.clear();
                reference(user, second, reference);
                result.write(direct, second, 0);
                if (!ByteBufUtil.equals(reference, direct)) return null;
            } finally { second.release(); }
            return result;
        } catch (Exception unsupported) {
            return null;
        } finally { input.release(); reference.release(); direct.release(); }
    }

    private static boolean addReader(List<Reader> readers, AbstractProtocol<?, ?, ?, ?> protocol, boolean mapped, ParticleType type, int id) {
        if (type == null || type.getClass() != ParticleType.class) return false;
        final DataReader<Particle> reader = type.reader(id);
        if (reader != ParticleType.Readers.DUST && reader != ParticleType.Readers.DUST1_21_2) return false;
        readers.add(new Reader(protocol, mapped, type, id, reader));
        return true;
    }

    private static int reference(UserConnection user, ByteBuf source, ByteBuf output) throws Exception {
        final ByteBuf copy = source.copy();
        try {
            final PacketWrapperImpl wrapper = new PacketWrapperImpl(42, copy, user);
            user.getProtocolInfo().getPipeline().transform(Direction.CLIENTBOUND, State.PLAY, wrapper);
            wrapper.writeToBuffer(output);
            return wrapper.getId();
        } finally { copy.release(); }
    }

    private static ByteBuf sample(boolean second) {
        return Unpooled.buffer().writeInt(30).writeBoolean(!second)
            .writeFloat(second ? -0F : 1.25F).writeFloat(second ? -1F : 2.5F).writeFloat(second ? 512.75F : -3.75F)
            .writeFloat(second ? 0F : .25F).writeFloat(second ? -.2F : .5F).writeFloat(second ? 1.2F : .75F)
            .writeFloat(second ? .5F : 1F).writeInt(0);
    }

    private static int layout(ProtocolVersion version) {
        if (version.olderThan(ProtocolVersion.v1_13)) return 0;
        if (version.olderThan(ProtocolVersion.v1_15)) return 1;
        if (version.olderThan(ProtocolVersion.v1_19)) return 2;
        if (version.olderThan(ProtocolVersion.v1_20_5)) return 3;
        if (version.olderThan(ProtocolVersion.v1_21_2)) return 4;
        if (version.olderThan(ProtocolVersion.v1_21_4)) return 5;
        if (version.olderThan(ProtocolVersion.v26_3)) return 6;
        return 7;
    }

    private record Step(AbstractProtocol<?, ?, ?, ?> protocol, int inputId, PacketMapping mapping,
                        PacketHandler handler, Object particleRewriter) {
        boolean current() {
            return protocol.clientboundMapping(State.PLAY, inputId) == mapping
                && (mapping == null || mapping.handler() == handler)
                && protocol.getParticleRewriter() == particleRewriter;
        }
    }

    private record DustMap(AbstractProtocol<?, ?, ?, ?> protocol, MappingData data, ParticleMappings mapping, int inputId, int outputId) {
        boolean current() {
            return protocol.getMappingData() == data && data.getParticleMappings() == mapping
                && mapping.getNewId(inputId) == outputId && !mapping.isBlockParticle(inputId) && !mapping.isItemParticle(inputId);
        }
    }

    private record Reader(AbstractProtocol<?, ?, ?, ?> protocol, boolean mapped, ParticleType type, int id, DataReader<Particle> reader) {
        boolean current() {
            final var types = mapped ? protocol.mappedTypes() : protocol.types();
            return types != null && types.particle() == type && type.reader(id) == reader;
        }
    }

    private record Plan(ProtocolVersion version, Protocol[] protocols, Step[] steps, DustMap[] maps, Reader[] readers,
                        int layout, int packetId, int dustId, int outputBytes) {
        boolean current(UserConnection user) {
            if (user.getProtocolInfo().protocolVersion() != version
                || !((ProtocolPipelineImpl) user.getProtocolInfo().getPipeline()).matchesClientboundPath(protocols)) return false;
            for (Step step : steps) if (!step.current()) return false;
            for (DustMap map : maps) if (!map.current()) return false;
            for (Reader reader : readers) if (!reader.current()) return false;
            return true;
        }

        void write(ByteBuf out, ByteBuf source, int start) {
            final boolean distance = source.getBoolean(start + 4);
            final float x = source.getFloat(start + 5), y = source.getFloat(start + 9), z = source.getFloat(start + 13);
            final float rx = source.getFloat(start + 17), gy = source.getFloat(start + 21), bz = source.getFloat(start + 25);
            final float speed = source.getFloat(start + 29);
            if (out.capacity() < outputBytes) out.capacity(outputBytes);
            out.clear();
            Types.VAR_INT.writePrimitive(out, packetId);
            if (layout == 0) {
                out.writeInt(30).writeBoolean(distance).writeFloat(x).writeFloat(y).writeFloat(z)
                    .writeFloat(rx).writeFloat(gy).writeFloat(bz).writeFloat(speed).writeInt(0);
                return;
            }
            final float r = WorldPacketRewriter1_13.redstoneDustColor(speed, rx, true);
            final float g = WorldPacketRewriter1_13.redstoneDustColor(speed, gy, false);
            final float b = WorldPacketRewriter1_13.redstoneDustColor(speed, bz, false);
            if (layout <= 2) out.writeInt(dustId);
            else if (layout == 3) Types.VAR_INT.writePrimitive(out, dustId);
            if (layout == 7) {
                Types.VAR_INT.writePrimitive(out, dustId);
                writeColor(out, r, g, b, true);
            }
            out.writeBoolean(distance);
            if (layout >= 6) out.writeBoolean(false); // Always show
            if (layout == 1) out.writeFloat(x).writeFloat(y).writeFloat(z);
            else out.writeDouble(x).writeDouble(y).writeDouble(z);
            out.writeFloat(0).writeFloat(0).writeFloat(0).writeFloat(0);
            if (layout == 7) {
                out.writeFloat(0).writeFloat(0); // Max y/z speed
                Types.VAR_INT.writePrimitive(out, 1);
                Types.VAR_INT.writePrimitive(out, 0); // Randomization type
            } else {
                out.writeInt(1);
                if (layout >= 4) Types.VAR_INT.writePrimitive(out, dustId);
                writeColor(out, r, g, b, layout >= 5);
            }
        }

        private static void writeColor(ByteBuf out, float r, float g, float b, boolean packed) {
            if (packed) out.writeInt(255 << 24 | (int) (r * 255) << 16 | (int) (g * 255) << 8 | (int) (b * 255));
            else out.writeFloat(r).writeFloat(g).writeFloat(b);
            out.writeFloat(1F); // Scale
        }
    }
}
