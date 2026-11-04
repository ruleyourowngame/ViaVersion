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
package com.viaversion.viaversion.bukkit.providers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BukkitAckSequenceBatchTest {
    @Test
    void burstUsesOneHopAndOneFrozenOrderedSend() {
        final var batch = new BukkitAckSequenceProvider.AckedSequence();
        assertTrue(batch.offer(1));
        for (int sequence = 2; sequence <= 100000; sequence++) assertFalse(batch.offer(sequence));
        assertEquals(100000, batch.takeBatch());
        assertTrue(batch.raiseTo(100000));
        assertFalse(batch.finishBatch());
        assertFalse(batch.offer(100000));
        assertFalse(batch.offer(1));
        assertTrue(batch.offer(100001));
    }

    @Test
    void arrivalsAfterTheHopWaitForANewOrderingDelay() {
        final var batch = new BukkitAckSequenceProvider.AckedSequence();
        assertTrue(batch.offer(10));
        final int first = batch.takeBatch();
        assertEquals(10, first);
        assertFalse(batch.offer(20));
        assertFalse(batch.offer(30));
        assertTrue(batch.raiseTo(first)); // The first callback cannot acknowledge sequence 30 early.
        assertTrue(batch.finishBatch());
        assertFalse(batch.offer(40)); // The next hop already has an owner.
        assertEquals(40, batch.takeBatch());
        assertTrue(batch.raiseTo(40));
        assertFalse(batch.finishBatch());
        assertFalse(batch.raiseTo(10));
    }

    @Test
    void disconnectInvalidatesBothQueuedAndInFlightBatches() {
        for (boolean sealed : new boolean[]{false, true}) {
            final var batch = new BukkitAckSequenceProvider.AckedSequence();
            assertTrue(batch.offer(10));
            if (sealed) assertEquals(10, batch.takeBatch());
            batch.onRemove();
            assertEquals(-1, batch.takeBatch());
            assertFalse(batch.raiseTo(10));
            assertFalse(batch.finishBatch());
            assertFalse(batch.offer(20));
        }
    }
}
