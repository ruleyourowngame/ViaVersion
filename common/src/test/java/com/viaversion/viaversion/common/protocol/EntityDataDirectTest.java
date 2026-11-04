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

import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_9;
import com.viaversion.viaversion.api.connection.UserConnection;
import java.lang.reflect.Proxy;
import com.viaversion.viaversion.api.minecraft.entitydata.types.EntityDataTypes1_9;
import com.viaversion.viaversion.common.PlatformTestBase;
import com.viaversion.viaversion.rewriter.entitydata.EntityDataHandler;
import com.viaversion.viaversion.rewriter.entitydata.EntityDataHandlerEvent;
import com.viaversion.viaversion.rewriter.entitydata.EntityDataHandlerEventImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EntityDataDirectTest extends PlatformTestBase {
    @Test
    void builtinsPreserveOrderIndexChangesAndCancellation() {
        final EntityDataDirectFixture rewriter = new EntityDataDirectFixture(false);
        rewriter.filter().mapDataType(id -> EntityDataTypes1_9.VAR_INT);
        rewriter.filter().addIndex(2);
        rewriter.filter().removeIndex(1);
        rewriter.filter().index(2).toIndex(7);
        rewriter.filter().cancel(4);
        final List<EntityData> entries = EntityDataDirectFixture.entries(5);
        rewriter.handleEntityData(1, entries, null);
        assertEquals(List.of(0, 7, 3), entries.stream().map(EntityData::id).toList());
        assertEquals(List.of(0, 2, 3), entries.stream().map(EntityData::getValue).toList());
    }

    @Test
    void cancellationUpdatesRetainedCustomEventAndPreservesExtraData() {
        final EntityDataDirectFixture rewriter = new EntityDataDirectFixture(false);
        final List<EntityDataHandlerEvent> saved = new ArrayList<>();
        rewriter.filter().handler((event, data) -> {
            saved.add(event);
            if (data.id() == 1) event.createExtraData(new EntityData(99, EntityDataTypes1_9.VAR_INT, 99));
        });
        rewriter.filter().handlerDirect((user, type, data) -> data.id() == 1);
        final List<EntityData> entries = EntityDataDirectFixture.entries(3);
        rewriter.handleEntityData(7, entries, null);
        assertEquals(List.of(0, 2, 99), entries.stream().map(EntityData::id).toList());
        assertEquals(3, saved.size()); // Extra entries must not be processed during this pass.
        assertNotSame(saved.get(0), saved.get(1));
        assertNotSame(saved.get(1), saved.get(2));
        assertFalse(saved.get(0).cancelled());
        assertTrue(saved.get(1).cancelled());
        assertEquals(7, saved.get(1).entityId());
        rewriter.handleEntityData(8, EntityDataDirectFixture.entries(1), null);
        assertEquals(1, saved.get(1).data().id());
        assertTrue(saved.get(1).cancelled());
    }

    @Test
    void directFailuresRemoveEntryWithoutDiscardingPriorExtraData() {
        final EntityDataDirectFixture rewriter = new EntityDataDirectFixture(false);
        rewriter.filter().index(1).handler((event, data) ->
            event.createExtraData(new EntityData(99, EntityDataTypes1_9.VAR_INT, 99)));
        rewriter.filter().handlerDirect((user, type, data) -> {
            if (data.id() == 1) throw new IllegalArgumentException("injected handler failure");
            return false;
        });
        final List<EntityData> entries = EntityDataDirectFixture.entries(3);
        rewriter.handleEntityData(1, entries, null);
        assertEquals(List.of(0, 2, 99), entries.stream().map(EntityData::id).toList());
    }

    @Test
    void directCallbackCanCancelAPreviouslyCapturedEvent() {
        final EntityDataDirectFixture r = new EntityDataDirectFixture(false);
        final List<EntityDataHandlerEvent> saved = new ArrayList<>();
        r.filter().handler((event, data) -> saved.add(event));
        r.filter().handlerDirect((user, type, data) -> {
            saved.get(saved.size() - 1).cancel();
            return false;
        });
        final List<EntityData> entries = EntityDataDirectFixture.entries(1);
        r.handleEntityData(1, entries, null);
        assertTrue(entries.isEmpty());
        assertTrue(saved.get(0).cancelled());
    }

    @Test
    void directHandlersStillWorkThroughLegacyEventInterface() {
        final EntityDataHandler.Direct direct = (user, type, data) -> true;
        final EntityData data = EntityDataDirectFixture.entries(1).get(0);
        final EntityDataHandlerEvent event = new EntityDataHandlerEventImpl(null, null, 1, data, List.of(data));
        ((EntityDataHandler) direct).handle(event, data);
        assertTrue(event.cancelled());
    }

    @Test
    void forwardsConnectionEntityTypeAndMarksTrackedEntitySent() {
        final EntityDataDirectFixture r = new EntityDataDirectFixture(false);
        final UserConnection user = (UserConnection) Proxy.newProxyInstance(UserConnection.class.getClassLoader(),
            new Class<?>[]{UserConnection.class}, (proxy, method, args) -> { throw new AssertionError(method); });
        final var tracked = r.entities.addEntity(42, EntityTypes1_9.EntityType.PLAYER);
        r.filter().handlerDirect((connection, type, data) -> {
            assertSame(user, connection);
            assertSame(EntityTypes1_9.EntityType.PLAYER, type);
            return false;
        });
        r.filter().handler((event, data) -> {
            assertSame(user, event.user());
            assertSame(tracked, event.trackedEntity());
        });
        r.handleEntityData(42, EntityDataDirectFixture.entries(1), user);
        assertTrue(tracked.hasSentEntityData());
    }

    @Test
    void typeMapperPreservesSimpleAndSkippedTypeBehavior() {
        for (boolean legacy : new boolean[]{false, true}) {
            final EntityDataDirectFixture simple = new EntityDataDirectFixture(legacy);
            final var sourceType = simple.protocol().types().entityDataTypes().byId(1);
            final var mappedType = simple.protocol().mappedTypes().entityDataTypes().byId(1);
            simple.dataTypeMapper().register();
            final List<EntityData> entries = new ArrayList<>(List.of(new EntityData(0, sourceType, 1)));
            simple.handleEntityData(1, entries, null);
            assertSame(mappedType, entries.get(0).dataType());

            final EntityDataDirectFixture skipped = new EntityDataDirectFixture(legacy);
            skipped.dataTypeMapper().skip(sourceType).register();
            final List<EntityData> untracked = new ArrayList<>(List.of(new EntityData(0, sourceType, 1)));
            untracked.add(new EntityData(1, simple.protocol().types().entityDataTypes().byId(0), (byte) 1));
            skipped.handleEntityData(1, untracked, null);
            assertEquals(1, untracked.size());
            assertSame(simple.protocol().mappedTypes().entityDataTypes().byId(0), untracked.get(0).dataType());
            skipped.entities.addEntity(1, EntityTypes1_9.EntityType.PLAYER);
            final List<EntityData> tracked = new ArrayList<>(List.of(new EntityData(0, sourceType, 1)));
            skipped.handleEntityData(1, tracked, null);
            assertSame(sourceType, tracked.get(0).dataType());
        }
    }

    @Test
    void randomizedPipelinesMatchOrdinaryEventDispatch() {
        final Random random = new Random(0xD1EC7);
        for (int trial = 0; trial < 1000; trial++) {
            final EntityDataDirectFixture direct = new EntityDataDirectFixture(false);
            final EntityDataDirectFixture legacy = new EntityDataDirectFixture(true);
            final int added = random.nextInt(8), removed = random.nextInt(8), cancelled = random.nextInt(8);
            final List<List<EntityDataHandlerEvent>> saved = List.of(new ArrayList<>(), new ArrayList<>());
            int which = 0;
            for (EntityDataDirectFixture r : List.of(direct, legacy)) {
                final List<EntityDataHandlerEvent> events = saved.get(which++);
                r.filter().mapDataType(id -> id == EntityDataTypes1_9.BYTE.typeId() ? null : EntityDataTypes1_9.VAR_INT);
                r.filter().addIndex(added);
                r.filter().handler((event, data) -> {
                    events.add(event);
                    if (data.id() % 4 == 0) event.createExtraData(new EntityData(50, EntityDataTypes1_9.VAR_INT, 50));
                });
                r.filter().removeIndex(removed);
                r.filter().cancel(cancelled);
                r.filter().handlerDirect((user, type, data) -> { data.setValue((int) data.getValue() + 1); return false; });
            }
            final List<EntityData> a = EntityDataDirectFixture.entries(random.nextInt(13));
            if (!a.isEmpty() && random.nextBoolean()) a.set(0, new EntityData(0, EntityDataTypes1_9.BYTE, (byte) 0));
            final List<EntityData> b = new ArrayList<>();
            for (EntityData data : a) b.add(new EntityData(data.id(), data.dataType(), data.getValue()));
            direct.handleEntityData(1, a, null);
            legacy.handleEntityData(1, b, null);
            assertEquals(b, a);
            assertEquals(saved.get(1).size(), saved.get(0).size());
            for (int i = 0; i < saved.get(0).size(); i++) {
                assertEquals(saved.get(1).get(i).cancelled(), saved.get(0).get(i).cancelled());
                assertEquals(saved.get(1).get(i).data(), saved.get(0).get(i).data());
            }
        }
    }
}
