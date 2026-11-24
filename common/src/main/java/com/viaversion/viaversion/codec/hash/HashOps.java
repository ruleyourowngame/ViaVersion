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
package com.viaversion.viaversion.codec.hash;

import com.viaversion.viaversion.api.minecraft.codec.CodecContext;
import com.viaversion.viaversion.api.minecraft.codec.ThrowingOps;
import com.viaversion.viaversion.api.minecraft.codec.hash.Hasher;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Re-usable (but not thread-safe) ops for writing hashes. Reusing one instance also reuses
 * the buffers of nested maps and lists.
 */
public final class HashOps implements Hasher {

    private static final byte TAG_MAP_START = 2;
    private static final byte TAG_MAP_END = 3;
    private static final byte TAG_LIST_START = 4;
    private static final byte TAG_LIST_END = 5;
    private static final byte TAG_BYTE = 6;
    private static final byte TAG_SHORT = 7;
    private static final byte TAG_INT = 8;
    private static final byte TAG_LONG = 9;
    private static final byte TAG_FLOAT = 10;
    private static final byte TAG_DOUBLE = 11;
    private static final byte TAG_STRING = 12;
    private static final byte TAG_BOOLEAN = 13;
    private static final byte TAG_BYTE_ARRAY_START = 14;
    private static final byte TAG_BYTE_ARRAY_END = 15;
    private static final byte TAG_INT_ARRAY_START = 16;
    private static final byte TAG_INT_ARRAY_END = 17;
    private static final byte TAG_LONG_ARRAY_START = 18;
    private static final byte TAG_LONG_ARRAY_END = 19;
    static final byte[] FALSE = {TAG_BOOLEAN, 0};
    static final byte[] TRUE = {TAG_BOOLEAN, 1};
    static final byte[] EMPTY_LIST = {TAG_LIST_START, TAG_LIST_END};
    static final byte[] EMPTY_MAP = {TAG_MAP_START, TAG_MAP_END};
    private static final long[] EMPTY_ENTRIES = new long[0];
    private static final int[] EMPTY_LIST_ENTRIES = new int[0];
    private static final int INITIAL_ENTRIES = 8;
    // Deeper nested hashers are created per element again, so an unusually deep item can't pin a hasher per level
    private static final int MAX_RETAINED_DEPTH = 16;
    private final ContextHolder contextHolder;
    private final HashBuilder hashBuilder;
    private final int depth;
    private MapHashBuilder mapBuilder;
    private ListHashBuilder listBuilder;

    public HashOps(final CodecContext context, final HashFunction hashFunction) {
        this(new ContextHolder(context), hashFunction, 0);
    }

    private HashOps(final ContextHolder contextHolder, final HashFunction hashFunction, final int depth) {
        this.contextHolder = contextHolder;
        this.hashBuilder = new HashBuilder(hashFunction);
        this.depth = depth;
    }

    @Override
    public CodecContext context() {
        return this.contextHolder.context;
    }

    /**
     * Sets the context of these ops and of every nested hasher created for them.
     */
    void setContext(final CodecContext context) {
        this.contextHolder.context = context;
    }

    @Override
    public void writeByte(final byte b) {
        hashBuilder.preSize(Byte.BYTES + Byte.BYTES)
            .writeByte(TAG_BYTE)
            .writeByte(b);
    }

    @Override
    public void writeBytes(final byte[] array) {
        hashBuilder.preSize(array.length + 2 * Byte.BYTES)
            .writeByte(TAG_BYTE_ARRAY_START)
            .writeBytes(array)
            .writeByte(TAG_BYTE_ARRAY_END);
    }

    @Override
    public void writeBoolean(final boolean b) {
        hashBuilder.writeBytesDirect(b ? TRUE : FALSE);
    }

    @Override
    public void writeShort(final short s) {
        hashBuilder.preSize(Byte.BYTES + Short.BYTES)
            .writeByte(TAG_SHORT)
            .writeShort(s);
    }

    @Override
    public void writeString(final CharSequence sequence) {
        hashBuilder.preSize((sequence.length() * Character.BYTES) + Byte.BYTES + Integer.BYTES)
            .writeByte(TAG_STRING)
            .writeInt(sequence.length())
            .writeString(sequence);
    }

    @Override
    public void writeInt(final int i) {
        hashBuilder.preSize(Byte.BYTES + Integer.BYTES)
            .writeByte(TAG_INT)
            .writeInt(i);
    }

