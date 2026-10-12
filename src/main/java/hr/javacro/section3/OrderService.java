package hr.javacro.section3;

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

/// Section 2, example 1 ([hr.javacro.section2.Ex1]) - correct but slow order processing, made for JFR.
///
/// 1. Lock contention - every order writes an audit record inside `synchronized (AUDIT_LOG)`
///    that also waits for the (simulated) audit store.
/// 2. CPU - each SKU is validated with `String.matches`, which compiles the regex every call.
/// 3. Exceptions - invalid quantities are detected by catching `NumberFormatException`.
/// 4. Allocation - boxed prices (`Map<Long, Double>`, `Double total`) and `String.format` per line.
///
/// Each order is recorded as a custom JFR event `hr.javacro.OrderProcessed`.
///
/// JFR: `jcmd <pid> JFR.start settings=profile duration=60s filename=workshop.jfr`, then
/// `jfr view contention-by-site|hot-methods|exception-count|allocation-by-site|hr.javacro.OrderProcessed workshop.jfr`.
final class OrderService implements Scenario {

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

    private final AtomicLong processed = new AtomicLong();
    private final Threads.Rate rate = new Threads.Rate();

    @Override
    public String id() {
        return "s2ex1";
    }

    @Override
    public String description() {
        return "slow OrderService.processOrder: contention, regex, exceptions, boxing (JFR)";
    }

    @Override
    public void start() {
        for (long i = 0; i < 1_000; i++) {
            PRICES.put(i, 1 + (i % 100) / 10.0);
        }

        AtomicLong nextId = new AtomicLong();
        for (int i = 1; i <= 8; i++) {
            Threads.startDaemon("order-worker-" + i, () -> {
                while (true) {
                    processOrder(randomOrder(nextId.incrementAndGet()));
                    processed.incrementAndGet();
                }
            });
        }
    }

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
            Threads.sleep(2);
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

    @Override
    public String status() {
        return "orders=" + rate.perSecond(processed.get()) + "/s";
    }
}
