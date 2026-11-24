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

import com.viaversion.nbt.tag.ByteArrayTag;
import com.viaversion.nbt.tag.ByteTag;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.DoubleTag;
import com.viaversion.nbt.tag.FloatTag;
import com.viaversion.nbt.tag.IntArrayTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.LongArrayTag;
import com.viaversion.nbt.tag.LongTag;
import com.viaversion.nbt.tag.ShortTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.GlobalBlockPosition;
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.minecraft.HolderSet;
import com.viaversion.viaversion.api.minecraft.codec.CodecContext;
import com.viaversion.viaversion.api.minecraft.codec.Ops;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.HashedItem;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.Equippable;
import com.viaversion.viaversion.api.minecraft.item.data.LodestoneTracker;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.codec.CodecRegistryContext;
import com.viaversion.viaversion.common.PlatformTestBase;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.data.item.ItemHasherBase;
import com.viaversion.viaversion.protocols.v1_21_6to1_21_7.Protocol1_21_6To1_21_7;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hashes from reused {@link HashOps} must equal those of the previous implementation
 * ({@link ReferenceHashOps}, a fresh instance per value), whose output is pinned against vanilla in ItemHashTest.
 */
class HashOpsReuseTest extends PlatformTestBase {

    private static final int RANDOM_VALUES = 4000;
    private static CodecContext context;
    private static Protocol<?, ?, ?, ?> protocol;
    private static UserConnectionImpl connection;

    @BeforeAll
    static void loadContext() {
        connection = new UserConnectionImpl(null);
        protocol = Via.getManager().getProtocolManager().getProtocol(Protocol1_21_6To1_21_7.class);
        protocol.init(connection);
        context = new CodecRegistryContext(protocol, CodecContext.RegistryAccess.of(protocol, connection), false);
    }

    @Test
    void reusedHasherMatchesReferenceForRandomTags() {
        final Random random = new Random(0x5eed);
        final HashOps hasher = new HashOps(context, HashFunction.crc32c());
        for (int i = 0; i < RANDOM_VALUES; i++) {
            final Tag tag = i % 3 == 0 ? randomComponent(random, 0) : randomCompound(random, 0);
            assertSameHash(hasher, Types.TAG, tag);
            assertSameHash(hasher, Types.TEXT_COMPONENT_TAG, tag);
        }
    }

    @Test
    void reusedHasherMatchesReferenceAcrossCollectionSizes() {
        final HashOps hasher = new HashOps(context, HashFunction.crc32c());
        // Up to and past the retained-capacity limit, then back down, so entry arrays grow, are dropped and regrow
        final int[] sizes = {0, 1, 7, 8, 9, 64, 511, 512, 513, 2000, 3, 0, 1025, 2};
        for (final int size : sizes) {
            final CompoundTag compound = new CompoundTag();
            final ListTag<IntTag> list = new ListTag<>(IntTag.class);
            for (int i = 0; i < size; i++) {
                compound.putInt("key" + i, i * 31);
                list.add(new IntTag(-i));
            }
            compound.put("list", list);
            assertSameHash(hasher, Types.TAG, compound);
            assertSameHash(hasher, Types.TAG, list);
        }
    }

    @Test
    void reusedHasherMatchesReferenceForDeepNesting() {
        final HashOps hasher = new HashOps(context, HashFunction.crc32c());
        // Past the depth whose hashers are kept, alternating maps and lists
        for (final int depth : new int[]{15, 16, 17, 40, 3, 100}) {
            Tag tag = new StringTag("leaf");
            for (int i = 0; i < depth; i++) {
                if (i % 2 == 0) {
                    final CompoundTag compound = new CompoundTag();
                    compound.put("child", tag);
                    compound.putInt("depth", i);
                    tag = compound;
                } else {
                    final ListTag<CompoundTag> list = new ListTag<>(CompoundTag.class);
                    list.add((CompoundTag) tag);
                    list.add(new CompoundTag());
                    final CompoundTag holder = new CompoundTag();
                    holder.put("list", list);
                    tag = holder;
                }
            }
            assertSameHash(hasher, Types.TAG, tag);
            assertSameHash(hasher, Types.TEXT_COMPONENT_TAG, tag);
        }
    }

