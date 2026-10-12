package hr.javacro.section1;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/// Native memory leak caused by direct `ByteBuffer`s that are never released.
///
/// A message sender encodes each outgoing message into a 64 KB direct buffer
/// (as network code like Netty does) and keeps it until the peer acknowledges it,
/// so it can be retransmitted. 95% of acks arrive and the buffer is dropped.
/// The other 5% are "lost": there is no retransmit timeout, so those buffers stay
/// in `PENDING_ACKS` forever.
///
/// The memory of a direct buffer lives outside the Java heap. The heap stays at
/// a few MB, so heap metrics and GC logs look healthy, while the process keeps
/// growing - in a container until it is OOM-killed, otherwise until
/// `OutOfMemoryError: Cannot reserve ... bytes of direct buffer memory`
/// (the limit defaults to the max heap size when `-XX:MaxDirectMemorySize` is not set).
///
/// How to see it with Native Memory Tracking:
/// run with `-XX:NativeMemoryTracking=summary`, then
/// `jcmd <pid> VM.native_memory baseline`, wait, `jcmd <pid> VM.native_memory summary.diff`.
/// The `Other` category (where direct buffers are allocated) grows by hundreds of MB,
/// while `Java Heap` used memory does not.
/// A heap dump then explains *who* holds the memory: thousands of small `DirectByteBuffer`
/// objects (`capacity` 65536 each) reachable from the static `Ex5.PENDING_ACKS` map.
public class Ex5 {

    static final int MESSAGE_SIZE = 64 * 1024;

    // Sent messages waiting for an ack, kept for retransmission - never expired, this is the bug
    static final Map<Long, ByteBuffer> PENDING_ACKS = new HashMap<>();

    static void send(long messageId, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(MESSAGE_SIZE);
        buffer.put(payload).flip();
        PENDING_ACKS.put(messageId, buffer);
    }

    static void onAck(long messageId) {
        PENDING_ACKS.remove(messageId);
    }

    static void main() {
        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Sending messages. Heap stays small, the process does not. Check native memory with:");
        IO.println("  jcmd " + ProcessHandle.current().pid() + " VM.native_memory baseline");
        IO.println("  jcmd " + ProcessHandle.current().pid() + " VM.native_memory summary.diff");

        long messageId = 0;
        long started = System.currentTimeMillis();
        while (true) {
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
                IO.println("%3ds: messages sent=%d, heap used=%d MB"
                        .formatted((System.currentTimeMillis() - started) / 1000, messageId, usedMb));
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
