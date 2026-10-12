package hr.javacro.section1;

/// Deadlock caused by inconsistent lock ordering.
///
/// Two threads transfer money between the same two accounts in opposite directions.
/// Each one locks its "from" account first, then tries to lock the "to" account,
/// so each thread ends up holding the lock the other one needs.
///
/// How to see it in a thread dump:
/// `jstack <pid>` (or `jcmd <pid> Thread.print`) ends with
/// "Found one Java-level deadlock", showing both threads BLOCKED in `transfer`,
/// each "waiting to lock" the Account the other one has "locked".
///
/// Two synchronized blocks holding monitors in different order
/// Look for “Found one Java-level deadlock;
/// Synchronized block holds a lock when executing a long operation
/// Many workers in BLOCKED, one in TIMED_WAITING
/// After a fix all in TIMED_WAITING
/// Virtual threads occupy all carrier threads (# cores) and a light heartbeat thread cannot wake up
/// Thread dump taken with jstack shows only platform threads (with carried threads) - all in crunch method; the
/// heartbeat virtual thread is not visible at all
/// Json format shows a hierarchy of thread containers
public class Ex1 {

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

    static void transfer(Account from, Account to, int amount) {
        synchronized (from) {
            // Give the other thread time to lock its first account
            sleep(100);
            synchronized (to) {
                from.balance -= amount;
                to.balance += amount;
                IO.println("Transferred " + amount + " from " + from + " to " + to);
            }
        }
    }

    static void main() {
        Account alice = new Account("alice", 100);
        Account bob = new Account("bob", 100);

        Thread t1 = new Thread(() -> transfer(alice, bob, 10), "transfer-alice-to-bob");
        Thread t2 = new Thread(() -> transfer(bob, alice, 20), "transfer-bob-to-alice");
        t1.start();
        t2.start();

        IO.println("PID: " + ProcessHandle.current().pid());
        IO.println("Threads started. The app now hangs, so take a thread dump with: jstack " +
                ProcessHandle.current().pid());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

/*
My running java application is stuck. Get and analyze stacktraces.
 */