package hr.javacro.section2;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/// Fixed version of [Ex1] - same orders, same results, the four problems removed:
///
/// 1. Lock contention - workers only put the audit record into a queue; one background
///    writer drains it and writes whole batches, so the slow audit store is hit once per batch
///    and no worker waits for it.
/// 2. CPU - the SKU pattern is compiled once (`static final Pattern`).
/// 3. Exceptions - quantities are validated before parsing, so invalid input is a plain `if`.
/// 4. Allocation - prices are a `double[]`, the total is a primitive `double`, and invoice lines
///    are appended to one `StringBuilder` instead of `String.format`.
///
/// How to see it with JFR: record it exactly like [Ex1] and compare the views -
/// `contention-by-site` has no `audit` entry, `exception-count` drops to ~0,
/// throughput is limited by CPU, and `hot-methods` now shows the real work.
public class Ex1Fixed {

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

    static final Pattern SKU = Pattern.compile("[A-Z]{3}-\\d{5}");
    static final double[] PRICES = new double[1_000];
    static final BlockingQueue<String> AUDIT_QUEUE = new LinkedBlockingQueue<>(100_000);
    static final AtomicLong processed = new AtomicLong();

    static String processOrder(Order order) {
        OrderProcessed event = new OrderProcessed();
        event.begin();

        double total = 0;
        int invalid = 0;
        StringBuilder invoice = new StringBuilder(8 * 1024);
        for (Item item : order.items()) {
            if (!SKU.matcher(item.sku()).matches()) {
                continue;
            }
            if (!isQuantity(item.quantityText())) {
                invalid++;
                continue;
            }
            int quantity = Integer.parseInt(item.quantityText());
            int productId = (int) (Long.parseLong(item.sku(), 4, 9, 10) % PRICES.length);
            double lineTotal = PRICES[productId] * quantity;
            total += lineTotal;
            invoice.append(item.sku()).append(" x").append(quantity).append(" = ");
            appendAmount(invoice, lineTotal).append('\n');
        }
        appendAmount(invoice.append("TOTAL "), total);

        audit(order.id(), total);

        event.orderId = order.id();
        event.items = order.items().size();
        event.invalidQuantities = invalid;
        event.commit();
        return invoice.toString();
    }

    static boolean isQuantity(String text) {
        if (text.isEmpty() || text.length() > 9) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    static StringBuilder appendAmount(StringBuilder sb, double amount) {
        long cents = Math.round(amount * 100);
        sb.append(cents / 100).append('.');
        long rest = cents % 100;
        return sb.append(rest < 10 ? "0" : "").append(rest);
    }

    static void audit(long orderId, double total) {
        // Non-blocking hand-off; the writer thread does the slow part
        AUDIT_QUEUE.offer(orderId + ";" + total);
    }

    static void auditWriter() {
        List<String> batch = new ArrayList<>();
        while (true) {
            try {
                batch.add(AUDIT_QUEUE.take());
            } catch (InterruptedException e) {
                return;
            }
            AUDIT_QUEUE.drainTo(batch, 10_000);
            // One write to the audit store per batch
            sleep(2);
            batch.clear();
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
        for (int i = 0; i < PRICES.length; i++) {
            PRICES[i] = 1 + (i % 100) / 10.0;
        }

        Thread writer = new Thread(Ex1Fixed::auditWriter, "audit-writer");
        writer.setDaemon(true);
        writer.start();

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
                + " JFR.start settings=profile duration=30s filename=ex1fixed.jfr");

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
