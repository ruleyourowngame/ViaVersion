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
package com.viaversion.viaversion.bukkit.handlers;

import com.viaversion.viaversion.util.ByteBufUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

final class BlueprintBufferOwnership {
    // No server-class dependency: old Blueprint and other Bukkit servers retain the copy path.
    private static final AttributeKey<Integer> OWNERSHIP =
        AttributeKey.valueOf("blueprint:outbound-buffer-ownership");

    private static final MethodHandle IS_READ_ONLY = readOnlyAccessor();

    private static MethodHandle readOnlyAccessor() {
        try {
            // Bukkit still compiles against Netty 4.0; the capability is advertised only
            // by newer Blueprint servers, where this method is available at runtime.
            return MethodHandles.publicLookup().findVirtual(ByteBuf.class, "isReadOnly",
                MethodType.methodType(boolean.class));
        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }

    private static boolean writable(ByteBuf input) {
        if (IS_READ_ONLY == null) {
            return false;
        }
        try {
            return !(boolean) IS_READ_ONLY.invokeExact(input);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    static ByteBuf prepare(Channel channel, ByteBuf input, ByteBufAllocator allocator) {
        Integer version = channel.attr(OWNERSHIP).get();
        if (Integer.valueOf(1).equals(version) && input.refCnt() == 1 && input.readerIndex() == 0 && writable(input)
            && input.unwrap() == null && !(input instanceof CompositeByteBuf)
            && input.maxCapacity() == Integer.MAX_VALUE) {
            // Borrow until encode returns: MessageToMessageEncoder still owns and releases this
            // reference. Retaining here would hide exclusive ownership from guarded transforms.
            return input;
        }
        return ByteBufUtil.copy(allocator, input);
    }

    private BlueprintBufferOwnership() { }
}
