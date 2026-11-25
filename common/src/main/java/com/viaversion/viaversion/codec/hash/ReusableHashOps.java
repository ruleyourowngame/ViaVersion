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

/**
 * Hands out a reset {@link HashOps} for one context. The hasher, with the buffers of its nested
 * maps and lists, is kept per thread and shared by every context used on that thread, so only one
 * set of buffers exists per thread instead of one per connection and protocol. A caller that finds
 * the thread's hasher taken, by a nested call, gets a fresh instance instead.
 */
public final class ReusableHashOps {

    private static final ThreadLocal<HashOps> IDLE = new ThreadLocal<>();
    private final CodecContext context;

    public ReusableHashOps(final CodecContext context) {
        this.context = context;
    }

    /**
     * Returns a reset hasher that only the caller uses until it is given back with {@link #release(HashOps)}.
     * A hasher that is not given back, for example because hashing threw, is simply dropped.
     *
     * @return a reset hasher using this context
     */
    public HashOps acquire() {
        HashOps hasher = IDLE.get();
        if (hasher != null) {
            IDLE.set(null);
        } else {
            hasher = new HashOps(this.context, new CountingHashFunction(HashFunction.crc32c()));
        }
        hasher.setContext(this.context);
        ((CountingHashFunction) hasher.hashFunction()).reset();
        return hasher;
    }

    /**
     * Returns an estimate of how much data a hasher from {@link #acquire()} has hashed since it was acquired,
     * counting its nested hashers: the hashed bytes plus {@code overheadPerCall} for every hash computed.
     *
     * @param hasher          a hasher from {@link #acquire()} that has not been released yet
     * @param overheadPerCall estimated size added for each hash computed
     * @return the estimated size of the hashed data
     */
    public static long hashedSize(final HashOps hasher, final int overheadPerCall) {
        if (!(hasher.hashFunction() instanceof final CountingHashFunction function)) {
            throw new IllegalArgumentException("Hasher was not acquired from ReusableHashOps");
        }
        return function.bytes + function.calls * overheadPerCall;
    }

    /**
     * Gives back a hasher from {@link #acquire()} for the current thread to reuse.
     *
     * @param hasher the hasher, which must not be used afterwards
     */
    public void release(final HashOps hasher) {
        if (!(hasher.hashFunction() instanceof CountingHashFunction)) {
            // Not from acquire(), so it can't report its hashed size; leave it to the caller
            return;
        }
        hasher.reset();
        // Don't keep the connection reachable from an idle hasher
        hasher.setContext(null);
        IDLE.set(hasher);
    }

    // Shared by a reusable hasher and its nested hashers, so it sees every hash computed for one value
    private static final class CountingHashFunction implements HashFunction {
        private final HashFunction function;
        private long bytes;
        private long calls;

        private CountingHashFunction(final HashFunction function) {
            this.function = function;
        }

        @Override
        public int hashBytes(final byte[] data, final int length) {
            this.bytes += length;
            this.calls++;
            return this.function.hashBytes(data, length);
        }

        private void reset() {
            this.bytes = 0;
            this.calls = 0;
        }
    }
}
