public class VendingMachineTest extends MiniTest {
    static final VendingMachine.Coin NICKEL = VendingMachine.Coin.NICKEL, DIME = VendingMachine.Coin.DIME,
        QUARTER = VendingMachine.Coin.QUARTER, DOLLAR = VendingMachine.Coin.DOLLAR;

    /** Chips 75c x2, Soda 125c x1, and a float of 10 nickels, dimes and quarters. */
    static VendingMachine machine() {
        VendingMachine m = new VendingMachine();
        m.stock("A1", new VendingMachine.Product("Chips", 75), 2);
        m.stock("B1", new VendingMachine.Product("Soda", 125), 1);
        m.loadCoins(NICKEL, 10); m.loadCoins(DIME, 10); m.loadCoins(QUARTER, 10);
        return m;
    }
    static VendingMachine machineWithoutFloat() {
        VendingMachine m = new VendingMachine();
        m.stock("A1", new VendingMachine.Product("Chips", 75), 2);
        return m;
    }

    void testCoinValues() {
        assertEquals(5, NICKEL.cents); assertEquals(10, DIME.cents); assertEquals(25, QUARTER.cents); assertEquals(100, DOLLAR.cents);
    }

    // ---- basic flow ------------------------------------------------------------------------------
    void testSelectingBeforePayingIsRefused() {
        VendingMachine m = machine();
        assertContains(assertThrows(IllegalStateException.class, () -> m.select("A1")).getMessage(), "Insert money first");
    }

    void testExactPaymentDispensesWithoutChange() throws Exception {
        VendingMachine m = machine();
        String out = capture(() -> { m.insertCoin(QUARTER); m.insertCoin(QUARTER); m.insertCoin(QUARTER); m.select("A1"); });
        assertContains(out, "Dispensed Chips");
        assertNotContains(out, "Returned");
    }

    void testOverpaymentReturnsChange() throws Exception {
        VendingMachine m = machine();
        String out = capture(() -> { m.insertCoin(DOLLAR); m.select("A1"); });
        assertContains(out, "Dispensed Chips");
        assertContains(out, "Returned 25c as {QUARTER=1}");
    }

    void testMachineIsIdleAgainAfterASale() throws Exception {
        VendingMachine m = machine();
        capture(() -> { m.insertCoin(DOLLAR); m.select("A1"); });
        assertThrows(IllegalStateException.class, () -> m.select("A1"));
    }

    void testInsufficientFundsKeepsTheMoneyAndAllowsTopUp() throws Exception {
        VendingMachine m = machine();
        m.insertCoin(QUARTER);
        assertContains(assertThrows(IllegalStateException.class, () -> m.select("A1")).getMessage(), "Need 50 more cents");
        String out = capture(() -> { m.insertCoin(QUARTER); m.insertCoin(QUARTER); m.select("A1"); });
        assertContains(out, "Dispensed Chips");
    }

    void testMultipleCoinsAccumulate() throws Exception {
        VendingMachine m = machine();
        String out = capture(() -> { m.insertCoin(DIME); m.insertCoin(DIME); m.insertCoin(QUARTER); m.insertCoin(QUARTER); m.insertCoin(NICKEL); m.select("A1"); });
        assertContains(out, "Dispensed Chips");           // 10+10+25+25+5 = 75
        assertNotContains(out, "Returned");
    }

    // ---- cancel ----------------------------------------------------------------------------------
    void testCancelRefundsEverythingInserted() throws Exception {
        VendingMachine m = machine();
        String out = capture(() -> { m.insertCoin(DIME); m.insertCoin(DIME); m.cancel(); });
        assertContains(out, "Returned 20c as {DIME=2}");
        assertThrows(IllegalStateException.class, () -> m.select("A1"));       // back to Idle
    }

    void testCancelWithNothingInsertedDoesNothing() throws Exception {
        VendingMachine m = machine();
        assertEquals("", capture(m::cancel));
    }

    // ---- stock and bad input ---------------------------------------------------------------------
    void testSoldOutProductIsRefusedAndMoneyIsKept() throws Exception {
        VendingMachine m = machine();
        capture(() -> { m.insertCoin(DOLLAR); m.insertCoin(QUARTER); m.select("B1"); });          // buys the only soda
        m.insertCoin(DOLLAR); m.insertCoin(QUARTER);
        assertContains(assertThrows(IllegalStateException.class, () -> m.select("B1")).getMessage(), "sold out");
        assertContains(capture(m::cancel), "Returned 125c");
    }

    void testStockRunsOutAfterExactlyTheStockedQuantity() throws Exception {
        VendingMachine m = machine();
        capture(() -> { m.insertCoin(DOLLAR); m.select("A1"); });
        capture(() -> { m.insertCoin(DOLLAR); m.select("A1"); });
        m.insertCoin(DOLLAR);
        assertContains(assertThrows(IllegalStateException.class, () -> m.select("A1")).getMessage(), "sold out");
    }

    void testUnknownSlotIsRejectedWithoutLosingTheMoney() throws Exception {
        VendingMachine m = machine();
        m.insertCoin(DOLLAR);
        assertThrows(IllegalArgumentException.class, () -> m.select("Z9"));
        assertContains(capture(m::cancel), "Returned 100c");
    }

    // ---- faults ----------------------------------------------------------------------------------
    void testJamRefundsAndTakesTheMachineOutOfService() throws Exception {
        VendingMachine m = machine();
        String out = capture(() -> { m.insertCoin(DOLLAR); m.insertCoin(QUARTER); m.simulateJam(); m.select("B1"); });
        assertContains(out, "Returned 125c");
        assertNotContains(out, "Dispensed");
        assertContains(assertThrows(IllegalStateException.class, () -> m.insertCoin(DIME)).getMessage(), "Out of service");
        assertThrows(IllegalStateException.class, () -> m.select("A1"));
        assertEquals("", capture(m::cancel));
    }

    // ---- change-making ---------------------------------------------------------------------------
    void testInsertedCoinsCanBeUsedAsChange() throws Exception {
        VendingMachine m = machineWithoutFloat();
        String out = capture(() -> { for (int i = 0; i < 4; i++) m.insertCoin(QUARTER); m.select("A1"); });
        assertContains(out, "Returned 25c as {QUARTER=1}");
    }

    void testSaleIsRefusedWhenChangeCannotBeMade() throws Exception {
        VendingMachine m = machineWithoutFloat();                 // no coins to give change from
        m.insertCoin(DOLLAR);
        assertContains(assertThrows(IllegalStateException.class, () -> m.select("A1")).getMessage(), "Exact change");
        assertContains(capture(m::cancel), "Returned 100c as {DOLLAR=1}");      // customer is not short-changed
        String out = capture(() -> { m.insertCoin(QUARTER); m.insertCoin(QUARTER); m.insertCoin(QUARTER); m.select("A1"); });
        assertContains(out, "Dispensed Chips");                  // exact change still works
    }

    void testChangeIsFoundEvenWhenTheGreedyChoiceWouldFail() throws Exception {
        VendingMachine m = new VendingMachine();
        m.stock("G1", new VendingMachine.Product("Gum", 70), 1);
        m.loadCoins(QUARTER, 1); m.loadCoins(DIME, 3);            // 30c change: greedy takes the quarter and is stuck needing a nickel
        String out = capture(() -> { m.insertCoin(DOLLAR); m.select("G1"); });
        assertContains(out, "Dispensed Gum");
        assertContains(out, "Returned 30c as {DIME=3}");
    }

    public static void main(String[] args) throws Exception { System.exit(run(VendingMachineTest.class) == 0 ? 0 : 1); }
}
