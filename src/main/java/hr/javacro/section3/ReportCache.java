package hr.javacro.section3;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/// Section 1, example 4 ([hr.javacro.section1.Ex4]) - heap leak caused by a cache key without `equals` / `hashCode`.
///
/// There are only 100 users x 3 report types, but every request creates a "new" `ReportKey`,
/// so the cache never hits and the static `CACHE` grows by a 10 KB report per request (~1 MB/s).
///
/// Heap dump: `jcmd <pid> GC.class_histogram` taken a minute apart shows `ReportCache$ReportKey`
/// and `ReportCache$Report` counts growing. `jcmd <pid> GC.heap_dump heap.hprof` opened in Eclipse MAT
/// shows the static `ReportCache.CACHE` HashMap as a leak suspect; grouping `ReportKey` by `userId`
/// shows only 100 distinct users.
final class ReportCache implements Scenario {

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

    private volatile int entries;

    @Override
    public String id() {
        return "s1ex4";
    }

    @Override
    public String description() {
        return "heap leak in ReportCache.CACHE (heap dump)";
    }

    @Override
    public void start() {
        Threads.startDaemon("report-requests", () -> {
            long requests = 0;
            while (true) {
                String userId = "user-" + ThreadLocalRandom.current().nextInt(100);
                String reportType = REPORT_TYPES[ThreadLocalRandom.current().nextInt(REPORT_TYPES.length)];
                getReport(userId, reportType);
                entries = CACHE.size();
                if (++requests % 10 == 0) {
                    Threads.sleep(100);
                }
            }
        });
    }

    static Report getReport(String userId, String reportType) {
        return CACHE.computeIfAbsent(new ReportKey(userId, reportType), _ -> render());
    }

    static Report render() {
        return new Report(10 * 1024);
    }

    @Override
    public String status() {
        return "report cache=" + entries + " entries";
    }
}
