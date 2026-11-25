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
package com.viaversion.viaversion.protocols.v1_21_4to1_21_5.storage;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.data.item.ItemHasher;
import com.viaversion.viaversion.api.minecraft.codec.CodecContext;
import com.viaversion.viaversion.api.minecraft.codec.CodecContext.RegistryAccess;
import com.viaversion.viaversion.api.minecraft.codec.hash.Hasher;
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.codec.CodecRegistryContext;
import com.viaversion.viaversion.codec.hash.HashOps;
import com.viaversion.viaversion.codec.hash.ReusableHashOps;
import com.viaversion.viaversion.data.item.ItemHasherBase;
import com.viaversion.viaversion.protocols.v1_21_4to1_21_5.Protocol1_21_4To1_21_5;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import org.checkerframework.checker.nullness.qual.Nullable;

public class ItemHashStorage1_21_5 implements ItemHasher {

    // Estimated sizes from hashed data + overhead
    private static final int HASH_CALL_SIZE = 32;
    private static final int MAX_CACHE_SIZE = 8 * 1024 * 1024;
    private static final int MIN_ENTRY_SIZE = MAX_CACHE_SIZE / 512;
    private static final int MAX_ENTRY_SIZE = 1024 * 1024;
    private static final int MAX_RESTORED_SIZE = 8 * 1024 * 1024;
    private final Cache<Long, CachedData> hashToStructuredData = CacheBuilder.newBuilder()
        .concurrencyLevel(1)
        .maximumWeight(MAX_CACHE_SIZE)
        .<Long, CachedData>weigher((key, data) -> Math.max(data.size(), MIN_ENTRY_SIZE))
        .build();
    private final long keyMultiplier = ThreadLocalRandom.current().nextLong() | 1; // Odd to keep keys unique, random to not have predictable buckets
    private boolean processingClientboundInventoryPacket;
    private final TrackingCodecContext context;
    private final ReusableHashOps hasher;
    private int restoredSize;

    public ItemHashStorage1_21_5(final Protocol1_21_4To1_21_5 protocol, final UserConnection connection) {
        final RegistryAccess registryAccess = RegistryAccess.of(protocol, connection);
        this.context = new TrackingCodecContext(new CodecRegistryContext(protocol, registryAccess, true)); // always using 1.21.5 items as input
        this.hasher = new ReusableHashOps(this.context);
    }

    public void trackStructuredData(final StructuredData<?> structuredData) {
        if (structuredData.isEmpty() || !context.isSupported(structuredData.key())) {
            return;
        }

        final HashOps hasher = this.hasher.acquire();
        context.skippedData = false;
        final int hash = hash(hasher, structuredData);
        final long size = ReusableHashOps.hashedSize(hasher, HASH_CALL_SIZE);
        this.hasher.release(hasher);
        if (hash == ItemHasherBase.UNKNOWN_HASH || context.skippedData || size > MAX_ENTRY_SIZE) {
            return;
        }

        final long key = key(structuredData.id(), hash);
        try {
            this.hashToStructuredData.get(key, () -> new CachedData(structuredData.copy(), (int) size));
        } catch (final ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    public @Nullable StructuredData<?> dataFromHash(final int dataComponentId, final int hash) {
        final long key = key(dataComponentId, hash);
        final CachedData data = this.hashToStructuredData.getIfPresent(key);
        if (data == null || this.restoredSize + data.size() > MAX_RESTORED_SIZE) {
            return null;
        }

        this.restoredSize += data.size();
        return data.data().copy();
    }

    /**
     * Resets the size of data restored from hashes, to be called before handling a new packet.
     */
    public void resetRestoredSize() {
        this.restoredSize = 0;
    }

    private long key(final int dataComponentId, final int hash) {
        // Add a key multiplier to avoid collisions from Guava's cache unseeded hashing
        return ((long) dataComponentId << 32 | (hash & 0xFFFFFFFFL)) * this.keyMultiplier;
    }

    private static <T> int hash(final Hasher hasher, final StructuredData<T> data) {
        hasher.write(data.key().type(), data.value());
        return hasher.hash();
    }

    @Override
    public boolean isProcessingClientboundInventoryPacket() {
        return this.processingClientboundInventoryPacket;
    }

    @Override
    public void setProcessingClientboundInventoryPacket(final boolean processingClientboundInventoryPacket) {
        this.processingClientboundInventoryPacket = processingClientboundInventoryPacket;
    }

    private record CachedData(StructuredData<?> data, int size) {
    }

    private static final class TrackingCodecContext implements CodecContext {
        private final CodecContext context;
        private boolean skippedData;

        private TrackingCodecContext(final CodecContext context) {
            this.context = context;
        }

        @Override
        public boolean isSupported(final StructuredDataKey<?> key) {
            final boolean supported = this.context.isSupported(key);
            if (!supported) {
                this.skippedData = true;
            }
            return supported;
        }

        @Override
        public RegistryAccess registryAccess() {
            return this.context.registryAccess();
        }

        @Override
        public boolean mapped() {
            return this.context.mapped();
        }

        @Override
        public UserConnection connection() {
            return this.context.connection();
        }

        @Override
        public Protocol<?, ?, ?, ?> protocol() {
            return this.context.protocol();
        }
    }
}