    @Test
    void mapEntriesAreOrderedByUnsignedKeyThenValue() {
        // Keys whose hashes straddle the sign bit and equal keys with differing values (only reachable through custom serializers)
        final Type<Integer> type = new HashOnlyType<>() {
            @Override
            public void write(final Ops ops, final Integer value) {
                ops.writeMap(map -> {
                    for (int i = 0; i < 40; i++) {
                        map.write(Types.INT, i % 5 == 0 ? Integer.MIN_VALUE + i : -i, Types.INT, value * i);
                        map.write(Types.INT, 7, Types.INT, i % 2 == 0 ? Integer.MAX_VALUE - i : i - 1);
                    }
                });
            }
        };
        final HashOps hasher = new HashOps(context, HashFunction.crc32c());
        for (int value = -3; value <= 3; value++) {
            assertSameHash(hasher, type, value);
        }
    }

    @Test
    void typedComponentsMatchReference() {
        final HashOps hasher = new HashOps(context, HashFunction.crc32c());
        for (int round = 0; round < 3; round++) {
            assertSameHash(hasher, LodestoneTracker.TYPE, new LodestoneTracker(new GlobalBlockPosition("wow", 5, 6, 7), true));
            assertSameHash(hasher, Equippable.TYPE1_21_6, new Equippable(0, Holder.of(0), null, null, HolderSet.of(new int[]{0}), true, true, true, false, false, Holder.of(0)));
            assertSameHash(hasher, VersionedTypes.V1_21_6.structuredDataKeys().container.type(), new Item[]{StructuredItem.empty(), new StructuredItem(1, 1)});
            final StructuredItem item = menuItem(new Random(round));
            assertSameHash(hasher, VersionedTypes.V1_21_6.item(), item);
        }
    }

    @Test
    void hasherRecoversAfterThrowingType() {
        final Type<Integer> throwing = new HashOnlyType<>() {
            @Override
            public void write(final Ops ops, final Integer value) {
                ops.writeMap(map -> map.writeList("list", list -> list.writeMap(inner -> {
                    inner.write("a", Types.INT, value);
                    throw new IllegalStateException("boom");
                })));
            }
        };
        final HashOps hasher = new HashOps(context, HashFunction.crc32c());
        final CompoundTag tag = randomCompound(new Random(42), 0);
        Assertions.assertThrows(IllegalStateException.class, () -> hasher.write(throwing, 1));
        assertSameHash(hasher, Types.TAG, tag);
        assertSameHash(hasher, Types.TEXT_COMPONENT_TAG, randomComponent(new Random(43), 0));
    }

    @Test
    void writingToOuterOpsDuringACollectionMatchesReference() {
        // Misuse that bypasses the serializer: the nested collection has to start from a fresh builder
        final Type<Integer> misuse = new HashOnlyType<>() {
            @Override
            public void write(final Ops ops, final Integer value) {
                ops.writeList(list -> {
                    list.write(Types.INT, value);
                    ops.writeMap(map -> {
                        map.write("x", Types.INT, value);
                        ops.writeMap(inner -> inner.write("z", Types.INT, -value));
                        map.write("w", Types.INT, value + 1);
                    });
                    ops.writeList(inner -> inner.write(Types.STRING, "y"));
                    list.write(Types.INT, -value);
                });
            }
        };
        final HashOps hasher = new HashOps(context, HashFunction.crc32c());
        for (int value = 0; value < 4; value++) {
            assertSameHash(hasher, misuse, value);
        }
    }

    @Test
    void reusableHashOpsHandsOutOneInstanceAtATime() {
        final ReusableHashOps reusable = new ReusableHashOps(context);
        final HashOps first = reusable.acquire();
        final HashOps second = reusable.acquire();
        Assertions.assertNotSame(first, second, "a hasher in use must not be handed out again");

        first.writeString("left over");
        reusable.release(first);
        final HashOps reused = reusable.acquire();
        Assertions.assertSame(first, reused);
        Types.STRING.write(reused, "value");
        Assertions.assertEquals(referenceHash(Types.STRING, "value"), reused.hash(), "released hasher must be reset");
    }

