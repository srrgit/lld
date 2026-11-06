import java.util.*;
import java.util.regex.*;

public class SplitwiseTest extends MiniTest {
    static long sum(Map<String, Long> m) { return m.values().stream().mapToLong(Long::longValue).sum(); }

    // ---- equal split -----------------------------------------------------------------------------
    void testEqualSplitDividesEvenly() {
        assertEquals(Map.of("a", 2500L, "b", 2500L, "c", 2500L, "d", 2500L), new Splitwise.EqualSplit("a", "b", "c", "d").shares(10000));
    }

    void testEqualSplitGivesLeftoverCentsToTheFirstUsersAndNeverLosesMoney() {
        Map<String, Long> m = new Splitwise.EqualSplit("a", "b", "c").shares(10000);
        assertEquals(List.of(3334L, 3333L, 3333L), new ArrayList<>(m.values()));
        assertEquals(10000, sum(m));
    }

    void testEqualSplitOfFewerCentsThanUsers() {
        Map<String, Long> m = new Splitwise.EqualSplit("a", "b", "c").shares(2);
        assertEquals(List.of(1L, 1L, 0L), new ArrayList<>(m.values()));
    }

    void testEqualSplitZeroAndSingleUser() {
        assertEquals(0, sum(new Splitwise.EqualSplit("a", "b").shares(0)));
        assertEquals(Map.of("solo", 999L), new Splitwise.EqualSplit("solo").shares(999));
    }

    void testEqualSplitAlwaysSumsToTheTotal() {
        for (long total = 0; total < 200; total++)
            for (int n = 1; n <= 7; n++) {
                String[] users = new String[n];
                for (int i = 0; i < n; i++) users[i] = "u" + i;
                assertEquals(total, sum(new Splitwise.EqualSplit(users).shares(total)), "total " + total + " users " + n);
            }
    }

    // ---- exact split -----------------------------------------------------------------------------
    void testExactSplitAcceptsAmountsThatAddUp() {
        Map<String, Long> m = Map.of("a", 700L, "b", 300L);
        assertEquals(m, new Splitwise.ExactSplit(m).shares(1000));
    }

    void testExactSplitRejectsAmountsThatDoNot() {
        Map<String, Long> m = Map.of("a", 400L, "b", 400L);
        assertContains(assertThrows(IllegalArgumentException.class, () -> new Splitwise.ExactSplit(m).shares(1000)).getMessage(), "add up to 800");
        assertThrows(IllegalArgumentException.class, () -> new Splitwise.ExactSplit(Map.of("a", 1100L)).shares(1000));
    }

    // ---- percent split ---------------------------------------------------------------------------
    void testPercentSplitBasic() {
        Map<String, Double> p = new LinkedHashMap<>();
        p.put("a", 40.0); p.put("b", 30.0); p.put("c", 20.0); p.put("d", 10.0);
        assertEquals(List.of(8000L, 6000L, 4000L, 2000L), new ArrayList<>(new Splitwise.PercentSplit(p).shares(20000).values()));
    }

    void testPercentSplitRemainderGoesToTheFirstUser() {
        Map<String, Double> p = new LinkedHashMap<>();
        p.put("a", 100.0 / 3); p.put("b", 100.0 / 3); p.put("c", 100.0 / 3);
        Map<String, Long> m = new Splitwise.PercentSplit(p).shares(10000);
        assertEquals(List.of(3334L, 3333L, 3333L), new ArrayList<>(m.values()));
        assertEquals(10000, sum(m));
    }

    void testPercentSplitIsNotFooledByFloatingPointError() {
        Map<String, Double> p = new LinkedHashMap<>();
        p.put("a", 10.1); p.put("b", 89.9);
        assertEquals(List.of(101L, 899L), new ArrayList<>(new Splitwise.PercentSplit(p).shares(1000).values()));
    }

    void testPercentagesMustAddUpTo100() {
        Map<String, Double> low = new LinkedHashMap<>(), high = new LinkedHashMap<>();
        low.put("a", 60.0); low.put("b", 30.0);
        high.put("a", 60.0); high.put("b", 50.0);
        assertContains(assertThrows(IllegalArgumentException.class, () -> new Splitwise.PercentSplit(low).shares(1000)).getMessage(), "not 100");
        assertThrows(IllegalArgumentException.class, () -> new Splitwise.PercentSplit(high).shares(1000));
    }

