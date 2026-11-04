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
package com.viaversion.viaversion.common.protocol;

import com.sun.management.ThreadMXBean;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.entitydata.types.EntityDataTypes1_9;
import java.lang.management.ManagementFactory;
import java.util.List;

/** Standalone allocation fixture. No timing or allocation thresholds are imposed by tests. */
public final class EntityDataDirectBenchmark {
    private static volatile Object sink;

    private static void run(EntityDataDirectFixture rewriter, List<EntityData> entries, int count) {
        for (int i = 0; i < count; i++) rewriter.handleEntityData(1, entries, null);
    }

    public static void main(String[] args) {
        final ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        for (boolean legacy : new boolean[]{true, false}) {
            final EntityDataDirectFixture r = new EntityDataDirectFixture(legacy);
            r.filter().mapDataType(id -> EntityDataTypes1_9.VAR_INT);
            r.filter().addIndex(4);
            r.filter().removeIndex(10000000);
            r.filter().index(0).handler((event, data) -> sink = event);
            final List<EntityData> entries = EntityDataDirectFixture.entries(12);
            run(r, entries, 100000);
            final long thread = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(thread);
            final long start = System.nanoTime();
            run(r, entries, 1000000);
            final long elapsed = System.nanoTime() - start;
            final long allocated = bean.getThreadAllocatedBytes(thread) - before;
            System.out.printf("legacy=%s bytes/12-field-list=%.2f ns/list=%.2f%n", legacy,
                allocated / 1000000., elapsed / 1000000.);
        }
    }
}
