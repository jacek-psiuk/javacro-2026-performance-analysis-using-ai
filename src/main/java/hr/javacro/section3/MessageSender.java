package hr.javacro.section3;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/// Section 1, example 5 ([hr.javacro.section1.Ex5]) - native memory leak of direct `ByteBuffer`s.
///
/// Every message is copied into a 64 KB direct buffer kept until the peer acks it. 5% of acks are
/// lost and there is no timeout, so those buffers stay in `PENDING_ACKS` forever (~0.3 MB/s).
/// The memory lives outside the Java heap.
///
/// Native Memory Tracking (needs `-XX:NativeMemoryTracking=detail` at startup):
/// `jcmd <pid> VM.native_memory baseline`, wait, `jcmd <pid> VM.native_memory summary.diff` -
/// the `Other` category grows. A heap dump then shows *who* holds it: `DirectByteBuffer` objects
/// (capacity 65536) reachable from the static `MessageSender.PENDING_ACKS`.
final class MessageSender implements Scenario {

    static final int MESSAGE_SIZE = 64 * 1024;

    // Sent messages waiting for an ack, kept for retransmission - never expired, this is the bug
    static final Map<Long, ByteBuffer> PENDING_ACKS = new HashMap<>();

    private final BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
            .filter(pool -> pool.getName().equals("direct"))
            .findFirst().orElseThrow();

    @Override
    public String id() {
        return "s1ex5";
    }

    @Override
    public String description() {
        return "direct buffer leak in MessageSender.PENDING_ACKS (NMT, heap dump)";
    }

    @Override
    public void start() {
        Threads.startDaemon("message-sender", () -> {
            long messageId = 0;
            while (true) {
                // Payload is built on the heap, then copied into the direct buffer for sending
                byte[] payload = new byte[MESSAGE_SIZE];
                ThreadLocalRandom.current().nextBytes(payload);
                send(++messageId, payload);

                // The peer acknowledges 95% of messages, the rest of the acks are lost
                if (ThreadLocalRandom.current().nextInt(100) < 95) {
                    onAck(messageId);
                }
                if (messageId % 10 == 0) {
                    Threads.sleep(100);
                }
            }
        });
    }

    static void send(long messageId, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);
        buffer.put(payload).flip();
        PENDING_ACKS.put(messageId, buffer);
    }

    static void onAck(long messageId) {
        PENDING_ACKS.remove(messageId);
    }

    @Override
    public String status() {
        return "direct buffers=%d (%d MB)".formatted(direct.getCount(), direct.getMemoryUsed() / (1024 * 1024));
    }
}
