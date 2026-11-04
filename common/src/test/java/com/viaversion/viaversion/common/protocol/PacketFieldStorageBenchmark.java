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

import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
public class PacketFieldStorageBenchmark {
    static volatile Object sink;
    static int fields=12;
    static void run(int count) {
        for (int n = 0; n < count; n++) {
            PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null);
            for (int i = 0; i < fields; i++) packet.write(Types.INT, i);
            for (int stage = 0; stage < 8; stage++) {
                packet.resetReader();
                for (int i = 0; i < fields; i++) packet.passthrough(Types.INT);
            }
            sink = packet;
        }
    }
    public static void main(String[] args) {
        fields=Integer.parseInt(args[0]);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        run(100000);
        long id=Thread.currentThread().getId(), before=bean.getThreadAllocatedBytes(id), start=System.nanoTime();
        run(1000000);
        long elapsed=System.nanoTime()-start, bytes=bean.getThreadAllocatedBytes(id)-before;
        System.out.printf("%.1f bytes/packet, %.1f ns/packet%n",bytes/1000000.0,elapsed/1000000.0);
    }
}
