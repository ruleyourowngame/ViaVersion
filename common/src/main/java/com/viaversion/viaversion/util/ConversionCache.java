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
package com.viaversion.viaversion.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Bounded memo for a pure conversion. Two generations stand in for LRU without bookkeeping on
 * every hit: a key found only in the previous generation is copied forward, and the previous
 * generation is dropped once the current one fills. Callers must hand out copies of mutable values.
 */
final class ConversionCache<K, V> {

    /** {@code -Dviaversion.component-cache=false} turns every cache off, for comparing output. */
    static final boolean ENABLED = !"false".equals(System.getProperty("viaversion.component-cache"));

    private final int capacity;
    private volatile ConcurrentHashMap<K, V> current = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<K, V> previous = new ConcurrentHashMap<>();

    ConversionCache(final int capacity) {
        this.capacity = capacity;
    }

    /**
     * The cached value for {@code key}, or {@code compute}'s result, which is cached unless null.
     * Exceptions propagate and cache nothing.
     */
    @Nullable V get(final K key, final Function<K, @Nullable V> compute) {
        V value = current.get(key);
        if (value != null) {
            return value;
        }

        value = previous.get(key);
        if (value == null) {
            value = compute.apply(key);
            if (value == null) {
                return null;
            }
        }

        put(key, value);
        return value;
    }

    private void put(final K key, final V value) {
        final ConcurrentHashMap<K, V> map = current;
        map.put(key, value);
        if (map.size() > capacity) {
            rotate(map);
        }
    }

    private synchronized void rotate(final ConcurrentHashMap<K, V> full) {
        if (current == full) {
            previous = full;
            current = new ConcurrentHashMap<>();
        }
    }
}
