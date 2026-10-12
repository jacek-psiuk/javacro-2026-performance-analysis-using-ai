package hr.javacro.section3;

/// Section 1, example 3 ([hr.javacro.section1.Ex3]) - carrier thread starvation by CPU-bound virtual threads.
///
/// One CPU-heavy `report-N` virtual thread is started per carrier thread. They never block, so they
/// occupy every carrier forever, and the `heartbeat` virtual thread never gets one to wake up on.
/// The status line shows "heartbeat Ns ago" growing.
///
/// [Workshop] limits the carrier pool to 2 (`-Djdk.virtualThreadScheduler.parallelism`) so this
/// scenario burns 2 cores instead of all of them.
///
/// Thread dump: `jstack <pid>` shows every `ForkJoinPool-1-worker-N` "Carrying virtual thread" in
/// `ReportingJobs.crunch`, and no `heartbeat`. `jcmd <pid> Thread.dump_to_file -format=json threads.json`
/// lists virtual threads too: `heartbeat` looks like it is just sleeping.
final class ReportingJobs implements Scenario {

    static volatile long sink;

    private volatile long lastHeartbeat = System.currentTimeMillis();

    @Override
    public String id() {
        return "s1ex3";
    }

    @Override
    public String description() {
        return "virtual thread carriers starved by ReportingJobs.crunch (thread dump)";
    }

    @Override
    public void start() {
        Thread.ofVirtual().name("heartbeat").start(() -> {
            while (true) {
                lastHeartbeat = System.currentTimeMillis();
                Threads.sleep(500);
            }
        });

        Threads.sleep(1000);

        // One CPU-bound task per carrier thread - enough to take all of them
        int carriers = Integer.getInteger("jdk.virtualThreadScheduler.parallelism",
                Runtime.getRuntime().availableProcessors());
        for (int i = 1; i <= carriers; i++) {
            Thread.ofVirtual().name("report-" + i).start(ReportingJobs::computeReport);
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

    @Override
    public String status() {
        return "heartbeat " + (System.currentTimeMillis() - lastHeartbeat) / 1000 + "s ago";
    }
}
