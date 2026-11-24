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
package com.viaversion.viaversion.common.hash;

import com.sun.management.ThreadMXBean;
import com.viaversion.nbt.tag.ByteTag;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.common.PlatformTestLoader;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.data.item.ItemHasherBase;
import com.viaversion.viaversion.protocols.v1_21_4to1_21_5.Protocol1_21_4To1_21_5;
import com.viaversion.viaversion.protocols.v1_21_4to1_21_5.storage.ItemHashStorage1_21_5;
import com.viaversion.viaversion.protocols.v1_21_6to1_21_7.Protocol1_21_6To1_21_7;
import java.lang.management.ManagementFactory;

/**
 * Measures allocation per hashed item on the two clientbound inventory paths: the 1.21.5 data
 * tracking of every component and the before/after item hashes of later protocols.
 */
public final class ItemHashAllocationBenchmark {

    private static final int ITEMS = 200_000;
    private static Object sink;

    public static void main(final String[] args) throws InterruptedException {
        PlatformTestLoader.ensureLoaded();
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Thread allocation counters unavailable");
        }
        bean.setThreadAllocatedMemoryEnabled(true);

        final UserConnectionImpl connection = new UserConnectionImpl(null);
        final Protocol1_21_6To1_21_7 modern = Via.getManager().getProtocolManager().getProtocol(Protocol1_21_6To1_21_7.class);
        modern.init(connection);
        final Protocol1_21_4To1_21_5 tracking = Via.getManager().getProtocolManager().getProtocol(Protocol1_21_4To1_21_5.class);
        tracking.init(connection);

        final ItemHasherBase hasher = new ItemHasherBase(modern, connection);
        final ItemHashStorage1_21_5 storage = new ItemHashStorage1_21_5(tracking, connection);
        final StructuredItem modernItem = menuItem(modern);
        final StructuredItem trackedItem = menuItem(tracking);

        final Runnable hashBoth = () -> {
            for (int i = 0; i < ITEMS; i++) {
                sink = hasher.toHashedItem(modernItem, false);
                sink = hasher.toHashedItem(modernItem, true);
            }
        };
        final Runnable track = () -> {
            for (int i = 0; i < ITEMS; i++) {
                for (final StructuredData<?> data : trackedItem.dataContainer().data().values()) {
                    storage.trackStructuredData(data);
                }
            }
        };

        for (int round = 0; round < 3; round++) {
            hashBoth.run();
            track.run();
        }
        for (int round = 0; round < 3; round++) {
            measure("item-hash-before-after", hashBoth, bean);
            measure("item-hash-1_21_5-track", track, bean);
        }
        // The test platform's scheduler threads would otherwise keep the JVM alive
        System.exit(0);
    }

    private static StructuredItem menuItem(final com.viaversion.viaversion.api.protocol.Protocol<?, ?, ?, ?> protocol) {
        final StructuredItem item = new StructuredItem(1, 1);
        item.dataContainer().setIdLookup(protocol, false);
        item.dataContainer().set(StructuredDataKey.CUSTOM_NAME, line("Server Selector", "green", true));
        final Tag[] lore = new Tag[6];
        for (int i = 0; i < lore.length; i++) {
            lore[i] = line("Click to join game mode " + i, i % 2 == 0 ? "gray" : "yellow", false);
        }
        item.dataContainer().set(StructuredDataKey.LORE, lore);
        final CompoundTag values = new CompoundTag();
        values.putString("arch:menu_id", "selector");
        values.putInt("arch:slot", 4);
        final CompoundTag customData = new CompoundTag();
        customData.put("PublicBukkitValues", values);
        item.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData);
        item.dataContainer().set(StructuredDataKey.ENCHANTMENT_GLINT_OVERRIDE, true);
        return item;
    }

    private static CompoundTag line(final String text, final String color, final boolean bold) {
        final CompoundTag part = new CompoundTag();
        part.putString("text", text);
        part.putString("color", color);
        part.put("italic", new ByteTag(false));
        if (bold) {
            part.put("bold", new ByteTag(true));
        }
        final ListTag<CompoundTag> extra = new ListTag<>(CompoundTag.class);
        extra.add(part);
        final CompoundTag root = new CompoundTag();
        root.putString("text", "");
        root.put("extra", extra);
        return root;
    }

    private static void measure(final String name, final Runnable action, final ThreadMXBean bean) {
        final long id = Thread.currentThread().getId();
        final long before = bean.getThreadAllocatedBytes(id);
        final long start = System.nanoTime();
        action.run();
        final long elapsed = System.nanoTime() - start;
        final long bytes = bean.getThreadAllocatedBytes(id) - before;
        System.out.printf("%s bytes/item=%.1f ns/item=%.1f%n", name, bytes / (double) ITEMS, elapsed / (double) ITEMS);
    }
}
