import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class HotelReservationTest extends MiniTest {
    static final LocalDate D = LocalDate.of(2026, 12, 20);

    static HotelReservation.Hotel hotel() {
        HotelReservation.Hotel h = new HotelReservation.Hotel();
        h.addRoom(new HotelReservation.Room("101", HotelReservation.RoomType.SINGLE, 9000));
        h.addRoom(new HotelReservation.Room("102", HotelReservation.RoomType.SINGLE, 9000));
        h.addRoom(new HotelReservation.Room("201", HotelReservation.RoomType.SUITE, 25000));
        return h;
    }
    static List<String> numbers(List<HotelReservation.Room> rooms) { return rooms.stream().map(r -> r.number).toList(); }

    // ---- booking basics --------------------------------------------------------------------------
    void testBookingComputesTheTotalFromNightsAndRate() {
        HotelReservation.Reservation r = hotel().book("Ann", "101", D, D.plusDays(3));
        assertEquals(27000, r.totalCents);
        assertEquals(HotelReservation.Status.CONFIRMED, r.status);
        assertEquals("Ann", r.guest);
    }

    void testOneNightStay() {
        assertEquals(25000, hotel().book("Ann", "201", D, D.plusDays(1)).totalCents);
    }

    void testReservationIdsAreUnique() {
        HotelReservation.Hotel h = hotel();
        Set<String> ids = new HashSet<>();
        ids.add(h.book("A", "101", D, D.plusDays(1)).id);
        ids.add(h.book("B", "102", D, D.plusDays(1)).id);
        ids.add(h.book("C", "101", D.plusDays(5), D.plusDays(6)).id);
        assertEquals(3, ids.size());
    }

    void testUnknownRoomIsRejected() {
        assertContains(assertThrows(IllegalArgumentException.class, () -> hotel().book("Ann", "999", D, D.plusDays(1))).getMessage(), "No room");
    }

    void testMoneyFormatting() {
        assertEquals("$90.00", HotelReservation.money(9000));
        assertEquals("$0.05", HotelReservation.money(5));
    }

    // ---- date validation -------------------------------------------------------------------------
    void testCheckOutMustBeAfterCheckIn() {
        HotelReservation.Hotel h = hotel();
        assertThrows(IllegalArgumentException.class, () -> h.book("Ann", "101", D, D));
        assertThrows(IllegalArgumentException.class, () -> h.book("Ann", "101", D, D.minusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> h.search(HotelReservation.RoomType.SINGLE, D, D));
        assertThrows(IllegalArgumentException.class, () -> h.bookAny("Ann", HotelReservation.RoomType.SINGLE, D, D.minusDays(2)));
    }

    // ---- overlap rule ----------------------------------------------------------------------------
    void testIdenticalDatesConflict() {
        HotelReservation.Hotel h = hotel();
        h.book("Ann", "101", D, D.plusDays(3));
        assertContains(assertThrows(IllegalStateException.class, () -> h.book("Ben", "101", D, D.plusDays(3))).getMessage(), "not free");
    }

    void testPartialOverlapAtEitherEndConflicts() {
        HotelReservation.Hotel h = hotel();
        h.book("Ann", "101", D.plusDays(5), D.plusDays(8));
        assertThrows(IllegalStateException.class, () -> h.book("Ben", "101", D.plusDays(3), D.plusDays(6)));
        assertThrows(IllegalStateException.class, () -> h.book("Ben", "101", D.plusDays(7), D.plusDays(10)));
    }

    void testContainedAndContainingStaysConflict() {
        HotelReservation.Hotel h = hotel();
        h.book("Ann", "101", D.plusDays(5), D.plusDays(10));
        assertThrows(IllegalStateException.class, () -> h.book("Ben", "101", D.plusDays(6), D.plusDays(8)));
        assertThrows(IllegalStateException.class, () -> h.book("Cy", "101", D.plusDays(1), D.plusDays(20)));
    }

    void testBackToBackStaysAreAllowed() {
        HotelReservation.Hotel h = hotel();
        h.book("Ann", "101", D, D.plusDays(3));
        h.book("Ben", "101", D.plusDays(3), D.plusDays(5));          // checks in the day Ann checks out
        h.book("Cy", "101", D.minusDays(2), D);                      // checks out the day Ann checks in
    }

    void testOneNightGapBetweenStaysCanBeFilled() {
        HotelReservation.Hotel h = hotel();
        h.book("Ann", "101", D, D.plusDays(2));
        h.book("Ben", "101", D.plusDays(3), D.plusDays(5));
        h.book("Cy", "101", D.plusDays(2), D.plusDays(3));
        assertThrows(IllegalStateException.class, () -> h.book("Dan", "101", D.plusDays(2), D.plusDays(4)));
    }

    void testDifferentRoomsAreIndependent() {
        HotelReservation.Hotel h = hotel();
        h.book("Ann", "101", D, D.plusDays(3));
        h.book("Ben", "102", D, D.plusDays(3));
    }

    void testReservationOverlapsHelper() {
        HotelReservation.Reservation r = hotel().book("Ann", "101", D, D.plusDays(3));
        assertTrue(r.overlaps(D.plusDays(2), D.plusDays(4)));
        assertFalse(r.overlaps(D.plusDays(3), D.plusDays(4)));
        assertFalse(r.overlaps(D.minusDays(1), D));
    }

    // ---- cancel ----------------------------------------------------------------------------------
    void testCancelledStayNoLongerBlocksTheRoom() {
        HotelReservation.Hotel h = hotel();
        HotelReservation.Reservation a = h.book("Ann", "101", D, D.plusDays(3));
        h.cancel(a.id);
        assertEquals(HotelReservation.Status.CANCELLED, a.status);
        h.book("Ben", "101", D, D.plusDays(3));
    }

    void testCancelUnknownReservationFails() {
        assertThrows(IllegalArgumentException.class, () -> hotel().cancel("R404"));
    }

    void testCancellingTwiceIsHarmless() {
        HotelReservation.Hotel h = hotel();
        HotelReservation.Reservation a = h.book("Ann", "101", D, D.plusDays(3));
        h.cancel(a.id); h.cancel(a.id);
        assertEquals(HotelReservation.Status.CANCELLED, a.status);
    }

    void testCancellingOneStayLeavesTheOthersAlone() {
        HotelReservation.Hotel h = hotel();
        HotelReservation.Reservation a = h.book("Ann", "101", D, D.plusDays(3));
        h.book("Ben", "101", D.plusDays(3), D.plusDays(5));
        h.cancel(a.id);
        assertThrows(IllegalStateException.class, () -> h.book("Cy", "101", D.plusDays(4), D.plusDays(6)));
    }

    // ---- search and bookAny ----------------------------------------------------------------------
    void testSearchFiltersByTypeAndAvailability() {
        HotelReservation.Hotel h = hotel();
        assertEquals(List.of("101", "102"), numbers(h.search(HotelReservation.RoomType.SINGLE, D, D.plusDays(2))));
        assertEquals(List.of("201"), numbers(h.search(HotelReservation.RoomType.SUITE, D, D.plusDays(2))));
        assertEquals(List.of(), numbers(h.search(HotelReservation.RoomType.DOUBLE, D, D.plusDays(2))));
        h.book("Ann", "101", D, D.plusDays(3));
        assertEquals(List.of("102"), numbers(h.search(HotelReservation.RoomType.SINGLE, D, D.plusDays(3))));
        assertEquals(List.of("101", "102"), numbers(h.search(HotelReservation.RoomType.SINGLE, D.plusDays(3), D.plusDays(4))));
    }

    void testBookAnyTakesRoomsInOrderThenRunsOut() {
        HotelReservation.Hotel h = hotel();
        assertEquals("101", h.bookAny("A", HotelReservation.RoomType.SINGLE, D, D.plusDays(1)).orElseThrow().room.number);
        assertEquals("102", h.bookAny("B", HotelReservation.RoomType.SINGLE, D, D.plusDays(1)).orElseThrow().room.number);
        assertTrue(h.bookAny("C", HotelReservation.RoomType.SINGLE, D, D.plusDays(1)).isEmpty());
    }

    void testBookAnyNeverReturnsTheWrongRoomType() {
        HotelReservation.Hotel h = hotel();
        assertTrue(h.bookAny("A", HotelReservation.RoomType.DOUBLE, D, D.plusDays(1)).isEmpty());
        assertEquals("201", h.bookAny("A", HotelReservation.RoomType.SUITE, D, D.plusDays(1)).orElseThrow().room.number);
    }

    void testBookAnyReservationCanBeCancelledLikeAnyOther() {
        HotelReservation.Hotel h = hotel();
        HotelReservation.Reservation r = h.bookAny("A", HotelReservation.RoomType.SUITE, D, D.plusDays(1)).orElseThrow();
        h.cancel(r.id);
        assertTrue(h.bookAny("B", HotelReservation.RoomType.SUITE, D, D.plusDays(1)).isPresent());
    }

    // ---- concurrency -----------------------------------------------------------------------------
    void testManyGuestsRacingForOneRoomProduceExactlyOneWinner() throws Exception {
        HotelReservation.Hotel h = hotel();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger wins = new AtomicInteger(), losses = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            final String guest = "g" + i;
            fs.add(pool.submit(() -> {
                try { h.book(guest, "101", D, D.plusDays(2)); wins.incrementAndGet(); }
                catch (IllegalStateException e) { losses.incrementAndGet(); }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertEquals(1, wins.get());
        assertEquals(39, losses.get());
    }

    void testBookAnyUnderContentionFillsEachRoomExactlyOnce() throws Exception {
        HotelReservation.Hotel h = hotel();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Optional<HotelReservation.Reservation>>> fs = new ArrayList<>();
        for (int i = 0; i < 30; i++) { final String guest = "g" + i; fs.add(pool.submit(() -> h.bookAny(guest, HotelReservation.RoomType.SINGLE, D, D.plusDays(2)))); }
        Set<String> rooms = new HashSet<>();
        int wins = 0;
        for (Future<Optional<HotelReservation.Reservation>> f : fs) { Optional<HotelReservation.Reservation> r = f.get(); if (r.isPresent()) { wins++; rooms.add(r.get().room.number); } }
        pool.shutdown();
        assertEquals(2, wins);
        assertEquals(Set.of("101", "102"), rooms);
    }

    public static void main(String[] args) throws Exception { System.exit(run(HotelReservationTest.class) == 0 ? 0 : 1); }
}
