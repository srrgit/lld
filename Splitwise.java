import java.util.*;

/**
 * Splitwise: money is stored as whole cents (long) so splits never drift by fractions of a cent.
 * Strategy pattern for split types. The ledger keeps ONE direction per pair of users
 * (opposite debts are netted), and simplify() computes net balances then greedily matches the
 * biggest creditor with the biggest debtor using two heaps, giving at most n-1 payments.
 */
public class Splitwise {
    interface SplitStrategy { Map<String, Long> shares(long totalCents); }

    static class EqualSplit implements SplitStrategy {
        private final List<String> users;
        EqualSplit(String... users) { this.users = List.of(users); }
        public Map<String, Long> shares(long total) {
            long base = total / users.size(), rem = total % users.size();
            Map<String, Long> m = new LinkedHashMap<>();
            for (int i = 0; i < users.size(); i++) m.put(users.get(i), base + (i < rem ? 1 : 0));  // spread leftover cents
            return m;
        }
    }

    static class ExactSplit implements SplitStrategy {
        private final Map<String, Long> exact;
        ExactSplit(Map<String, Long> exact) { this.exact = exact; }
        public Map<String, Long> shares(long total) {
            long sum = exact.values().stream().mapToLong(Long::longValue).sum();
            if (sum != total) throw new IllegalArgumentException("Exact amounts add up to " + sum + " but the expense is " + total);
            return exact;
        }
    }

    static class PercentSplit implements SplitStrategy {
        private final Map<String, Double> pct;
        PercentSplit(Map<String, Double> pct) { this.pct = pct; }
        public Map<String, Long> shares(long total) {
            double sum = pct.values().stream().mapToDouble(Double::doubleValue).sum();
            if (Math.abs(sum - 100.0) > 1e-6) throw new IllegalArgumentException("Percentages add up to " + sum + ", not 100");
            Map<String, Long> m = new LinkedHashMap<>();
            long assigned = 0;
            for (var e : pct.entrySet()) { long c = (long) Math.floor(total * e.getValue() / 100.0 + 1e-9); m.put(e.getKey(), c); assigned += c; }
            String first = pct.keySet().iterator().next();
            m.merge(first, total - assigned, Long::sum);          // rounding remainder goes to the first user
            return m;
        }
    }

    static class Ledger {
        private final Map<String, Map<String, Long>> owed = new TreeMap<>();   // owed[debtor][creditor] = cents

        private long get(String a, String b) { return owed.getOrDefault(a, Map.of()).getOrDefault(b, 0L); }
        private void set(String a, String b, long v) { owed.computeIfAbsent(a, k -> new TreeMap<>()).put(b, v); }

        void addExpense(String payer, long total, SplitStrategy strategy) {
            for (var e : strategy.shares(total).entrySet())
                if (!e.getKey().equals(payer) && e.getValue() > 0) transfer(e.getKey(), payer, e.getValue());
        }

        /** from owes `to` an extra amt; cancel against any opposite debt first. */
        private void transfer(String from, String to, long amt) {
            long reverse = get(to, from);
            if (reverse >= amt) set(to, from, reverse - amt);
            else { set(to, from, 0); set(from, to, get(from, to) + amt - reverse); }
        }

        List<String> balances() {
            List<String> out = new ArrayList<>();
            owed.forEach((debtor, m) -> m.forEach((creditor, c) -> { if (c > 0) out.add(debtor + " owes " + creditor + " " + money(c)); }));
            return out;
        }

        List<String> simplify() {
            Map<String, Long> net = new TreeMap<>();               // positive = is owed money
            owed.forEach((debtor, m) -> m.forEach((creditor, c) -> {
                net.merge(creditor, c, Long::sum); net.merge(debtor, -c, Long::sum);
            }));
            PriorityQueue<Map.Entry<String, Long>> creditors = new PriorityQueue<>((x, y) -> Long.compare(y.getValue(), x.getValue()));
            PriorityQueue<Map.Entry<String, Long>> debtors = new PriorityQueue<>((x, y) -> Long.compare(y.getValue(), x.getValue()));
            net.forEach((u, v) -> {
                if (v > 0) creditors.add(new AbstractMap.SimpleEntry<>(u, v));
                else if (v < 0) debtors.add(new AbstractMap.SimpleEntry<>(u, -v));
            });
            List<String> out = new ArrayList<>();
            while (!creditors.isEmpty() && !debtors.isEmpty()) {
                var c = creditors.poll(); var d = debtors.poll();
                long pay = Math.min(c.getValue(), d.getValue());
                out.add(d.getKey() + " pays " + c.getKey() + " " + money(pay));
                if (c.getValue() > pay) creditors.add(new AbstractMap.SimpleEntry<>(c.getKey(), c.getValue() - pay));
                if (d.getValue() > pay) debtors.add(new AbstractMap.SimpleEntry<>(d.getKey(), d.getValue() - pay));
            }
            return out;
        }
    }

    static String money(long cents) { return String.format("$%.2f", cents / 100.0); }

    public static void main(String[] args) {
        Ledger l = new Ledger();
        l.addExpense("Alice", 10000, new EqualSplit("Alice", "Bob", "Cy", "Dan"));
        l.addExpense("Bob", 6000, new EqualSplit("Alice", "Bob", "Cy"));
        l.addExpense("Cy", 9000, new ExactSplit(Map.of("Alice", 5000L, "Bob", 3000L, "Cy", 1000L)));
        Map<String, Double> pct = new LinkedHashMap<>();
        pct.put("Alice", 40.0); pct.put("Bob", 30.0); pct.put("Cy", 20.0); pct.put("Dan", 10.0);
        l.addExpense("Dan", 20000, new PercentSplit(pct));

        System.out.println("Raw balances (opposite debts already netted):");
        l.balances().forEach(s -> System.out.println("  " + s));
        System.out.println("Simplified:");
        l.simplify().forEach(s -> System.out.println("  " + s));

        System.out.println("Remainder cents: 100.00 split 3 ways = " + new EqualSplit("a", "b", "c").shares(10000));
        try { l.addExpense("Alice", 1000, new ExactSplit(Map.of("Bob", 400L, "Cy", 400L))); } catch (IllegalArgumentException e) { System.out.println("Rejected: " + e.getMessage()); }
        Map<String, Double> bad = new LinkedHashMap<>();
        bad.put("Bob", 60.0); bad.put("Cy", 30.0);
        try { l.addExpense("Alice", 1000, new PercentSplit(bad)); } catch (IllegalArgumentException e) { System.out.println("Rejected: " + e.getMessage()); }
    }
}
