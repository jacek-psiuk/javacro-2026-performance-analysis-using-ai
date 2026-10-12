package hr.javacro.section1;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/// Memory leak caused by a cache key without `equals` / `hashCode`.
///
/// The report service caches rendered reports per (user, report type).
/// There are only 100 users x 3 report types, so the cache should never hold
/// more than 300 entries. But `ReportKey` does not override `equals` and `hashCode`,
/// so every request creates a key that is "different" from all previous ones:
/// the cache never hits, and every request adds a new 10 KB report to a static map
/// that is never cleaned up. The heap grows until `OutOfMemoryError`.
///
/// How to see it in a heap dump:
/// `jcmd <pid> GC.heap_dump ex4.hprof`, then open it in Eclipse MAT
/// (or run `ParseHeapDump.bat ex4.hprof org.eclipse.mat.api:suspects`).
/// The leak suspect is the static `Ex4.CACHE` HashMap retaining most of the heap.
/// Grouping `ReportKey` by `userId` (MAT: `group_by_value`) shows only 100 distinct
/// users, each repeated over and over - the map holds thousands of "equal" keys.
public class Ex4 {

    // Missing equals/hashCode - this is the bug
    static class ReportKey {

        private final String userId;
        private final String reportType;

        ReportKey(String userId, String reportType) {
            this.userId = userId;
            this.reportType = reportType;
        }
    }

    static class Report {

        private final byte[] content;

        Report(int size) {
            this.content = new byte[size];
        }
    }

    static final Map<ReportKey, Report> CACHE = new HashMap<>();
    static final String[] REPORT_TYPES = {"daily", "weekly", "monthly"};

    static Report getReport(String userId, String reportType) {
        return CACHE.computeIfAbsent(new ReportKey(userId, reportType), _ -> render());
    }

    static Report render() {
        return new Report(10 * 1024);
    }

    static void main() {
        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Serving reports. Memory keeps growing, so take a heap dump with: jcmd "
                + ProcessHandle.current().pid() + " GC.heap_dump ex4.hprof");

        long requests = 0;
        long started = System.currentTimeMillis();
        while (true) {
            String userId = "user-" + ThreadLocalRandom.current().nextInt(100);
            String reportType = REPORT_TYPES[ThreadLocalRandom.current().nextInt(REPORT_TYPES.length)];
            getReport(userId, reportType);
            requests++;

            if (requests % 100 == 0) {
                sleep(100);
            }
            if (requests % 2_000 == 0) {
                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                IO.println("%3ds: requests=%d, cache entries=%d, heap used=%d MB"
                        .formatted((System.currentTimeMillis() - started) / 1000, requests, CACHE.size(), usedMb));
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
