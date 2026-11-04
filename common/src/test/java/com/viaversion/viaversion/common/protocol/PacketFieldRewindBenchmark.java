package com.viaversion.viaversion.common.protocol;

import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/** Standalone command-like small-rewind benchmark; no timing assertions in the unit suite. */
public final class PacketFieldRewindBenchmark {
    private static volatile Object sink;

    private static void run(int packets, int nodes) {
        for (int packet = 0; packet < packets; packet++) {
            PacketWrapperImpl wrapper = new PacketWrapperImpl(-1, null, null);
            for (int node = 0; node < nodes; node++) {
                wrapper.write(Types.VAR_INT, node);
                wrapper.rewindReader(1);
                int value = wrapper.read(Types.VAR_INT);
                wrapper.write(Types.VAR_INT, value);
            }
            sink = wrapper;
        }
    }

    public static void main(String[] args) {
        int nodes = Integer.parseInt(args[0]);
        int packets = Math.max(10, 64000 / nodes);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        run(Math.max(10, 10000 / nodes), nodes);
        long id = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
        run(packets, nodes);
        long elapsed = System.nanoTime() - start, bytes = bean.getThreadAllocatedBytes(id) - before;
        System.out.printf("nodes=%d ns/node=%.2f bytes/node=%.2f%n", nodes,
            elapsed / (double) packets / nodes, bytes / (double) packets / nodes);
    }
}
