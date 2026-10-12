package hr.javacro.section3;

final class Threads {

    private Threads() {
    }

    static Thread startDaemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Events per second since the previous call.
    static final class Rate {

        private long lastCount;
        private long lastNanos = System.nanoTime();

        long perSecond(long count) {
            long now = System.nanoTime();
            long rate = (count - lastCount) * 1_000_000_000L / Math.max(1, now - lastNanos);
            lastCount = count;
            lastNanos = now;
            return rate;
        }
    }
}
