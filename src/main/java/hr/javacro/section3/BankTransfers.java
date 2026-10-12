package hr.javacro.section3;

import java.util.concurrent.atomic.AtomicInteger;

/// Section 1, example 1 ([hr.javacro.section1.Ex1]) - deadlock caused by inconsistent lock ordering.
///
/// Two threads transfer money between the same two accounts in opposite directions,
/// each locking its "from" account first. Both end up waiting for the other's lock forever.
///
/// Thread dump: `jstack -l <pid>` ends with "Found one Java-level deadlock" listing
/// `transfer-alice-to-bob` and `transfer-bob-to-alice`, both BLOCKED in `BankTransfers.transfer`.
final class BankTransfers implements Scenario {

    static class Account {

        private final String name;
        private int balance;

        Account(String name, int balance) {
            this.name = name;
            this.balance = balance;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final AtomicInteger completed = new AtomicInteger();

    @Override
    public String id() {
        return "s1ex1";
    }

    @Override
    public String description() {
        return "deadlock in BankTransfers.transfer (thread dump)";
    }

    @Override
    public void start() {
        Account alice = new Account("alice", 100);
        Account bob = new Account("bob", 100);
        Threads.startDaemon("transfer-alice-to-bob", () -> transfer(alice, bob, 10));
        Threads.startDaemon("transfer-bob-to-alice", () -> transfer(bob, alice, 20));
    }

    void transfer(Account from, Account to, int amount) {
        synchronized (from) {
            // Give the other thread time to lock its first account
            Threads.sleep(100);
            synchronized (to) {
                from.balance -= amount;
                to.balance += amount;
                completed.incrementAndGet();
            }
        }
    }

    @Override
    public String status() {
        return "transfers done=" + completed.get() + "/2";
    }
}
