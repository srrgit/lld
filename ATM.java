import java.util.*;

/**
 * ATM: State pattern for the session, Chain of Responsibility for note dispensing,
 * and a BankService interface so the ATM never touches account data directly.
 */
public class ATM {
    interface BankService {
        boolean verifyPin(String card, String pin);
        int balance(String card);
        boolean debit(String card, int amount);   // atomic: false if insufficient funds
        void credit(String card, int amount);     // used to roll back a failed dispense
    }

    static class InMemoryBank implements BankService {
        private final Map<String, String> pins = new HashMap<>();
        private final Map<String, Integer> balances = new HashMap<>();
        void open(String card, String pin, int bal) { pins.put(card, pin); balances.put(card, bal); }
        public boolean verifyPin(String card, String pin) { return pin.equals(pins.get(card)); }
        public int balance(String card) { return balances.get(card); }
        public synchronized boolean debit(String card, int amt) {
            int b = balances.get(card);
            if (b < amt) return false;
            balances.put(card, b - amt); return true;
        }
        public synchronized void credit(String card, int amt) { balances.merge(card, amt, Integer::sum); }
    }

    /** One link per denomination; each takes as many notes as it can, then passes the rest on. */
    static class NoteHandler {
        final int denom; int count; NoteHandler next;
        NoteHandler(int denom, int count) { this.denom = denom; this.count = count; }
        boolean canDispense(int amt, Map<Integer, Integer> plan) {
            int use = Math.min(amt / denom, count);
            if (use > 0) plan.put(denom, use);
            int rest = amt - use * denom;
            if (rest == 0) return true;
            return next != null && next.canDispense(rest, plan);
        }
        void commit(Map<Integer, Integer> plan) {
            Integer n = plan.get(denom);
            if (n != null) count -= n;
            if (next != null) next.commit(plan);
        }
        void restore(Map<Integer, Integer> plan) {
            Integer n = plan.get(denom);
            if (n != null) count += n;
            if (next != null) next.restore(plan);
        }
    }

    static class CashDispenser {
        private final NoteHandler head;
        CashDispenser(NoteHandler head) { this.head = head; }
        /** Returns the note plan, or empty if the amount cannot be made exactly. */
        synchronized Optional<Map<Integer, Integer>> dispense(int amt) {
            Map<Integer, Integer> plan = new TreeMap<>(Comparator.reverseOrder());
            if (!head.canDispense(amt, plan)) return Optional.empty();
            head.commit(plan);
            return Optional.of(plan);
        }
        synchronized void restore(Map<Integer, Integer> plan) { head.restore(plan); }
    }

    interface State {
        void insertCard(ATM a, String card);
        void enterPin(ATM a, String pin);
        void withdraw(ATM a, int amount);
        void eject(ATM a);
    }

    static class IdleState implements State {
        public void insertCard(ATM a, String card) { a.card = card; a.pinAttempts = 0; a.state = new CardInsertedState(); System.out.println("  Card accepted"); }
        public void enterPin(ATM a, String pin) { throw new IllegalStateException("Insert card first"); }
        public void withdraw(ATM a, int amt) { throw new IllegalStateException("Insert card first"); }
        public void eject(ATM a) {}
    }

    static class CardInsertedState implements State {
        public void insertCard(ATM a, String card) { throw new IllegalStateException("Card already inserted"); }
        public void enterPin(ATM a, String pin) {
            if (a.bank.verifyPin(a.card, pin)) { a.state = new AuthenticatedState(); System.out.println("  PIN OK"); return; }
            if (++a.pinAttempts >= 3) { System.out.println("  Card retained after 3 bad PINs"); a.card = null; a.state = new IdleState(); return; }
            throw new IllegalArgumentException("Wrong PIN (" + (3 - a.pinAttempts) + " tries left)");
        }
        public void withdraw(ATM a, int amt) { throw new IllegalStateException("Enter PIN first"); }
        public void eject(ATM a) { a.card = null; a.state = new IdleState(); }
    }

    static class AuthenticatedState implements State {
        public void insertCard(ATM a, String card) { throw new IllegalStateException("Card already inserted"); }
        public void enterPin(ATM a, String pin) { throw new IllegalStateException("Already authenticated"); }
        public void withdraw(ATM a, int amt) {
            if (amt <= 0 || amt % 100 != 0) throw new IllegalArgumentException("Amount must be a multiple of 100");
            // 1) check the machine can physically pay out  2) debit  3) dispense  4) roll back on failure
            Optional<Map<Integer, Integer>> plan = a.dispenser.dispense(amt);
            if (plan.isEmpty()) throw new IllegalStateException("ATM cannot dispense that amount");
            if (!a.bank.debit(a.card, amt)) {
                a.dispenser.restore(plan.get());
                throw new IllegalStateException("Insufficient funds");
            }
            System.out.println("  Dispensed " + amt + " as " + plan.get());
        }
        public void eject(ATM a) { a.card = null; a.state = new IdleState(); System.out.println("  Card ejected"); }
    }

    private State state = new IdleState();
    private String card;
    private int pinAttempts;
    private final BankService bank;
    private final CashDispenser dispenser;

    ATM(BankService bank, CashDispenser d) { this.bank = bank; this.dispenser = d; }
    void insertCard(String c) { state.insertCard(this, c); }
    void enterPin(String p) { state.enterPin(this, p); }
    void withdraw(int amt) { state.withdraw(this, amt); }
    void eject() { state.eject(this); }

    public static void main(String[] args) {
        InMemoryBank bank = new InMemoryBank();
        bank.open("CARD-1", "1234", 5000);
        NoteHandler h2000 = new NoteHandler(2000, 2), h500 = new NoteHandler(500, 5), h100 = new NoteHandler(100, 10);
        h2000.next = h500; h500.next = h100;
        ATM atm = new ATM(bank, new CashDispenser(h2000));

        System.out.println("Withdraw without card:");
        try { atm.withdraw(500); } catch (IllegalStateException e) { System.out.println("  " + e.getMessage()); }

        System.out.println("Normal session:");
        atm.insertCard("CARD-1"); atm.enterPin("1234"); atm.withdraw(2600);
        System.out.println("  Balance now " + bank.balance("CARD-1"));
        try { atm.withdraw(4000); } catch (IllegalStateException e) { System.out.println("  " + e.getMessage()); }
        System.out.println("  Balance still " + bank.balance("CARD-1"));
        atm.eject();

        System.out.println("Three wrong PINs:");
        atm.insertCard("CARD-1");
        for (int i = 0; i < 3; i++) {
            try { atm.enterPin("0000"); } catch (IllegalArgumentException e) { System.out.println("  " + e.getMessage()); }
        }
    }
}
