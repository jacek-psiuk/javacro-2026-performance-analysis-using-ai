package hr.javacro.section1;

/// Carrier thread starvation caused by CPU-bound work on virtual threads.
///
/// Virtual threads run on a small pool of platform "carrier" threads
/// (one per CPU core by default) and are not time-sliced: a virtual thread only
/// gives up its carrier when it blocks (sleep, I/O, lock, queue...).
/// Here one CPU-heavy "report" virtual thread is started per core. They never block,
/// so they occupy every carrier forever. The "heartbeat" virtual thread wakes up
/// from its sleep, is ready to run, but never gets a carrier again.
/// The main thread is a platform thread, so it keeps running and reports the silence.
///
/// How to see it in a thread dump:
/// - `jstack <pid>` lists only platform threads. Every `ForkJoinPool-1-worker-N`
///   carrier is "Carrying virtual thread" and shows `Ex3.crunch` on top of its stack.
///   The starving heartbeat thread does not appear at all.
/// - `jcmd <pid> Thread.dump_to_file -format=json ex3.json` (or without `-format=json`
///   for plain text) also lists virtual threads: the `report-N` threads are RUNNABLE in `crunch`,
///   and `heartbeat` still shows TIMED_WAITING in `Thread.sleep` - although its 500 ms sleep
///   expired long ago. Waking it up needs a free carrier too, so it looks idle, not starved.
///   The dump alone cannot tell it apart from a healthy sleeping thread: the clue is that
///   every carrier is busy in jstack while the heartbeat output has stopped.
public class Ex3 {

    static volatile long sink;
    static volatile long lastHeartbeat = System.currentTimeMillis();

    static void main() {
        Thread.ofVirtual().name("heartbeat").start(() -> {
            while (true) {
                lastHeartbeat = System.currentTimeMillis();
                IO.println("heartbeat");
                sleep(500);
            }
        });

        sleep(2000);

        // One CPU-bound task per carrier thread - enough to take all of them
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 1; i <= cores; i++) {
            Thread.ofVirtual().name("report-" + i).start(Ex3::computeReport);
        }

        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Started " + cores + " CPU-bound virtual threads. Heartbeat stops, so take thread dumps with:");
        IO.println("  jstack " + ProcessHandle.current().pid());
        IO.println("  jcmd " + ProcessHandle.current().pid() + " Thread.dump_to_file -format=json ex3.json");

        // main is a platform thread - it is not affected by the starved carriers
        while (true) {
            sleep(2000);
            IO.println("main: last heartbeat " + (System.currentTimeMillis() - lastHeartbeat) / 1000 + "s ago");
        }
    }

    static void computeReport() {
        long x = Thread.currentThread().threadId();
        while (true) {
            // Pure CPU work, never blocks, so never unmounts from its carrier
            x = crunch(x);
            sink = x;
        }
    }

    static long crunch(long x) {
        for (int i = 0; i < 1_000_000; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
        }
        return x;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
