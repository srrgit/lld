import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ATMTest extends MiniTest {
    static final String CARD = "CARD-1", PIN = "1234";

    static class Rig {
        final ATM.InMemoryBank bank = new ATM.InMemoryBank();
        final ATM.NoteHandler h2000, h500, h100;
        final ATM.CashDispenser dispenser;
        final ATM atm;
        Rig(int balance, int n2000, int n500, int n100) {
            bank.open(CARD, PIN, balance);
            h2000 = new ATM.NoteHandler(2000, n2000); h500 = new ATM.NoteHandler(500, n500); h100 = new ATM.NoteHandler(100, n100);
            h2000.next = h500; h500.next = h100;
            dispenser = new ATM.CashDispenser(h2000);
            atm = new ATM(bank, dispenser);
        }
        static Rig standard() { return new Rig(5000, 2, 5, 10); }
        void login() { atm.insertCard(CARD); atm.enterPin(PIN); }
    }

    // ---- session flow ----------------------------------------------------------------------------
    void testNothingWorksWithoutACard() {
        Rig r = Rig.standard();
        assertThrows(IllegalStateException.class, () -> r.atm.withdraw(500));
        assertThrows(IllegalStateException.class, () -> r.atm.enterPin(PIN));
        r.atm.eject();                                         // harmless when idle
    }

    void testWithdrawalDebitsTheAccountAndPaysLargeNotesFirst() throws Exception {
        Rig r = Rig.standard();
        String out = capture(() -> { r.login(); r.atm.withdraw(2600); });
        assertContains(out, "Dispensed 2600 as {2000=1, 500=1, 100=1}");
        assertEquals(2400, r.bank.balance(CARD));
    }

    void testWithdrawingTheWholeBalance() throws Exception {
        Rig r = new Rig(500, 0, 1, 0);
        capture(() -> { r.login(); r.atm.withdraw(500); });
        assertEquals(0, r.bank.balance(CARD));
        assertThrows(IllegalStateException.class, () -> r.atm.withdraw(100));
    }

    void testCannotInsertASecondCardOrReenterThePin() throws Exception {
        Rig r = Rig.standard();
        r.atm.insertCard(CARD);
        assertContains(assertThrows(IllegalStateException.class, () -> r.atm.insertCard("OTHER")).getMessage(), "already inserted");
        r.atm.enterPin(PIN);
        assertThrows(IllegalStateException.class, () -> r.atm.insertCard("OTHER"));
        assertContains(assertThrows(IllegalStateException.class, () -> r.atm.enterPin(PIN)).getMessage(), "Already authenticated");
    }

    void testWithdrawBeforeEnteringThePinIsRefused() throws Exception {
        Rig r = Rig.standard();
        r.atm.insertCard(CARD);
        assertContains(assertThrows(IllegalStateException.class, () -> r.atm.withdraw(100)).getMessage(), "Enter PIN first");
    }

    void testEjectEndsTheSession() throws Exception {
        Rig r = Rig.standard();
        r.login();
        r.atm.eject();
        assertThrows(IllegalStateException.class, () -> r.atm.withdraw(100));
        r.atm.insertCard(CARD);                                 // a new session can start
    }

    // ---- PIN handling ----------------------------------------------------------------------------
    void testWrongPinReportsRemainingTries() throws Exception {
        Rig r = Rig.standard();
        r.atm.insertCard(CARD);
        assertContains(assertThrows(IllegalArgumentException.class, () -> r.atm.enterPin("0000")).getMessage(), "2 tries left");
        assertContains(assertThrows(IllegalArgumentException.class, () -> r.atm.enterPin("0000")).getMessage(), "1 tries left");
    }

    void testCorrectPinAfterTwoMistakesStillWorks() throws Exception {
        Rig r = Rig.standard();
        r.atm.insertCard(CARD);
        for (int i = 0; i < 2; i++) assertThrows(IllegalArgumentException.class, () -> r.atm.enterPin("0000"));
        r.atm.enterPin(PIN);
        assertContains(capture(() -> r.atm.withdraw(100)), "Dispensed 100");
    }

    void testCardIsRetainedAfterThreeBadPins() throws Exception {
        Rig r = Rig.standard();
        r.atm.insertCard(CARD);
        for (int i = 0; i < 2; i++) assertThrows(IllegalArgumentException.class, () -> r.atm.enterPin("0000"));
        assertContains(capture(() -> r.atm.enterPin("0000")), "Card retained");
        assertContains(assertThrows(IllegalStateException.class, () -> r.atm.enterPin(PIN)).getMessage(), "Insert card first");
    }

    void testPinAttemptsResetForANewSession() throws Exception {
        Rig r = Rig.standard();
        r.atm.insertCard(CARD);
        for (int i = 0; i < 2; i++) assertThrows(IllegalArgumentException.class, () -> r.atm.enterPin("0000"));
        r.atm.eject();
        r.atm.insertCard(CARD);
        assertContains(assertThrows(IllegalArgumentException.class, () -> r.atm.enterPin("0000")).getMessage(), "2 tries left");
    }

    // ---- amount validation -----------------------------------------------------------------------
    void testAmountMustBeAPositiveMultipleOf100() throws Exception {
        Rig r = Rig.standard();
        r.login();
        assertThrows(IllegalArgumentException.class, () -> r.atm.withdraw(150));
        assertThrows(IllegalArgumentException.class, () -> r.atm.withdraw(0));
        assertThrows(IllegalArgumentException.class, () -> r.atm.withdraw(-100));
        assertEquals(5000, r.bank.balance(CARD));
    }

    // ---- failures keep money and cash consistent ---------------------------------------------------
    void testInsufficientFundsChangesNothingAndReturnsTheNotes() throws Exception {
        Rig r = new Rig(300, 0, 0, 5);
        r.login();
        assertContains(assertThrows(IllegalStateException.class, () -> r.atm.withdraw(400)).getMessage(), "Insufficient funds");
        assertEquals(300, r.bank.balance(CARD));
        r.bank.credit(CARD, 700);                               // balance 1000, and the machine must still hold all 5 notes
        assertContains(capture(() -> r.atm.withdraw(500)), "Dispensed 500 as {100=5}");
        assertEquals(500, r.bank.balance(CARD));
    }

    void testMachineThatCannotPayOutLeavesTheAccountUntouched() throws Exception {
        Rig r = new Rig(5000, 0, 1, 0);                          // only one 500 note inside
        r.login();
        assertContains(assertThrows(IllegalStateException.class, () -> r.atm.withdraw(300)).getMessage(), "cannot dispense");
        assertEquals(5000, r.bank.balance(CARD));
    }

    void testCashRunsOutAcrossWithdrawals() throws Exception {
        Rig r = new Rig(1000, 0, 0, 3);
        r.login();
        capture(() -> r.atm.withdraw(200));
        assertThrows(IllegalStateException.class, () -> r.atm.withdraw(200));
        assertEquals(800, r.bank.balance(CARD));
    }

    // ---- the cash dispenser on its own -----------------------------------------------------------
    void testDispenserUsesTheLargestNotesFirst() {
        Rig r = Rig.standard();
        assertEquals(Map.of(2000, 1, 500, 1, 100, 1), r.dispenser.dispense(2600).orElseThrow());
    }

    void testDispenserFallsBackToSmallerNotesWhenLargeOnesAreGone() {
        Rig r = new Rig(0, 0, 5, 10);
        assertEquals(Map.of(500, 5, 100, 1), r.dispenser.dispense(2600).orElseThrow());
    }

    void testDispenserCommitsNoteCounts() {
        Rig r = Rig.standard();
        r.dispenser.dispense(2600);
        assertEquals(1, r.h2000.count); assertEquals(4, r.h500.count); assertEquals(9, r.h100.count);
    }

    void testDispenserLeavesCountsAloneWhenTheAmountIsImpossible() {
        Rig r = Rig.standard();
        assertTrue(r.dispenser.dispense(150).isEmpty());
        assertTrue(r.dispenser.dispense(100_000).isEmpty());
        assertEquals(2, r.h2000.count); assertEquals(5, r.h500.count); assertEquals(10, r.h100.count);
    }

    void testDispenserRestoreReturnsNotes() {
        Rig r = Rig.standard();
        Map<Integer, Integer> plan = r.dispenser.dispense(2600).orElseThrow();
        r.dispenser.restore(plan);
        assertEquals(2, r.h2000.count); assertEquals(5, r.h500.count); assertEquals(10, r.h100.count);
    }

    void testDispenserCanEmptyItselfExactly() {
        Rig r = new Rig(0, 1, 1, 1);
        assertEquals(Map.of(2000, 1, 500, 1, 100, 1), r.dispenser.dispense(2600).orElseThrow());
        assertTrue(r.dispenser.dispense(100).isEmpty());
    }

    // ---- bank ------------------------------------------------------------------------------------
    void testBankDebitIsAtomicUnderConcurrency() throws Exception {
        ATM.InMemoryBank bank = new ATM.InMemoryBank();
        bank.open(CARD, PIN, 1000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger ok = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) futures.add(pool.submit(() -> { if (bank.debit(CARD, 100)) ok.incrementAndGet(); }));
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        assertEquals(10, ok.get());
        assertEquals(0, bank.balance(CARD));
    }

    void testBankVerifiesPins() {
        ATM.InMemoryBank bank = new ATM.InMemoryBank();
        bank.open(CARD, PIN, 10);
        assertTrue(bank.verifyPin(CARD, PIN));
        assertFalse(bank.verifyPin(CARD, "9999"));
        assertFalse(bank.verifyPin("UNKNOWN", PIN));
    }

    public static void main(String[] args) throws Exception { System.exit(run(ATMTest.class) == 0 ? 0 : 1); }
}
