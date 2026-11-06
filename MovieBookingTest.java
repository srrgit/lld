import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MovieBookingTest extends MiniTest {
    static final Instant T0 = Instant.parse("2026-11-06T18:00:00Z");
    static final Duration TTL = Duration.ofMinutes(5);

    static Instant plusMin(long m) { return T0.plus(Duration.ofMinutes(m)); }

    /** Records what was charged and refunded; can decline named users and run a hook in the middle of a charge. */
    static class FakePay implements MovieBooking.PaymentGateway {
        final List<String> charges = new ArrayList<>(), refunds = new ArrayList<>();
        final Set<String> declined = new HashSet<>();
        Runnable duringCharge = () -> { };
        public boolean charge(String user, int cents) {
            duringCharge.run();
            if (declined.contains(user)) return false;
            charges.add(user + ":" + cents);
            return true;
        }
        public void refund(String user, int cents) { refunds.add(user + ":" + cents); }
    }

    static class Rig {
        final FakePay pay = new FakePay();
        final MovieBooking.BookingService svc = new MovieBooking.BookingService(TTL, pay);
        Rig() {
            svc.addShow(new MovieBooking.Show("S1", new MovieBooking.Movie("M1", "Dune 3"), 2, 3, 1200));   // A1..A3, B1..B3
            svc.addShow(new MovieBooking.Show("S2", new MovieBooking.Movie("M2", "Heat 2"), 1, 2, 2000));
        }
        List<String> free(Instant t) { return svc.availableSeats("S1", t); }
    }

    // ---- shows and seat map ----------------------------------------------------------------------
    void testShowBuildsARowAndColumnSeatMap() {
        assertEquals(List.of("A1", "A2", "A3", "B1", "B2", "B3"), new Rig().free(T0));
    }

    void testUnknownShowIsRejected() {
        Rig r = new Rig();
        assertContains(assertThrows(IllegalArgumentException.class, () -> r.svc.availableSeats("NOPE", T0)).getMessage(), "No show");
        assertThrows(IllegalArgumentException.class, () -> r.svc.lockSeats("NOPE", "u", List.of("A1"), T0));
    }

    void testShowsAreIndependent() {
        Rig r = new Rig();
        r.svc.lockSeats("S1", "ann", List.of("A1"), T0);
        r.svc.lockSeats("S2", "ben", List.of("A1"), T0);          // same seat id, different show
        assertEquals(1, r.svc.availableSeats("S2", T0).size());
    }

    // ---- locking ---------------------------------------------------------------------------------
    void testLockHidesSeatsFromEveryoneElse() {
        Rig r = new Rig();
        MovieBooking.SeatLock l = r.svc.lockSeats("S1", "ann", List.of("A1", "A2"), T0);
        assertEquals(List.of("A3", "B1", "B2", "B3"), r.free(T0));
        assertEquals(T0.plus(TTL), l.expiry());
        assertEquals(List.of("A1", "A2"), l.seatIds());
        assertContains(assertThrows(IllegalStateException.class, () -> r.svc.lockSeats("S1", "bob", List.of("A2"), T0)).getMessage(), "A2");
    }

    void testLockIsAllOrNothing() {
        Rig r = new Rig();
        r.svc.lockSeats("S1", "ann", List.of("A2"), T0);
        assertThrows(IllegalStateException.class, () -> r.svc.lockSeats("S1", "bob", List.of("A1", "A2", "A3"), T0));
        assertEquals(List.of("A1", "A3", "B1", "B2", "B3"), r.free(T0), "Bob's failed request must not leave A1 or A3 locked");
    }

    void testUnknownSeatRejectsTheWholeRequest() {
        Rig r = new Rig();
        assertContains(assertThrows(IllegalArgumentException.class, () -> r.svc.lockSeats("S1", "ann", List.of("A1", "Z9"), T0)).getMessage(), "Z9");
        assertEquals(6, r.free(T0).size());
    }

    void testEmptyOrDuplicateSeatRequestsAreRejected() {
        Rig r = new Rig();
        assertThrows(IllegalArgumentException.class, () -> r.svc.lockSeats("S1", "ann", List.of(), T0));
        assertContains(assertThrows(IllegalArgumentException.class, () -> r.svc.lockSeats("S1", "ann", List.of("A1", "A1"), T0)).getMessage(), "Duplicate");
        assertEquals(6, r.free(T0).size());
    }

    void testLockExpiresExactlyAtTheDeadline() {
        Rig r = new Rig();
        r.svc.lockSeats("S1", "ann", List.of("A1"), T0);
        assertFalse(r.free(plusMin(4)).contains("A1"));
        assertTrue(r.free(plusMin(5)).contains("A1"));
    }

    void testExpiredSeatCanBeLockedByAnotherUser() {
        Rig r = new Rig();
        r.svc.lockSeats("S1", "ann", List.of("A1"), T0);
        MovieBooking.SeatLock bob = r.svc.lockSeats("S1", "bob", List.of("A1"), plusMin(6));
        assertEquals("bob", bob.userId());
    }

    // ---- confirm ---------------------------------------------------------------------------------
    void testConfirmChargesTheTotalAndBooksTheSeats() {
        Rig r = new Rig();
        MovieBooking.SeatLock l = r.svc.lockSeats("S1", "ann", List.of("A1", "A2"), T0);
        MovieBooking.Booking b = r.svc.confirm(l.lockId(), plusMin(1));
        assertEquals(2400, b.totalCents());
        assertEquals(List.of("A1", "A2"), b.seatIds());
        assertEquals(List.of("ann:2400"), r.pay.charges);
        assertEquals(List.of("A3", "B1", "B2", "B3"), r.free(plusMin(30)), "booked seats never come back by themselves");
    }

    void testPricePerShowIsRespected() {
        Rig r = new Rig();
        assertEquals(4000, r.svc.confirm(r.svc.lockSeats("S2", "ann", List.of("A1", "A2"), T0).lockId(), T0).totalCents());
    }

    void testConfirmingTwiceFails() {
        Rig r = new Rig();
        MovieBooking.SeatLock l = r.svc.lockSeats("S1", "ann", List.of("A1"), T0);
        r.svc.confirm(l.lockId(), T0);
        assertThrows(IllegalArgumentException.class, () -> r.svc.confirm(l.lockId(), T0));
        assertEquals(1, r.pay.charges.size(), "the customer must not be charged twice");
    }

    void testUnknownLockIsRejected() {
        assertContains(assertThrows(IllegalArgumentException.class, () -> new Rig().svc.confirm("L999", T0)).getMessage(), "Unknown lock");
    }

    void testConfirmAtOrAfterExpiryFailsAndFreesTheSeats() {
        Rig r = new Rig();
        MovieBooking.SeatLock l = r.svc.lockSeats("S1", "ann", List.of("A1"), T0);
        assertContains(assertThrows(IllegalStateException.class, () -> r.svc.confirm(l.lockId(), plusMin(5))).getMessage(), "expired");
        assertTrue(r.pay.charges.isEmpty());
        assertTrue(r.free(plusMin(5)).contains("A1"));
    }

    void testExpiredLockHolderCannotStealSeatsFromTheNewOwner() {
        Rig r = new Rig();
        MovieBooking.SeatLock ann = r.svc.lockSeats("S1", "ann", List.of("A1"), T0);
        MovieBooking.SeatLock bob = r.svc.lockSeats("S1", "bob", List.of("A1"), plusMin(6));
        assertThrows(IllegalStateException.class, () -> r.svc.confirm(ann.lockId(), plusMin(7)));      // Ann is late
        assertFalse(r.free(plusMin(7)).contains("A1"), "Ann's failed confirm must not release Bob's seat");
        assertEquals(List.of("A1"), r.svc.confirm(bob.lockId(), plusMin(8)).seatIds());
    }

    void testDeclinedPaymentReleasesTheSeatsAndBooksNothing() {
        Rig r = new Rig();
        r.pay.declined.add("broke");
        MovieBooking.SeatLock l = r.svc.lockSeats("S1", "broke", List.of("B1", "B2"), T0);
        assertContains(assertThrows(IllegalStateException.class, () -> r.svc.confirm(l.lockId(), T0)).getMessage(), "declined");
        assertEquals(6, r.free(T0).size());
        assertTrue(r.pay.charges.isEmpty() && r.pay.refunds.isEmpty());
    }

    void testSeatsLostDuringPaymentAreRefunded() {
        Rig r = new Rig();
        MovieBooking.SeatLock ann = r.svc.lockSeats("S1", "ann", List.of("A1"), T0);
        // While Ann's (slow) payment is in flight, her lock runs out and Cy grabs the seat.
        r.pay.duringCharge = () -> { r.pay.duringCharge = () -> { }; r.svc.lockSeats("S1", "cy", List.of("A1"), plusMin(6)); };
        assertContains(assertThrows(IllegalStateException.class, () -> r.svc.confirm(ann.lockId(), plusMin(4))).getMessage(), "refunded");
        assertEquals(List.of("ann:1200"), r.pay.refunds);
        assertFalse(r.free(plusMin(7)).contains("A1"), "the seat now belongs to Cy's lock");
    }

    // ---- cancel ----------------------------------------------------------------------------------
    void testCancelFreesTheSeatsAndRefunds() {
        Rig r = new Rig();
        MovieBooking.Booking b = r.svc.confirm(r.svc.lockSeats("S1", "ann", List.of("A1", "A2"), T0).lockId(), T0);
        r.svc.cancel(b.id());
        assertEquals(6, r.free(plusMin(1)).size());
        assertEquals(List.of("ann:2400"), r.pay.refunds);
    }

    void testCancelTwiceOrUnknownFailsWithoutDoubleRefund() {
        Rig r = new Rig();
        MovieBooking.Booking b = r.svc.confirm(r.svc.lockSeats("S1", "ann", List.of("A1"), T0).lockId(), T0);
        r.svc.cancel(b.id());
        assertThrows(IllegalArgumentException.class, () -> r.svc.cancel(b.id()));
        assertThrows(IllegalArgumentException.class, () -> r.svc.cancel("B404"));
        assertEquals(1, r.pay.refunds.size());
    }

    void testCancelledSeatsCanBeBookedAgain() {
        Rig r = new Rig();
        MovieBooking.Booking b = r.svc.confirm(r.svc.lockSeats("S1", "ann", List.of("A1"), T0).lockId(), T0);
        r.svc.cancel(b.id());
        MovieBooking.Booking again = r.svc.confirm(r.svc.lockSeats("S1", "bob", List.of("A1"), plusMin(1)).lockId(), plusMin(1));
        assertEquals("bob", again.userId());
    }

    // ---- concurrency -----------------------------------------------------------------------------
    void testOnlyOneOfManyRacersGetsASeat() throws Exception {
        Rig r = new Rig();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            final String user = "u" + i;
            fs.add(pool.submit(() -> { try { r.svc.lockSeats("S1", user, List.of("B3"), T0); wins.incrementAndGet(); } catch (IllegalStateException ignored) { } }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertEquals(1, wins.get());
    }

    void testOverlappingRequestsInOppositeOrderNeitherDeadlockNorDoubleBook() throws Exception {
        for (int round = 0; round < 30; round++) {
            Rig r = new Rig();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> fs = new ArrayList<>();
            fs.add(pool.submit(() -> { go.await(); return tryLock(r, "ann", List.of("A1", "A2")); }));
            fs.add(pool.submit(() -> { go.await(); return tryLock(r, "bob", List.of("A2", "A1")); }));
            go.countDown();
            int winners = 0;
            for (Future<Boolean> f : fs) if (f.get(10, TimeUnit.SECONDS)) winners++;
            pool.shutdown();
            assertEquals(1, winners, "round " + round);
            assertEquals(List.of("A3", "B1", "B2", "B3"), r.free(T0));
        }
    }

    static boolean tryLock(Rig r, String user, List<String> seats) {
        try { r.svc.lockSeats("S1", user, seats, T0); return true; } catch (IllegalStateException e) { return false; }
    }

    public static void main(String[] args) throws Exception { System.exit(run(MovieBookingTest.class) == 0 ? 0 : 1); }
}
