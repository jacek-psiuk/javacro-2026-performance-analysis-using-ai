package hr.javacro.section1;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/// Fixed version of [Ex5]: pending messages expire, buffers are reused, in-flight messages are capped.
///
/// 1. Every pending message remembers when it was sent. After `ACK_TIMEOUT_MS` without an ack
///    it is retransmitted, at most `MAX_RETRANSMITS` times, and then given up. Either way it leaves
///    `PENDING_ACKS` - a lost ack no longer means a buffer kept forever. `PENDING_ACKS` is a
///    `LinkedHashMap` in insertion (= send time) order, so expiry only looks at the oldest entries.
/// 2. Direct buffers come from a small pool and go back to it on ack or give-up. Native memory is
///    reused instead of waiting for a GC to find the dropped `DirectByteBuffer` and the `Cleaner`
///    to free it - so it does not swing up and down with GC either. Netty does the same with
///    pooled `ByteBuf`s and `release()`; with FFM you would allocate from an `Arena` and close it.
/// 3. At most `MAX_IN_FLIGHT` messages wait for an ack. When the peer stops acking, the sender
///    waits (backpressure) instead of allocating more. Native memory is bounded by
///    `(MAX_IN_FLIGHT + POOL_MAX) x 64 KB` whatever the peer does.
///
/// In production also set `-XX:MaxDirectMemorySize` explicitly and monitor the "direct"
/// `BufferPoolMXBean` (printed below). The demo is single-threaded; a real sender would run
/// the expiry on a scheduler and needs a thread-safe map and pool.
///
/// How to see it with Native Memory Tracking:
/// run with `-XX:NativeMemoryTracking=summary`, then
/// `jcmd <pid> VM.native_memory baseline`, wait, `jcmd <pid> VM.native_memory summary.diff`.
/// The `Other` category stays flat (a few MB, no `+NNNKB` trend), and the direct buffer
/// count printed by the program stays constant.
public class Ex5Fixed {

    static final int MESSAGE_SIZE = 64 * 1024;
    static final long ACK_TIMEOUT_MS = 500;
    static final int MAX_RETRANSMITS = 3;
    static final int MAX_IN_FLIGHT = 1_000;
    static final int POOL_MAX = 256;

    record Pending(ByteBuffer buffer, long sentAt, int retransmits) {}

    // Sent messages waiting for an ack, oldest first - removed on ack, retransmit limit or give-up
    static final Map<Long, Pending> PENDING_ACKS = new LinkedHashMap<>();
    static final ArrayDeque<ByteBuffer> POOL = new ArrayDeque<>();

    static long retransmitted;
    static long givenUp;

    static void send(long messageId, byte[] payload) {
        ByteBuffer buffer = acquire();
        buffer.put(payload).flip();
        PENDING_ACKS.put(messageId, new Pending(buffer, System.currentTimeMillis(), 0));
    }

    static void onAck(long messageId) {
        Pending pending = PENDING_ACKS.remove(messageId);
        if (pending != null) {
            release(pending.buffer());
        }
    }

    /// Retransmits or gives up messages whose ack did not arrive in time.
    static void expirePending() {
        long now = System.currentTimeMillis();
        Map<Long, Pending> resent = new LinkedHashMap<>();
        Iterator<Map.Entry<Long, Pending>> it = PENDING_ACKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Pending> entry = it.next();
            Pending pending = entry.getValue();
            if (now - pending.sentAt() < ACK_TIMEOUT_MS) {
                break; // the rest is younger
            }
            it.remove();
            if (pending.retransmits() < MAX_RETRANSMITS) {
                pending.buffer().rewind(); // send the same bytes again
                retransmitted++;
                resent.put(entry.getKey(), new Pending(pending.buffer(), now, pending.retransmits() + 1));
            } else {
                givenUp++;
                release(pending.buffer());
            }
        }
        // Re-insert at the end, so the map stays ordered by send time
        PENDING_ACKS.putAll(resent);
        // A retransmitted message is acknowledged like any other: 95% of the time
        for (Long messageId : resent.keySet()) {
            if (ThreadLocalRandom.current().nextInt(100) < 95) {
                onAck(messageId);
            }
        }
    }

    static ByteBuffer acquire() {
        ByteBuffer buffer = POOL.poll();
        return buffer != null ? buffer : ByteBuffer.allocateDirect(MESSAGE_SIZE);
    }

    static void release(ByteBuffer buffer) {
        buffer.clear();
        if (POOL.size() < POOL_MAX) {
            POOL.push(buffer);
        }
    }

    static void main() {
        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Sending messages. Native memory stays flat - compare with Ex5 using:");
        IO.println("  jcmd " + ProcessHandle.current().pid() + " VM.native_memory baseline");
        IO.println("  jcmd " + ProcessHandle.current().pid() + " VM.native_memory summary.diff");

        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals("direct"))
                .findFirst().orElseThrow();

        long messageId = 0;
        long started = System.currentTimeMillis();
        while (true) {
            expirePending();
            // Backpressure: do not send more while too many messages wait for an ack
            while (PENDING_ACKS.size() >= MAX_IN_FLIGHT) {
                sleep(10);
                expirePending();
            }

            // Payload is built on the heap, then copied into the direct buffer for sending
            byte[] payload = new byte[MESSAGE_SIZE];
            ThreadLocalRandom.current().nextBytes(payload);
            send(++messageId, payload);

            // The peer acknowledges 95% of messages, the rest of the acks are lost
            if (ThreadLocalRandom.current().nextInt(100) < 95) {
                onAck(messageId);
            }

            if (messageId % 100 == 0) {
                sleep(100);
            }
            if (messageId % 2_000 == 0) {
                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                IO.println(
                        ("%3ds: messages sent=%d, pending=%d, retransmitted=%d, given up=%d, heap used=%d MB, direct " +
                                "buffers=%d (%d MB)")
                                .formatted((System.currentTimeMillis() - started) / 1000, messageId,
                                        PENDING_ACKS.size(),
                                        retransmitted, givenUp, usedMb, direct.getCount(),
                                        direct.getMemoryUsed() / (1024 * 1024)));
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
