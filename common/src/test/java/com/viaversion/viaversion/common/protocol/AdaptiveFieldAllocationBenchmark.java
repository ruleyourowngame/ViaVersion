package com.viaversion.viaversion.common.protocol;

import com.sun.management.ThreadMXBean;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.lang.management.ManagementFactory;

/** Synthetic particle-shaped field replay; run both hints under the same JVM, never a live rate forecast. */
public final class AdaptiveFieldAllocationBenchmark {
    private static volatile Object sink;
    private static final int PACKETS = 500000;

    private static void fields(int count, int hint) {
        for (int n = 0; n < count; n++) {
            PacketWrapperImpl packet = new PacketWrapperImpl(-1, null, null, hint);
            for (int i = 0; i < 15; i++) packet.write(Types.INT, i);
            for (int stage = 0; stage < 8; stage++) {
                packet.resetReader();
                for (int i = 0; i < 15; i++) packet.passthrough(Types.INT);
            }
            sink = packet;
        }
    }

    private static void floats(ByteBuf buffer, int count, boolean cached) {
        for (int n = 0; n < count; n++) {
            buffer.readerIndex(0);
            for (int i = 0; i < 12; i++) sink = cached ? Types.FLOAT.read(buffer) : Float.valueOf(buffer.readFloat());
        }
    }

    private static void measure(String name, int packets, Runnable action, ThreadMXBean bean) {
        long id = Thread.currentThread().getId(), before = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
        action.run();
        long elapsed = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(id) - before;
        System.out.printf("%s bytes/packet=%.2f ns/packet=%.2f%n", name, bytes / (double) packets, elapsed / (double) packets);
    }

    public static void main(String[] args) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Thread allocation counters unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int i = 0; i < 12; i++) buffer.writeFloat(i % 3 == 0 ? 1F : (i % 3 == 1 ? -0F : 0F));
            for (int round = 0; round < 2; round++) {
                fields(100000, 4); fields(100000, 16);
                floats(buffer, 100000, false); floats(buffer, 100000, true);
            }
            for (int round = 0; round < 3; round++) {
                measure("field-default", PACKETS, () -> fields(PACKETS, 4), bean);
                measure("field-learned", PACKETS, () -> fields(PACKETS, 16), bean);
                measure("float-boxed", PACKETS, () -> floats(buffer, PACKETS, false), bean);
                measure("float-cached", PACKETS, () -> floats(buffer, PACKETS, true), bean);
            }
        } finally { buffer.release(); }
    }
}
