package hr.javacro.section1;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/// Fixed version of [Ex2]: no slow work while holding a lock.
///
/// The cache uses a `ConcurrentHashMap`, so reads take no lock at all and cached
/// prices are served immediately. On a miss the slow remote call runs *outside*
/// any lock, and the result is stored with `putIfAbsent`.
///
/// Trade-off: two threads missing the same symbol at the same time may both fetch it.
/// That is usually acceptable for a cache. Note that `computeIfAbsent(symbol, this::fetchFromRemote)`
/// is *not* a full fix here: it locks a hash bin while the function runs, so a slow fetch
/// can still block other keys in the same bin (its javadoc asks for short computations).
///
/// How to see it in a thread dump:
/// `jstack -l <pid>` shows the `worker-N` threads TIMED_WAITING in `fetchFromRemote`
/// in parallel, with no "locked" / "waiting to lock" lines and no BLOCKED threads.
public class Ex2Fixed {

    static class PriceCache {

        private final Map<String, Integer> prices = new ConcurrentHashMap<>();

        int getPrice(String symbol) {
            Integer price = prices.get(symbol);
            if (price == null) {
                // Slow I/O without holding any lock
                price = fetchFromRemote(symbol);
                Integer existing = prices.putIfAbsent(symbol, price);
                if (existing != null) {
                    price = existing;
                }
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
        IO.println("Workers started. Compare with Ex2 by taking a thread dump with: jstack -l " +
                ProcessHandle.current().pid());

        // Same workload as Ex2 - throughput is now limited only by the remote calls running in parallel
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
