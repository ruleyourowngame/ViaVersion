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
package com.viaversion.viaversion.common.protocol;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.data.entity.EntityTracker;
import com.viaversion.viaversion.api.minecraft.entities.EntityType;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.entitydata.types.EntityDataTypes1_9;
import com.viaversion.viaversion.api.protocol.AbstractProtocol;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.api.type.types.version.VersionedTypesHolder;
import com.viaversion.viaversion.api.protocol.packet.ClientboundPacketType;
import com.viaversion.viaversion.api.protocol.packet.ServerboundPacketType;
import com.viaversion.viaversion.data.entity.EntityTrackerBase;
import com.viaversion.viaversion.rewriter.EntityRewriter;
import com.viaversion.viaversion.rewriter.entitydata.EntityDataFilter;
import com.viaversion.viaversion.rewriter.entitydata.EntityDataHandler;
import com.viaversion.viaversion.util.ProtocolLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

final class EntityDataDirectFixture extends EntityRewriter<ClientboundPacketType, EntityDataDirectFixture.TestProtocol> {
    private final boolean legacyDispatch;
    final EntityTracker entities = new EntityTrackerBase(null, null);

    EntityDataDirectFixture(boolean legacyDispatch) {
        super(new TestProtocol());
        this.legacyDispatch = legacyDispatch;
    }

    @Override
    public void registerFilter(EntityDataFilter filter) {
        if (legacyDispatch && filter.handler() instanceof EntityDataHandler.Direct direct) {
            // Exercise the ordinary event-based API, with the same transformation implementation.
            filter = new EntityDataFilter(filter.type(), filter.filterFamily(), filter.dataType(),
                filter.index(), direct::handle);
        }
        super.registerFilter(filter);
    }

    @Override public EntityType typeFromId(int id) { return null; }
    @Override @SuppressWarnings("unchecked")
    public <E extends EntityTracker> E tracker(UserConnection connection) { return (E) entities; }

    static List<EntityData> entries(int count) {
        final List<EntityData> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(new EntityData(i, EntityDataTypes1_9.VAR_INT, i));
        return entries;
    }

    static final class TestProtocol extends AbstractProtocol<ClientboundPacketType, ClientboundPacketType,
        ServerboundPacketType, ServerboundPacketType> {
        private final ProtocolLogger logger = new ProtocolLogger(Logger.getAnonymousLogger(), TestProtocol.class);
        @Override public ProtocolLogger getLogger() { return logger; }
        @Override public VersionedTypesHolder types() { return VersionedTypes.V1_21_4; }
        @Override public VersionedTypesHolder mappedTypes() { return VersionedTypes.V1_21_5; }
    }
}
