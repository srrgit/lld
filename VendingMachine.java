import java.util.*;

/**
 * Vending Machine: State pattern. Each state decides which actions are legal,
 * so there are no if/else chains over a status flag.
 */
public class VendingMachine {
    enum Coin {
        NICKEL(5), DIME(10), QUARTER(25), DOLLAR(100);
        final int cents;
        Coin(int c) { cents = c; }
    }

    record Product(String name, int priceCents) {}

    static class Slot {
        final Product product; int qty;
        Slot(Product p, int q) { product = p; qty = q; }
    }

    interface State {
        void insertCoin(VendingMachine m, Coin c);
        void select(VendingMachine m, String code);
        void cancel(VendingMachine m);
    }

    static class IdleState implements State {
        public void insertCoin(VendingMachine m, Coin c) { m.balance += c.cents; m.setState(new HasMoneyState()); }
        public void select(VendingMachine m, String code) { throw new IllegalStateException("Insert money first"); }
        public void cancel(VendingMachine m) { /* nothing to refund */ }
    }

    static class HasMoneyState implements State {
        public void insertCoin(VendingMachine m, Coin c) { m.balance += c.cents; }
        public void select(VendingMachine m, String code) {
            Slot s = m.slots.get(code);
            if (s == null) throw new IllegalArgumentException("Unknown slot " + code);
            if (s.qty == 0) throw new IllegalStateException(s.product.name() + " is sold out");
            if (m.balance < s.product.priceCents())
                throw new IllegalStateException("Need " + (s.product.priceCents() - m.balance) + " more cents");
            int change = m.balance - s.product.priceCents();
            if (change > 0 && m.makeChange(change).isEmpty())       // never take the money if we cannot give change back
                throw new IllegalStateException("Exact change only: cannot return " + change + "c");
            m.setState(new DispensingState());
            m.dispense(s);
        }
        public void cancel(VendingMachine m) { m.refund(m.balance); m.balance = 0; m.setState(new IdleState()); }
    }

    static class DispensingState implements State {
        public void insertCoin(VendingMachine m, Coin c) { throw new IllegalStateException("Dispensing, please wait"); }
        public void select(VendingMachine m, String code) { throw new IllegalStateException("Dispensing, please wait"); }
        public void cancel(VendingMachine m) { throw new IllegalStateException("Cannot cancel while dispensing"); }
    }

    static class OutOfServiceState implements State {
        public void insertCoin(VendingMachine m, Coin c) { throw new IllegalStateException("Out of service"); }
        public void select(VendingMachine m, String code) { throw new IllegalStateException("Out of service"); }
        public void cancel(VendingMachine m) { /* balance was refunded when the fault happened */ }
    }

    private State state = new IdleState();
    private final Map<String, Slot> slots = new HashMap<>();
    private final Map<Coin, Integer> coinBank = new EnumMap<>(Coin.class);
    private int balance;
    private boolean jamNextDispense; // simulates a hardware fault for the demo

    void stock(String code, Product p, int qty) { slots.put(code, new Slot(p, qty)); }
    void loadCoins(Coin c, int n) { coinBank.merge(c, n, Integer::sum); }
    void setState(State s) { state = s; }
    void simulateJam() { jamNextDispense = true; }

    synchronized void insertCoin(Coin c) {
        state.insertCoin(this, c);
        coinBank.merge(c, 1, Integer::sum);
    }
    synchronized void select(String code) { state.select(this, code); }
    synchronized void cancel() { state.cancel(this); }

    private void dispense(Slot s) {
        if (jamNextDispense) {            // fault: refund everything, stop serving
            jamNextDispense = false;
            System.out.println("  !! dispense jam, refunding " + balance);
            refund(balance); balance = 0;
            setState(new OutOfServiceState());
            return;
        }
        s.qty--;                           // decrement and release happen together under the lock
        int change = balance - s.product.priceCents();
        balance = 0;
        System.out.println("  Dispensed " + s.product.name());
        if (change > 0) refund(change);
        setState(new IdleState());
    }

    /**
     * Finds a way to pay out exactly `cents` from the coins we hold, preferring big coins.
     * Plain greedy is not enough once stock is limited (30c with 1 quarter and 3 dimes: greedy takes the
     * quarter and is stuck), so this is a bounded backtracking search that remembers dead ends.
     */
    private Optional<Map<Coin, Integer>> makeChange(int cents) {
        List<Coin> desc = new ArrayList<>(List.of(Coin.values()));
        desc.sort(Comparator.comparingInt((Coin c) -> c.cents).reversed());
        Map<Coin, Integer> plan = new EnumMap<>(Coin.class);
        Set<Long> deadEnds = new HashSet<>();
        return search(desc, 0, cents, plan, deadEnds) ? Optional.of(plan) : Optional.empty();
    }

    private boolean search(List<Coin> coins, int idx, int left, Map<Coin, Integer> plan, Set<Long> deadEnds) {
        if (left == 0) return true;
        if (idx == coins.size()) return false;
        long key = (long) idx * 1_000_000L + left;
        if (deadEnds.contains(key)) return false;
        Coin c = coins.get(idx);
        for (int n = Math.min(left / c.cents, coinBank.getOrDefault(c, 0)); n >= 0; n--) {
            if (n > 0) plan.put(c, n);
            if (search(coins, idx + 1, left - n * c.cents, plan, deadEnds)) return true;
            plan.remove(c);
        }
        deadEnds.add(key);
        return false;
    }

    private void refund(int cents) {
        if (cents == 0) return;
        Map<Coin, Integer> out = makeChange(cents).orElse(Map.of());
        out.forEach((c, n) -> coinBank.merge(c, -n, Integer::sum));
        int paid = out.entrySet().stream().mapToInt(e -> e.getKey().cents * e.getValue()).sum();
        System.out.println("  Returned " + paid + "c as " + out + (paid < cents ? " (could not return " + (cents - paid) + "c!)" : ""));
    }

    public static void main(String[] args) {
        VendingMachine m = new VendingMachine();
        m.stock("A1", new Product("Chips", 75), 2);
        m.stock("B1", new Product("Soda", 125), 1);
        m.loadCoins(Coin.NICKEL, 10); m.loadCoins(Coin.DIME, 10); m.loadCoins(Coin.QUARTER, 10);

        System.out.println("1) Select before paying");
        try { m.select("A1"); } catch (IllegalStateException e) { System.out.println("  " + e.getMessage()); }

        System.out.println("2) Pay $1, buy chips (75c), expect 25c change");
        m.insertCoin(Coin.DOLLAR); m.select("A1");

        System.out.println("3) Insufficient funds, then cancel");
        m.insertCoin(Coin.QUARTER);
        try { m.select("B1"); } catch (IllegalStateException e) { System.out.println("  " + e.getMessage()); }
        m.cancel();

        System.out.println("4) Jam during dispense");
        m.insertCoin(Coin.DOLLAR); m.insertCoin(Coin.QUARTER); m.simulateJam(); m.select("B1");
        try { m.insertCoin(Coin.DIME); } catch (IllegalStateException e) { System.out.println("  " + e.getMessage()); }
    }
}
