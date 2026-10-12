package hr.javacro.section1;

/// Fixed version of [Ex1]: consistent lock ordering.
///
/// Two threads still transfer money between the same two accounts in opposite directions,
/// but `transfer` always locks the accounts in the same global order (by name),
/// regardless of transfer direction. No thread can hold the "second" lock while
/// waiting for the "first", so the circular wait from [Ex1] cannot occur.
public class Ex1Fixed {

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
        // Always lock in the same order, independent of transfer direction
        Account first = from.name.compareTo(to.name) < 0 ? from : to;
        Account second = first == from ? to : from;
        synchronized (first) {
            // Same delay as in Ex1 - now harmless, the other thread waits for `first`
            sleep(100);
            synchronized (second) {
                from.balance -= amount;
                to.balance += amount;
                IO.println("Transferred " + amount + " from " + from + " to " + to);
            }
        }
    }

    static void main() throws InterruptedException {
        Account alice = new Account("alice", 100);
        Account bob = new Account("bob", 100);

        Thread t1 = new Thread(() -> transfer(alice, bob, 10), "transfer-alice-to-bob");
        Thread t2 = new Thread(() -> transfer(bob, alice, 20), "transfer-bob-to-alice");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        IO.println("Done. alice=" + alice.balance + ", bob=" + bob.balance);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