    @Override
    public void writeLong(final long l) {
        hashBuilder.preSize(Byte.BYTES + Long.BYTES)
            .writeByte(TAG_LONG)
            .writeLong(l);
    }

    @Override
    public void writeFloat(final float f) {
        hashBuilder.preSize(Byte.BYTES + Float.BYTES)
            .writeByte(TAG_FLOAT)
            .writeFloat(f);
    }

    @Override
    public void writeDouble(final double d) {
        hashBuilder.preSize(Byte.BYTES + Double.BYTES)
            .writeByte(TAG_DOUBLE)
            .writeDouble(d);
    }

    @Override
    public void writeInts(final int[] array) {
        hashBuilder.preSize(array.length * Integer.BYTES + 2 * Byte.BYTES);
        hashBuilder.writeByte(TAG_INT_ARRAY_START);
        for (final int i : array) {
            hashBuilder.writeInt(i);
        }
        hashBuilder.writeByte(TAG_INT_ARRAY_END);
    }

    @Override
    public void writeLongs(final long[] array) {
        hashBuilder.preSize(array.length * Long.BYTES + 2 * Byte.BYTES);
        hashBuilder.writeByte(TAG_LONG_ARRAY_START);
        for (final long l : array) {
            hashBuilder.writeLong(l);
        }
        hashBuilder.writeByte(TAG_LONG_ARRAY_END);
    }

    @Override
    public void writeList(final Consumer<ListSerializer> consumer) {
        ListHashBuilder builder = this.listBuilder;
        if (builder == null) {
            builder = this.listBuilder = new ListHashBuilder();
        } else if (builder.active) {
            // Only reachable when a consumer writes to these ops instead of its serializer
            builder = new ListHashBuilder();
        }

        builder.begin();
        try {
            consumer.accept(builder);
            builder.applyHashToParent();
        } finally {
            builder.end();
        }
    }

    @Override
    public void writeMap(final Consumer<MapSerializer> consumer) {
        MapHashBuilder builder = this.mapBuilder;
        if (builder == null) {
            builder = this.mapBuilder = new MapHashBuilder();
        } else if (builder.active) {
            builder = new MapHashBuilder();
        }

        builder.begin();
        try {
            consumer.accept(builder);
            builder.applyHashToParent();
        } finally {
            builder.end();
        }
    }

    @Override
    public int hash() {
        return this.hashBuilder.hash();
    }

    @Override
    public void reset() {
        this.hashBuilder.reset();
    }

    /**
     * Returns the bytes of buffers and entry arrays this hasher and its nested hashers keep for reuse.
     */
    int retainedBytes() {
        int bytes = this.hashBuilder.capacity();
        if (this.mapBuilder != null) {
            bytes += this.mapBuilder.retainedBytes();
        }
        if (this.listBuilder != null) {
            bytes += this.listBuilder.retainedBytes();
        }
        return bytes;
    }

    // Shared by a hasher and all of its nested hashers, so a reused tree can switch contexts at once
    private static final class ContextHolder {

        private CodecContext context;

        private ContextHolder(final CodecContext context) {
            this.context = context;
        }
    }

    // Builders and their element hashers are kept and reused for every collection at the same depth,
    // so hashing a nested component allocates nothing once each depth has been seen.
    private abstract class CollectionHashBuilder {

        private HashOps elementHasher;
        boolean active;

        void begin() {
            this.active = true;
        }

        void end() {
            this.active = false;
            if (this.elementHasher != null) {
                // Drops an oversized buffer now rather than on the element hasher's next use, which may never come
                this.elementHasher.reset();
            }
        }

        int retainedBytes() {
            return this.elementHasher != null ? this.elementHasher.retainedBytes() : 0;
        }

        private HashOps elementHasher() {
            HashOps hasher = this.elementHasher;
            if (hasher == null) {
                hasher = new HashOps(contextHolder, hashBuilder.function(), depth + 1);
                if (depth < MAX_RETAINED_DEPTH) {
                    this.elementHasher = hasher;
                }
            }
            hasher.reset();
            return hasher;
        }

        protected int listHash(final Consumer<ListSerializer> consumer) {
            final HashOps hasher = elementHasher();
            hasher.writeList(consumer);
            return hasher.hash();
        }

        protected int mapHash(final Consumer<MapSerializer> consumer) {
            final HashOps hasher = elementHasher();
            hasher.writeMap(consumer);
            return hasher.hash();
        }

        protected <V> int hash(final Type<V> type, final V value) {
            final HashOps hasher = elementHasher();
            type.write(hasher, value);
            return hasher.hash();
        }
    }