    @Test
    void reusedHasherSwitchesContextForNestedHashers() {
        final CodecContext otherContext = new CodecRegistryContext(protocol, CodecContext.RegistryAccess.of(protocol, connection), true);
        final List<CodecContext> seen = new ArrayList<>();
        final Type<Integer> recording = new HashOnlyType<>() {
            @Override
            public void write(final Ops ops, final Integer value) {
                ops.writeMap(map -> map.writeList("list", list -> list.write(new HashOnlyType<Integer>() {
                    @Override
                    public void write(final Ops nested, final Integer nestedValue) {
                        seen.add(nested.context());
                        nested.writeInt(nestedValue);
                    }
                }, value)));
            }
        };

        final ReusableHashOps first = new ReusableHashOps(context);
        final ReusableHashOps second = new ReusableHashOps(otherContext);
        final HashOps hasher = first.acquire();
        Types.STRING.write(hasher, "left over");
        hasher.reset();
        hasher.write(recording, 1);
        first.release(hasher);
        Assertions.assertNull(hasher.context(), "an idle hasher must not keep its context");

        final HashOps reused = second.acquire();
        Assertions.assertSame(hasher, reused, "the thread's hasher is shared between contexts");
        reused.write(recording, 1);
        second.release(reused);
        Assertions.assertEquals(List.of(context, otherContext), seen);
    }

    @Test
    void reusableHashOpsIsSafeAcrossThreads() throws Exception {
        final ReusableHashOps reusable = new ReusableHashOps(context);
        final List<CompoundTag> tags = new ArrayList<>();
        final List<Integer> expected = new ArrayList<>();
        final Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            final CompoundTag tag = randomCompound(random, 0);
            tags.add(tag);
            expected.add(referenceHash(Types.TAG, tag));
        }

