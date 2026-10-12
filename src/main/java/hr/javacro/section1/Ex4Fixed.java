package hr.javacro.section1;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/// Fixed version of [Ex4]: a key with value semantics, and a bounded cache.
///
/// 1. `ReportKey` is a `record`, so `equals` and `hashCode` are generated from
///    `userId` and `reportType`. Two keys with the same fields find the same entry:
///    the cache hits, and it stops growing at 300 entries (100 users x 3 report types).
/// 2. The cache is bounded anyway: a `LinkedHashMap` in access order evicts the least
///    recently used entry above `MAX_ENTRIES`. Correct keys fix *this* leak; the bound
///    protects against the next one (more users, more report parameters, dates in the key).
///    In production prefer a cache library such as Caffeine (`maximumSize`, `expireAfterWrite`),
///    which is also thread-safe - this `LinkedHashMap` is only safe because the demo is single-threaded.
///
/// How to see it in a heap dump:
/// `jcmd <pid> GC.class_histogram` taken minutes apart shows a stable 300 `ReportKey`
/// and `Report` instances, and MAT finds no leak suspect around `Ex4Fixed.CACHE`.
public class Ex4Fixed {

    record ReportKey(String userId, String reportType) {}

    static class Report {

        private final byte[] content;

        Report(int size) {
            this.content = new byte[size];
        }
    }

    static final int MAX_ENTRIES = 1_000;

    static final Map<ReportKey, Report> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ReportKey, Report> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    static final String[] REPORT_TYPES = {"daily", "weekly", "monthly"};

    static long renders;

    static Report getReport(String userId, String reportType) {
        return CACHE.computeIfAbsent(new ReportKey(userId, reportType), _ -> render());
    }

    static Report render() {
        renders++;
        return new Report(10 * 1024);
    }

    static void main() {
        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Serving reports. Memory stays flat - compare with Ex4 using: jcmd "
                + ProcessHandle.current().pid() + " GC.class_histogram");

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
                IO.println("%3ds: requests=%d, renders=%d, cache entries=%d, heap used=%d MB"
                        .formatted((System.currentTimeMillis() - started) / 1000, requests, renders, CACHE.size(),
                                usedMb));
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
