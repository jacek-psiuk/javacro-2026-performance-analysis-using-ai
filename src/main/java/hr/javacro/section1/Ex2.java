package hr.javacro.section1;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/// Lock contention caused by doing slow work while holding a lock.
///
/// A price cache guards its map with `synchronized`, which is fine on its own.
/// The problem is that on a cache miss it calls a slow "remote" service *inside*
/// the synchronized method. Every other thread, including those asking for prices
/// that are already cached, has to wait until that remote call finishes.
/// The app is not deadlocked: it keeps working, just at a crawl.
///
/// How to see it in a thread dump:
/// `jstack -l <pid>` shows almost all `worker-N` threads BLOCKED in `getPrice`,
/// "waiting to lock" the same `PriceCache`. A single worker owns it ("locked")
/// and is sitting in `fetchFromRemote` (TIMED_WAITING, sleeping).
/// Take several dumps a few seconds apart: the owner changes, the pile-up stays.
public class Ex2 {

    static class PriceCache {

        private final Map<String, Integer> prices = new HashMap<>();

        synchronized int getPrice(String symbol) {
            Integer price = prices.get(symbol);
            if (price == null) {
                // Slow I/O while holding the lock - this is the bug
                price = fetchFromRemote(symbol);
                prices.put(symbol, price);
            }
            return price;
        }

        private static int fetchFromRemote(String symbol) {
            sleep(500);
            return Math.abs(symbol.hashCode() % 1000);
        }
    }

    static final AtomicLong requests = new AtomicLong();

    static void main() {
        PriceCache cache = new PriceCache();

        for (int i = 1; i <= 8; i++) {
            Thread worker = new Thread(() -> {
                while (true) {
                    // Half the requests hit an already cached symbol, half are new
                    String symbol = ThreadLocalRandom.current().nextBoolean()
                            ? "HOT"
                            : "SYM-" + ThreadLocalRandom.current().nextInt(1_000_000);
                    cache.getPrice(symbol);
                    requests.incrementAndGet();
                }
            }, "worker-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Workers started. The app is slow, so take a few thread dumps with: jstack -l " +
                ProcessHandle.current().pid());

        // 8 threads, half the lookups are cache hits - yet throughput is only a few requests/s
        long last = 0;
        while (true) {
            sleep(2000);
            long now = requests.get();
            IO.println("Throughput: " + (now - last) / 2 + " requests/s");
            last = now;
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