        final ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            final List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < 4; thread++) {
                futures.add(executor.submit(() -> {
                    for (int round = 0; round < 25; round++) {
                        for (int i = 0; i < tags.size(); i++) {
                            final HashOps hasher = reusable.acquire();
                            Types.TAG.write(hasher, tags.get(i));
                            final int hash = hasher.hash();
                            reusable.release(hasher);
                            Assertions.assertEquals(expected.get(i), hash);
                        }
                    }
                }));
            }
            for (final Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void itemHasherMatchesReferenceAcrossCalls() {
        final ItemHasherBase itemHasher = new ItemHasherBase(protocol, connection);
        final CodecContext mappedContext = new CodecRegistryContext(protocol, CodecContext.RegistryAccess.of(protocol, connection), true);
        final Random random = new Random(11);
        for (int i = 0; i < 200; i++) {
            final StructuredItem item = menuItem(random);
            final boolean mapped = i % 2 == 0;
            final HashedItem hashed = itemHasher.toHashedItem(item, mapped);
            final HashedItem expected = ItemHasherBase.toHashedItem(new ReferenceHashOps(mapped ? mappedContext : context, HashFunction.crc32c()), item);
            Assertions.assertEquals(expected, hashed);
        }
    }

    private static StructuredItem menuItem(final Random random) {
        final StructuredItem item = new StructuredItem(1 + random.nextInt(50), 1 + random.nextInt(64));
        item.dataContainer().setIdLookup(protocol, false);
        item.dataContainer().set(StructuredDataKey.CUSTOM_NAME, randomComponent(random, 0));
        final Tag[] lore = new Tag[random.nextInt(8)];
        for (int i = 0; i < lore.length; i++) {
            lore[i] = randomComponent(random, 1);
        }
        item.dataContainer().set(StructuredDataKey.LORE, lore);
        item.dataContainer().set(StructuredDataKey.CUSTOM_DATA, randomCompound(random, 1));
        item.dataContainer().set(StructuredDataKey.ENCHANTMENT_GLINT_OVERRIDE, random.nextBoolean());
        return item;
    }

    private static <T> void assertSameHash(final HashOps hasher, final Type<T> type, final T value) {
        Assertions.assertEquals(referenceHash(type, value), hashWith(hasher, type, value), () -> "hash mismatch for " + value);
    }

    private static <T> int hashWith(final HashOps hasher, final Type<T> type, final T value) {
        hasher.reset();
        hasher.write(type, value);
        return hasher.hash();
    }

    private static <T> int referenceHash(final Type<T> type, final T value) {
        final ReferenceHashOps reference = new ReferenceHashOps(context, HashFunction.crc32c());
        reference.write(type, value);
        return reference.hash();
    }

    private static CompoundTag randomComponent(final Random random, final int depth) {
        final CompoundTag component = new CompoundTag();
        component.putString("text", randomString(random));
        if (random.nextBoolean()) {
            component.putString("color", random.nextBoolean() ? "gray" : "#" + Integer.toHexString(random.nextInt(0xFFFFFF)));
        }
        for (final String key : new String[]{"bold", "italic", "underlined", "strikethrough", "obfuscated"}) {
            if (random.nextInt(3) == 0) {
                component.put(key, new ByteTag(random.nextBoolean()));
            }
        }
        if (depth < 3 && random.nextInt(3) != 0) {
            final ListTag<CompoundTag> extra = new ListTag<>(CompoundTag.class);
            final int parts = random.nextInt(5);
            for (int i = 0; i < parts; i++) {
                extra.add(randomComponent(random, depth + 1));
            }
            component.put("extra", extra);
        }
        return component;
    }

    private static CompoundTag randomCompound(final Random random, final int depth) {
        final CompoundTag compound = new CompoundTag();
        final int entries = random.nextInt(depth == 0 ? 12 : 6);
        for (int i = 0; i < entries; i++) {
            compound.put(randomString(random), randomTag(random, depth + 1));
        }
        return compound;
    }

    private static Tag randomTag(final Random random, final int depth) {
        final int kind = random.nextInt(depth < 5 ? 13 : 11);
        return switch (kind) {
            case 0 -> new ByteTag((byte) random.nextInt());
            case 1 -> new ShortTag((short) random.nextInt());
            case 2 -> new IntTag(random.nextInt());
            case 3 -> new LongTag(random.nextLong());
            case 4 -> new FloatTag(random.nextFloat() * 1000 - 500);
            case 5 -> new DoubleTag(random.nextGaussian());
            case 6, 7 -> new StringTag(randomString(random));
            case 8 -> new ByteArrayTag(randomBytes(random));
            case 9 -> new IntArrayTag(random.ints(random.nextInt(6)).toArray());
            case 10 -> new LongArrayTag(random.longs(random.nextInt(6)).toArray());
            case 11 -> randomCompound(random, depth);
            default -> randomList(random, depth);
        };
    }

    private static ListTag<?> randomList(final Random random, final int depth) {
        final int size = random.nextInt(6);
        if (random.nextBoolean()) {
            final ListTag<CompoundTag> list = new ListTag<>(CompoundTag.class);
            for (int i = 0; i < size; i++) {
                list.add(randomCompound(random, depth));
            }
            return list;
        }
        final ListTag<StringTag> list = new ListTag<>(StringTag.class);
        for (int i = 0; i < size; i++) {
            list.add(new StringTag(randomString(random)));
        }
        return list;
    }

    private static String randomString(final Random random) {
        final int length = random.nextInt(24);
        final StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(8) == 0 ? (char) (0x80 + random.nextInt(0x2000)) : (char) ('a' + random.nextInt(26)));
        }
        return builder.toString();
    }

    private static byte[] randomBytes(final Random random) {
        final byte[] bytes = new byte[random.nextInt(8)];
        random.nextBytes(bytes);
        return bytes;
    }

    private abstract static class HashOnlyType<T> extends Type<T> {

        HashOnlyType() {
            super(Object.class);
        }

        @Override
        public T read(final ByteBuf buffer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(final ByteBuf buffer, final T value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public abstract void write(Ops ops, T value);
    }
}