    // ---- ledger ----------------------------------------------------------------------------------
    void testEveryoneOwesThePayerTheirShare() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 3000, new Splitwise.EqualSplit("Alice", "Bob", "Cy"));
        assertEquals(List.of("Bob owes Alice $10.00", "Cy owes Alice $10.00"), l.balances());
    }

    void testPayerNeedNotBeInTheSplit() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 1000, new Splitwise.EqualSplit("Bob", "Cy"));
        assertEquals(List.of("Bob owes Alice $5.00", "Cy owes Alice $5.00"), l.balances());
    }

    void testPayerPayingOnlyForThemselvesCreatesNoDebt() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 1000, new Splitwise.EqualSplit("Alice"));
        assertEquals(List.of(), l.balances());
    }

    void testOppositeDebtsAreNetted() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 2000, new Splitwise.EqualSplit("Alice", "Bob"));     // Bob owes Alice 10
        l.addExpense("Bob", 3000, new Splitwise.EqualSplit("Alice", "Bob"));       // Alice owes Bob 15 -> net 5 the other way
        assertEquals(List.of("Alice owes Bob $5.00"), l.balances());
    }

    void testDebtsThatCancelExactlyDisappear() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 2000, new Splitwise.EqualSplit("Alice", "Bob"));
        l.addExpense("Bob", 2000, new Splitwise.EqualSplit("Alice", "Bob"));
        assertEquals(List.of(), l.balances());
    }

    void testDebtsToTheSamePayerAccumulate() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 1000, new Splitwise.EqualSplit("Alice", "Bob"));
        l.addExpense("Alice", 3000, new Splitwise.EqualSplit("Alice", "Bob"));
        assertEquals(List.of("Bob owes Alice $20.00"), l.balances());
    }

    void testZeroShareCreatesNoDebt() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 1000, new Splitwise.ExactSplit(Map.of("Bob", 1000L, "Cy", 0L)));
        assertEquals(List.of("Bob owes Alice $10.00"), l.balances());
    }

    void testRejectedExpenseLeavesTheLedgerUntouched() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 1000, new Splitwise.EqualSplit("Alice", "Bob"));
        List<String> before = l.balances();
        assertThrows(IllegalArgumentException.class, () -> l.addExpense("Alice", 1000, new Splitwise.ExactSplit(Map.of("Bob", 400L))));
        assertEquals(before, l.balances());
    }

    // ---- simplify --------------------------------------------------------------------------------
    void testSimplifyOnAnEmptyLedger() {
        assertEquals(List.of(), new Splitwise.Ledger().simplify());
    }

    void testSimplifyCollapsesAChainIntoOnePayment() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("B", 1000, new Splitwise.ExactSplit(Map.of("A", 1000L)));     // A owes B 10
        l.addExpense("C", 1000, new Splitwise.ExactSplit(Map.of("B", 1000L)));     // B owes C 10
        assertEquals(List.of("A pays C $10.00"), l.simplify());
    }

    void testSimplifyCancelsACircularDebt() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("B", 1000, new Splitwise.ExactSplit(Map.of("A", 1000L)));
        l.addExpense("C", 1000, new Splitwise.ExactSplit(Map.of("B", 1000L)));
        l.addExpense("A", 1000, new Splitwise.ExactSplit(Map.of("C", 1000L)));
        assertEquals(List.of(), l.simplify());
    }

    void testSimplifyDoesNotChangeTheLedger() {
        Splitwise.Ledger l = new Splitwise.Ledger();
        l.addExpense("Alice", 10000, new Splitwise.EqualSplit("Alice", "Bob", "Cy", "Dan"));
        List<String> before = l.balances();
        l.simplify();
        assertEquals(before, l.balances());
    }

    void testSimplifyUsesAtMostNMinusOnePaymentsAndSettlesEveryone() {
        Random rnd = new Random(5);
        String[] users = {"u0", "u1", "u2", "u3", "u4", "u5"};
        for (int round = 0; round < 50; round++) {
            Splitwise.Ledger l = new Splitwise.Ledger();
            Map<String, Long> net = new HashMap<>();                                 // independent bookkeeping of who is owed what
            for (int i = 0; i < 12; i++) {
                String payer = users[rnd.nextInt(users.length)];
                long total = 100 + rnd.nextInt(20000);
                List<String> group = new ArrayList<>(Arrays.asList(users));
                Collections.shuffle(group, rnd);
                Splitwise.EqualSplit split = new Splitwise.EqualSplit(group.subList(0, 2 + rnd.nextInt(4)).toArray(new String[0]));
                for (var e : split.shares(total).entrySet())
                    if (!e.getKey().equals(payer)) { net.merge(payer, e.getValue(), Long::sum); net.merge(e.getKey(), -e.getValue(), Long::sum); }
                l.addExpense(payer, total, split);
            }
            List<String> payments = l.simplify();
            assertTrue(payments.size() <= users.length - 1, "too many payments: " + payments.size());
            Pattern p = Pattern.compile("(\\w+) pays (\\w+) \\$(\\d+)\\.(\\d\\d)");
            for (String s : payments) {
                Matcher m = p.matcher(s);
                assertTrue(m.matches(), s);
                long cents = Long.parseLong(m.group(3)) * 100 + Long.parseLong(m.group(4));
                assertTrue(cents > 0);
                net.merge(m.group(1), cents, Long::sum);                             // payer of the settlement gets their debt reduced
                net.merge(m.group(2), -cents, Long::sum);
            }
            for (var e : net.entrySet()) assertEquals(0L, (long) e.getValue(), "round " + round + " " + e.getKey() + " not settled");
        }
    }

    public static void main(String[] args) throws Exception { System.exit(run(SplitwiseTest.class) == 0 ? 0 : 1); }
}
