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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.exception.InformativeException;

/**
 * Opt-in promise that a handler preserves the built-in legacy redstone-dust conversion for
 * a 1.8 dust packet with count zero. This is not a general stateless-handler annotation.
 * Such a packet must not send additional packets, cancel, or access per-user rewrite state.
 */
public final class LegacyDustHandler implements PacketHandler {
    private final PacketHandler delegate;

    private LegacyDustHandler(PacketHandler delegate) {
        this.delegate = delegate;
    }

    public static PacketHandler wrap(PacketHandler delegate) {
        return new LegacyDustHandler(delegate);
    }

    @Override
    public void handle(PacketWrapper wrapper) throws InformativeException {
        delegate.handle(wrapper);
    }
}