    private final class MapHashBuilder extends CollectionHashBuilder implements MapSerializer {

        // Each entry is (key << 32 | value) with the sign bit flipped, so a signed sort orders entries
        // by unsigned key, then unsigned value, as vanilla does
        private long[] entries = EMPTY_ENTRIES;
        private int size;

        @Override
        int retainedBytes() {
            return super.retainedBytes() + this.entries.length * Long.BYTES;
        }

        @Override
        void end() {
            super.end();
            this.size = 0;
            if (this.entries.length * Long.BYTES > HashBuilder.MAX_RETAINED_CAPACITY) {
                this.entries = EMPTY_ENTRIES;
            }
        }

        private void add(final int key, final int value) {
            if (this.size == this.entries.length) {
                this.entries = Arrays.copyOf(this.entries, Math.max(INITIAL_ENTRIES, this.size * 2));
            }
            this.entries[this.size++] = (((long) key << 32) | (value & 0xFFFFFFFFL)) ^ Long.MIN_VALUE;
        }

        @Override
        public <K, T> MapSerializer write(final Type<K> keyType, final K key, final Type<T> valueType, final T value) {
            final int keyHash = hash(keyType, key);
            add(keyHash, hash(valueType, value));
            return this;
        }

        @Override
        public MapSerializer writeList(final String key, final Consumer<ListSerializer> consumer) {
            final int keyHash = hash(Types.STRING, key);
            add(keyHash, listHash(consumer));
            return this;
        }

        @Override
        public MapSerializer writeMap(final String key, final Consumer<MapSerializer> consumer) {
            final int keyHash = hash(Types.STRING, key);
            add(keyHash, mapHash(consumer));
            return this;
        }

        @Override
        public <T> MapSerializer writeInlinedMap(final Type<T> valueType, final T value) {
            // Only allow writeMap calls here
            valueType.write(new ThrowingOps() {
                @Override
                public void writeMap(final Consumer<MapSerializer> consumer) {
                    consumer.accept(MapHashBuilder.this);
                }
            }, value);
            return this;
        }

        public void applyHashToParent() {
            final int size = this.size;
            if (size == 0) {
                hashBuilder.writeBytesDirect(EMPTY_MAP);
                return;
            }

            hashBuilder.preSize((size * Integer.BYTES * 2) + (2 * Byte.BYTES));
            hashBuilder.writeByte(TAG_MAP_START);
            Arrays.sort(this.entries, 0, size);
            for (int i = 0; i < size; i++) {
                final long entry = this.entries[i] ^ Long.MIN_VALUE;
                hashBuilder.writeInt((int) (entry >>> 32));
                hashBuilder.writeInt((int) entry);
            }
            hashBuilder.writeByte(TAG_MAP_END);
        }
    }

    private final class ListHashBuilder extends CollectionHashBuilder implements ListSerializer {

        private int[] entries = EMPTY_LIST_ENTRIES;
        private int size;

        @Override
        int retainedBytes() {
            return super.retainedBytes() + this.entries.length * Integer.BYTES;
        }

        @Override
        void end() {
            super.end();
            this.size = 0;
            if (this.entries.length * Integer.BYTES > HashBuilder.MAX_RETAINED_CAPACITY) {
                this.entries = EMPTY_LIST_ENTRIES;
            }
        }

        private void add(final int hash) {
            if (this.size == this.entries.length) {
                this.entries = Arrays.copyOf(this.entries, Math.max(INITIAL_ENTRIES, this.size * 2));
            }
            this.entries[this.size++] = hash;
        }

        @Override
        public <T> ListSerializer write(final Type<T> valueType, final T value) {
            add(hash(valueType, value));
            return this;
        }

        @Override
        public ListSerializer writeList(final Consumer<ListSerializer> consumer) {
            add(listHash(consumer));
            return this;
        }

        @Override
        public ListSerializer writeMap(final Consumer<MapSerializer> consumer) {
            add(mapHash(consumer));
            return this;
        }

        public void applyHashToParent() {
            final int size = this.size;
            if (size == 0) {
                hashBuilder.writeBytesDirect(EMPTY_LIST);
                return;
            }

            hashBuilder.preSize(size * Integer.BYTES + 2 * Byte.BYTES);
            hashBuilder.writeByte(TAG_LIST_START);
            for (int i = 0; i < size; i++) {
                hashBuilder.writeInt(this.entries[i]);
            }
            hashBuilder.writeByte(TAG_LIST_END);
        }
    }
}
