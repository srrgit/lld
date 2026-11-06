import java.time.LocalDate;
import java.util.*;

public class LibraryManagementTest extends MiniTest {
    static final LocalDate D0 = LocalDate.of(2026, 11, 1);
    static final LibraryManagement.Book CLEAN = new LibraryManagement.Book("111", "Clean Code", "Robert Martin");
    static final LibraryManagement.Book JAVA = new LibraryManagement.Book("222", "Effective Java", "Joshua Bloch");

    static class Rig {
        final List<String> notices = new ArrayList<>();
        final LibraryManagement lib = new LibraryManagement(new LibraryManagement.PerDayFine(0.50),
            (m, b) -> notices.add(m.name + ":" + b.title()), 2, 14);
        final LibraryManagement.Member alice = new LibraryManagement.Member("m1", "Alice"),
            bob = new LibraryManagement.Member("m2", "Bob"), carl = new LibraryManagement.Member("m3", "Carl");
        Rig() { lib.addBook(CLEAN, 1); lib.addBook(JAVA, 2); }
    }

    // ---- catalogue -------------------------------------------------------------------------------
    void testSearchMatchesTitleAndAuthorIgnoringCase() {
        Rig r = new Rig();
        assertEquals(List.of(CLEAN), r.lib.search("CLEAN"));
        assertEquals(List.of(CLEAN), r.lib.search("martin"));
        assertEquals(List.of(JAVA), r.lib.search("bloch"));
    }

    void testSearchWithNoMatchOrAnEmptyQuery() {
        Rig r = new Rig();
        assertEquals(List.of(), r.lib.search("python"));
        assertEquals(2, r.lib.search("").size(), "an empty query matches everything");
    }

    void testAddingMoreCopiesOfAnExistingTitleExtendsTheBarcodes() {
        Rig r = new Rig();
        r.lib.addBook(JAVA, 1);                                  // now 3 copies of ISBN 222
        for (LibraryManagement.Member m : List.of(r.alice, r.bob)) r.lib.checkout(m, "222", D0);
        LibraryManagement.Member dan = new LibraryManagement.Member("m4", "Dan");
        assertEquals("222-3", r.lib.checkout(dan, "222", D0).item.barcode);
    }

    // ---- checkout --------------------------------------------------------------------------------
    void testCheckoutSetsTheDueDate() {
        Rig r = new Rig();
        LibraryManagement.Loan loan = r.lib.checkout(r.alice, "111", D0);
        assertEquals(D0.plusDays(14), loan.due);
        assertEquals("111-1", loan.item.barcode);
        assertEquals(LibraryManagement.ItemStatus.LOANED, loan.item.status);
        assertTrue(r.alice.loanedBarcodes.contains("111-1"));
    }

    void testUnknownIsbnIsRejected() {
        Rig r = new Rig();
        assertContains(assertThrows(IllegalArgumentException.class, () -> r.lib.checkout(r.alice, "999", D0)).getMessage(), "Unknown ISBN");
    }

    void testNoCopyAvailable() {
        Rig r = new Rig();
        r.lib.checkout(r.alice, "111", D0);
        assertContains(assertThrows(IllegalStateException.class, () -> r.lib.checkout(r.bob, "111", D0)).getMessage(), "No copy available");
    }

    void testTwoCopiesCanGoToTwoMembersButNotThree() {
        Rig r = new Rig();
        r.lib.checkout(r.alice, "222", D0);
        r.lib.checkout(r.bob, "222", D0);
        assertThrows(IllegalStateException.class, () -> r.lib.checkout(r.carl, "222", D0));
    }

    void testLoanLimitIsEnforcedAndFreedByReturning() {
        Rig r = new Rig();
        LibraryManagement.Loan first = r.lib.checkout(r.alice, "111", D0);
        r.lib.checkout(r.alice, "222", D0);
        assertContains(assertThrows(IllegalStateException.class, () -> r.lib.checkout(r.alice, "222", D0)).getMessage(), "loan limit of 2");
        r.lib.returnItem(first.item.barcode, D0.plusDays(1));
        r.lib.checkout(r.alice, "222", D0.plusDays(1));          // allowed again
    }

    // ---- returns and fines -----------------------------------------------------------------------
    void testNoFineOnTheDueDateItself() {
        Rig r = new Rig();
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        assertEquals(0.0, r.lib.returnItem(l.item.barcode, D0.plusDays(14)), 1e-9);
    }

    void testNoFineForAnEarlyReturn() {
        Rig r = new Rig();
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        assertEquals(0.0, r.lib.returnItem(l.item.barcode, D0), 1e-9);
    }

    void testFineGrowsPerDayLate() {
        Rig r = new Rig();
        LibraryManagement.Loan a = r.lib.checkout(r.alice, "111", D0), b = r.lib.checkout(r.bob, "222", D0);
        assertEquals(0.5, r.lib.returnItem(a.item.barcode, D0.plusDays(15)), 1e-9);
        assertEquals(3.0, r.lib.returnItem(b.item.barcode, D0.plusDays(20)), 1e-9);
    }

    void testPerDayFineStrategyDirectly() {
        LibraryManagement.PerDayFine f = new LibraryManagement.PerDayFine(2.0);
        assertEquals(0.0, f.fine(-5), 1e-9);
        assertEquals(0.0, f.fine(0), 1e-9);
        assertEquals(2.0, f.fine(1), 1e-9);
        assertEquals(20.0, f.fine(10), 1e-9);
    }

