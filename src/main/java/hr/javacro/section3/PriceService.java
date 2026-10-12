package hr.javacro.section3;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/// Section 1, example 2 ([hr.javacro.section1.Ex2]) - lock contention caused by slow work under a lock.
///
/// The price cache calls a slow "remote" service inside its `synchronized` method,
/// so 8 workers queue up behind it - even those asking for an already cached price.
///
/// Thread dump: `jstack -l <pid>` shows most `price-worker-N` threads BLOCKED in
/// `PriceService$PriceCache.getPrice`, "waiting to lock" the same `PriceCache`, while one
/// owner sleeps in `fetchFromRemote`. JFR shows it too: `jfr view contention-by-site`.
final class PriceService implements Scenario {

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
            Threads.sleep(500);
            return Math.abs(symbol.hashCode() % 1000);
        }
    }

    private final AtomicLong requests = new AtomicLong();
    private final Threads.Rate rate = new Threads.Rate();

    @Override
    public String id() {
        return "s1ex2";
    }

    @Override
    public String description() {
        return "lock contention on PriceService$PriceCache (thread dump, JFR)";
    }

    @Override
    public void start() {
        PriceCache cache = new PriceCache();
        for (int i = 1; i <= 8; i++) {
            Threads.startDaemon("price-worker-" + i, () -> {
                while (true) {
                    // Half the requests hit an already cached symbol, half are new
                    String symbol = ThreadLocalRandom.current().nextBoolean()
                            ? "HOT"
                            : "SYM-" + ThreadLocalRandom.current().nextInt(1_000_000);
                    cache.getPrice(symbol);
                    requests.incrementAndGet();
                }
            });
        }
    }

    @Override
    public String status() {
        return "prices=" + rate.perSecond(requests.get()) + " req/s";
    }
}
