package hr.javacro.section2;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/// An order-processing service that is correct, but slow - with several problems at once.
///
/// Nothing is stuck and nothing leaks, so a thread or heap dump shows little.
/// The question is "where does the time go?", and the answer is spread over
/// several dimensions that a profiler records together:
///
/// 1. Lock contention - every order writes an audit record inside a `synchronized` block
///    that also waits for the (simulated) audit store, so the 8 workers queue up behind it.
/// 2. CPU - each SKU is validated with `String.matches`, which compiles the regex every call.
/// 3. Exceptions - invalid quantities ("n/a", "") are detected by catching `NumberFormatException`.
/// 4. Allocation - prices are boxed (`Map<Long, Double>`, `Double total`) and every invoice line
///    is built with `String.format`.
///
/// Each order is also recorded as a custom JFR event `hr.javacro.OrderProcessed`
/// (duration, item count, invalid quantities), so slow orders can be correlated with the rest.
///
/// How to see it with JFR:
/// run with `-XX:StartFlightRecording=duration=30s,settings=profile,filename=ex1.jfr`
/// (or `jcmd <pid> JFR.start settings=profile duration=30s filename=ex1.jfr` on a running JVM), then
/// `jfr summary ex1.jfr` and `jfr view <view> ex1.jfr` with the views
/// `contention-by-site`, `hot-methods`, `exception-count`, `allocation-by-site`, `gc-pauses`,
/// and `hr.javacro.OrderProcessed` for the custom event.
public class Ex1 {

    @Name("hr.javacro.OrderProcessed")
    @Label("Order Processed")
    @Category("JavaCro")
    static class OrderProcessed extends Event {

        @Label("Order Id")
        long orderId;

        @Label("Items")
        int items;

        @Label("Invalid Quantities")
        int invalidQuantities;
    }

    record Item(String sku, String quantityText) {}

    record Order(long id, List<Item> items) {}

    static final Map<Long, Double> PRICES = new HashMap<>();
    static final List<String> AUDIT_LOG = new ArrayList<>();
    static final AtomicLong processed = new AtomicLong();

    static String processOrder(Order order) {
        OrderProcessed event = new OrderProcessed();
        event.begin();

        Double total = 0.0;
        int invalid = 0;
        List<String> lines = new ArrayList<>();
        for (Item item : order.items()) {
            // Compiles the pattern on every call
            if (!item.sku().matches("[A-Z]{3}-\\d{5}")) {
                continue;
            }
            int quantity;
            try {
                quantity = Integer.parseInt(item.quantityText());
            } catch (NumberFormatException e) {
                // Exceptions as control flow for an expected case
                invalid++;
                continue;
            }
            long productId = Long.parseLong(item.sku().substring(4)) % PRICES.size();
            Double price = PRICES.get(productId);
            Double lineTotal = price * quantity;
            total += lineTotal;
            lines.add(String.format("%s x%d = %.2f", item.sku(), quantity, lineTotal));
        }
        String invoice = String.join("\n", lines) + String.format("%nTOTAL %.2f", total);

        audit(order.id(), total);

        event.orderId = order.id();
        event.items = order.items().size();
        event.invalidQuantities = invalid;
        event.commit();
        return invoice;
    }

    static void audit(long orderId, double total) {
        synchronized (AUDIT_LOG) {
            // Synchronous write to the audit store while holding the lock
            sleep(2);
            if (AUDIT_LOG.size() > 10_000) {
                AUDIT_LOG.clear();
            }
            AUDIT_LOG.add(orderId + ";" + total);
        }
    }

    static Order randomOrder(long id) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String sku = "ABC-" + (10_000 + random.nextInt(90_000));
            String quantity = random.nextInt(10) == 0 ? "n/a" : String.valueOf(1 + random.nextInt(9));
            items.add(new Item(sku, quantity));
        }
        return new Order(id, items);
    }

    static void main() {
        for (long i = 0; i < 1_000; i++) {
            PRICES.put(i, 1 + (i % 100) / 10.0);
        }

        AtomicLong nextId = new AtomicLong();
        for (int i = 1; i <= 8; i++) {
            Thread worker = new Thread(() -> {
                while (true) {
                    processOrder(randomOrder(nextId.incrementAndGet()));
                    processed.incrementAndGet();
                }
            }, "order-worker-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Processing orders with 8 workers. To record: jcmd " + ProcessHandle.current().pid()
                + " JFR.start settings=profile duration=30s filename=ex1.jfr");

        long last = 0;
        while (true) {
            sleep(5000);
            long now = processed.get();
            IO.println("Throughput: " + (now - last) / 5 + " orders/s");
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