    void testReturningAnItemThatIsNotOnLoanFails() {
        Rig r = new Rig();
        assertThrows(IllegalArgumentException.class, () -> r.lib.returnItem("111-1", D0));
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        r.lib.returnItem(l.item.barcode, D0);
        assertThrows(IllegalArgumentException.class, () -> r.lib.returnItem(l.item.barcode, D0), "double return");
    }

    void testReturnedCopyBecomesAvailableAgain() {
        Rig r = new Rig();
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        r.lib.returnItem(l.item.barcode, D0);
        assertEquals(LibraryManagement.ItemStatus.AVAILABLE, l.item.status);
        assertEquals(0, r.alice.loanedBarcodes.size());
        r.lib.checkout(r.bob, "111", D0);
        assertTrue(r.notices.isEmpty(), "nobody was waiting, so nobody is notified");
    }

    // ---- reservations ----------------------------------------------------------------------------
    void testReturnHoldsTheCopyForTheFirstReservationAndNotifiesThem() {
        Rig r = new Rig();
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        r.lib.reserve(r.bob, "111");
        r.lib.returnItem(l.item.barcode, D0.plusDays(1));
        assertEquals(List.of("Bob:Clean Code"), r.notices);
        assertEquals(LibraryManagement.ItemStatus.RESERVED, l.item.status);
    }

    void testHeldCopyCannotBeTakenByAnyoneElseIncludingTheLastBorrower() {
        Rig r = new Rig();
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        r.lib.reserve(r.bob, "111");
        r.lib.returnItem(l.item.barcode, D0);
        assertThrows(IllegalStateException.class, () -> r.lib.checkout(r.alice, "111", D0));
        assertThrows(IllegalStateException.class, () -> r.lib.checkout(r.carl, "111", D0));
        LibraryManagement.Loan b = r.lib.checkout(r.bob, "111", D0.plusDays(1));
        assertEquals(LibraryManagement.ItemStatus.LOANED, b.item.status);
    }

    void testReservationIsConsumedByTheCheckout() {
        Rig r = new Rig();
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        r.lib.reserve(r.bob, "111");
        r.lib.returnItem(l.item.barcode, D0);
        LibraryManagement.Loan b = r.lib.checkout(r.bob, "111", D0);
        r.lib.returnItem(b.item.barcode, D0);
        assertEquals(1, r.notices.size(), "Bob's second return must not notify him again");
        assertEquals(LibraryManagement.ItemStatus.AVAILABLE, b.item.status);
    }

    void testReservationsAreServedInOrder() {
        Rig r = new Rig();
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        r.lib.reserve(r.bob, "111"); r.lib.reserve(r.carl, "111");
        r.lib.returnItem(l.item.barcode, D0);
        assertEquals(List.of("Bob:Clean Code"), r.notices);
        assertThrows(IllegalStateException.class, () -> r.lib.checkout(r.carl, "111", D0), "Carl is second in line");
        LibraryManagement.Loan b = r.lib.checkout(r.bob, "111", D0);
        r.lib.returnItem(b.item.barcode, D0);
        assertEquals(List.of("Bob:Clean Code", "Carl:Clean Code"), r.notices);
    }

    void testSameMemberCannotReserveTheSameTitleTwice() {
        Rig r = new Rig();
        r.lib.reserve(r.bob, "111");
        assertContains(assertThrows(IllegalStateException.class, () -> r.lib.reserve(r.bob, "111")).getMessage(), "already reserved");
    }

    void testWithTwoCopiesTheFirstTwoWaitersEachGetOne() {
        Rig r = new Rig();
        LibraryManagement.Loan a = r.lib.checkout(r.alice, "222", D0);
        LibraryManagement.Member dan = new LibraryManagement.Member("m4", "Dan");
        LibraryManagement.Loan d = r.lib.checkout(dan, "222", D0);
        r.lib.reserve(r.bob, "222"); r.lib.reserve(r.carl, "222");
        r.lib.returnItem(a.item.barcode, D0);
        r.lib.returnItem(d.item.barcode, D0);
        assertEquals(List.of("Bob:Effective Java", "Carl:Effective Java"), r.notices);
        r.lib.checkout(r.carl, "222", D0);                       // Carl may take either held copy
        r.lib.checkout(r.bob, "222", D0);
    }

    void testNonReservingMemberCannotTakeAHeldCopyEvenWhenOneOfTwoIsFree() {
        Rig r = new Rig();
        LibraryManagement.Loan a = r.lib.checkout(r.alice, "222", D0);
        r.lib.reserve(r.bob, "222");
        r.lib.returnItem(a.item.barcode, D0);                    // copy 1 held for Bob, copy 2 still free
        r.lib.checkout(r.carl, "222", D0);                       // takes the free copy 2
        assertThrows(IllegalStateException.class, () -> r.lib.checkout(new LibraryManagement.Member("m9", "Zed"), "222", D0));
        r.lib.checkout(r.bob, "222", D0);
    }

    void testReservedMemberStillSubjectToTheLoanLimit() {
        Rig r = new Rig();
        LibraryManagement.Loan l = r.lib.checkout(r.alice, "111", D0);
        r.lib.checkout(r.bob, "222", D0); r.lib.checkout(r.bob, "222", D0);
        r.lib.reserve(r.bob, "111");
        r.lib.returnItem(l.item.barcode, D0);
        assertContains(assertThrows(IllegalStateException.class, () -> r.lib.checkout(r.bob, "111", D0)).getMessage(), "loan limit");
    }

    public static void main(String[] args) throws Exception { System.exit(run(LibraryManagementTest.class) == 0 ? 0 : 1); }
}
