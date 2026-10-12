package hr.javacro.section1;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Fixed version of [Ex3]: CPU-bound work runs on platform threads.
///
/// Virtual threads are meant for tasks that spend most of their time blocked (I/O, sleep, locks).
/// The CPU-heavy reports now run on a fixed pool of platform threads, which the OS time-slices
/// like any other thread. The virtual thread carriers stay free, so the heartbeat
/// keeps running even while every core is busy computing reports.
///
/// How to see it in a thread dump:
/// - `jstack <pid>` shows the `report-N` platform threads RUNNABLE in `Ex3Fixed.crunch`,
///   and the `ForkJoinPool-1-worker-N` carriers are idle (not "Carrying virtual thread").
/// - `jcmd <pid> Thread.dump_to_file ex3fixed.txt` shows the `heartbeat` virtual thread
///   in `Thread.sleep` - same as in Ex3, so the dump alone does not prove it is healthy.
///   The difference is that a carrier is free to wake it up, and the console shows it still printing.
public class Ex3Fixed {

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

        // Same CPU-bound load as Ex3, but on a dedicated pool of platform threads
        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService cpuPool = Executors.newFixedThreadPool(cores,
                Thread.ofPlatform().name("report-", 1).daemon(true).factory());
        for (int i = 1; i <= cores; i++) {
            cpuPool.submit(Ex3Fixed::computeReport);
        }

        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Started " + cores +
                " CPU-bound tasks on platform threads. Heartbeat keeps going, compare dumps with Ex3:");
        IO.println("  jstack " + ProcessHandle.current().pid());
        IO.println("  jcmd " + ProcessHandle.current().pid() + " Thread.dump_to_file -format=json ex3fixed.json");

        while (true) {
            sleep(2000);
            IO.println("main: last heartbeat " + (System.currentTimeMillis() - lastHeartbeat) / 1000 + "s ago");
        }
    }

    static void computeReport() {
        long x = Thread.currentThread().threadId();
        while (true) {
            // Still pure CPU work - but a platform thread gets preempted by the OS
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
