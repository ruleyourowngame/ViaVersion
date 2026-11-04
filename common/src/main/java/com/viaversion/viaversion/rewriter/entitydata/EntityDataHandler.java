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

package com.viaversion.viaversion.rewriter.entitydata;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entities.EntityType;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import org.checkerframework.checker.nullness.qual.Nullable;

@FunctionalInterface
public interface EntityDataHandler {

    /**
     * Handles an entity data entry of.
     *
     * @param event entity data event
     * @param data  entity data, convenience parameter for {@link EntityDataHandlerEvent#data()}
     */
    void handle(EntityDataHandlerEvent event, EntityData data);

    /**
     * Opt-in handler for transformations that do not need an event or extra data.
     * Custom event handlers continue to receive their own event for each data entry.
     */
    @FunctionalInterface
    interface Direct extends EntityDataHandler {
        /**
         * @return true to cancel this entry, false to continue filtering
         */
        boolean handleData(UserConnection connection, @Nullable EntityType type, EntityData data);

        @Override
        default void handle(EntityDataHandlerEvent event, EntityData data) {
            if (handleData(event.user(), event.entityType(), data)) {
                event.cancel();
            }
        }
    }
}
