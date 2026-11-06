import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Movie Ticket Booking (BookMyShow style). The hard part is concurrent seat booking:
 *   AVAILABLE --lock(TTL)--> LOCKED --confirm(payment)--> BOOKED
 * A lock is all-or-nothing per request, expires on its own (checked lazily, no sweeper
 * thread needed), and the slow payment call happens OUTSIDE the show-level lock.
 */
public class MovieBooking {
    enum SeatStatus { AVAILABLE, LOCKED, BOOKED }

    record Movie(String id, String title) {}
    record SeatLock(String lockId, String showId, String userId, List<String> seatIds, Instant expiry) {}
    record Booking(String id, String userId, String showId, List<String> seatIds, int totalCents) {}

    static class Seat {
        final String id; final int priceCents;
        SeatStatus status = SeatStatus.AVAILABLE; String lockId; Instant lockExpiry;
        Seat(String id, int priceCents) { this.id = id; this.priceCents = priceCents; }
    }

    static class Show {
        final String id; final Movie movie; final Map<String, Seat> seats = new LinkedHashMap<>();
        Show(String id, Movie movie, int rows, int cols, int priceCents) {
            this.id = id; this.movie = movie;
            for (int r = 0; r < rows; r++)
                for (int c = 1; c <= cols; c++) { String sid = "" + (char) ('A' + r) + c; seats.put(sid, new Seat(sid, priceCents)); }
        }
    }

    interface PaymentGateway {
        boolean charge(String userId, int cents);
        void refund(String userId, int cents);
    }

    static class BookingService {
        private final Map<String, Show> shows = new HashMap<>();
        private final Map<String, SeatLock> locks = new ConcurrentHashMap<>();
        private final Map<String, Booking> bookings = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();
        private final Duration lockTtl; private final PaymentGateway pay;

        BookingService(Duration lockTtl, PaymentGateway pay) { this.lockTtl = lockTtl; this.pay = pay; }
        void addShow(Show s) { shows.put(s.id, s); }

        private static boolean isFree(Seat s, Instant now) {
            return s.status == SeatStatus.AVAILABLE || (s.status == SeatStatus.LOCKED && !now.isBefore(s.lockExpiry));
        }

        private Show show(String showId) {
            Show s = shows.get(showId);
            if (s == null) throw new IllegalArgumentException("No show " + showId);
            return s;
        }

        List<String> availableSeats(String showId, Instant now) {
            Show show = show(showId);
            synchronized (show) { return show.seats.values().stream().filter(s -> isFree(s, now)).map(s -> s.id).toList(); }
        }

        /** Locks all requested seats or none of them. */
        SeatLock lockSeats(String showId, String userId, List<String> seatIds, Instant now) {
            Show show = show(showId);
            if (seatIds.isEmpty()) throw new IllegalArgumentException("Pick at least one seat");
            if (new HashSet<>(seatIds).size() != seatIds.size())              // [A1, A1] would be priced and charged twice
                throw new IllegalArgumentException("Duplicate seat in request");
            synchronized (show) {
                for (String id : seatIds) {
                    Seat s = show.seats.get(id);
                    if (s == null) throw new IllegalArgumentException("No seat " + id);
                    if (!isFree(s, now)) throw new IllegalStateException("Seat " + id + " is not available");
                }
                String lockId = "L" + seq.incrementAndGet();
                Instant exp = now.plus(lockTtl);
                for (String id : seatIds) {
                    Seat s = show.seats.get(id);
                    s.status = SeatStatus.LOCKED; s.lockId = lockId; s.lockExpiry = exp;
                }
                SeatLock l = new SeatLock(lockId, showId, userId, List.copyOf(seatIds), exp);
                locks.put(lockId, l);
                return l;
            }
        }

        Booking confirm(String lockId, Instant now) {
            SeatLock l = locks.get(lockId);
            if (l == null) throw new IllegalArgumentException("Unknown lock " + lockId);
            Show show = shows.get(l.showId());
            int total;
            synchronized (show) {                                   // phase 1: validate, compute price
                if (!now.isBefore(l.expiry())) { release(show, l); throw new IllegalStateException("Lock expired"); }
                total = l.seatIds().stream().mapToInt(id -> show.seats.get(id).priceCents).sum();
            }
            if (!pay.charge(l.userId(), total)) {                   // phase 2: slow call, no lock held
                synchronized (show) { release(show, l); }
                throw new IllegalStateException("Payment declined, seats released");
            }
            synchronized (show) {                                   // phase 3: commit if the seats are still ours
                boolean stillOurs = l.seatIds().stream().allMatch(id -> lockId.equals(show.seats.get(id).lockId));
                if (!stillOurs) { pay.refund(l.userId(), total); throw new IllegalStateException("Seats were lost while paying, refunded"); }
                for (String id : l.seatIds()) { Seat s = show.seats.get(id); s.status = SeatStatus.BOOKED; s.lockId = null; }
                locks.remove(lockId);
                Booking b = new Booking("B" + seq.incrementAndGet(), l.userId(), l.showId(), l.seatIds(), total);
                bookings.put(b.id(), b);
                return b;
            }
        }

        void cancel(String bookingId) {
            Booking b = bookings.remove(bookingId);
            if (b == null) throw new IllegalArgumentException("No booking " + bookingId);
            Show show = shows.get(b.showId());
            synchronized (show) { for (String id : b.seatIds()) { Seat s = show.seats.get(id); s.status = SeatStatus.AVAILABLE; s.lockId = null; } }
            pay.refund(b.userId(), b.totalCents());
        }

        private void release(Show show, SeatLock l) {
            for (String id : l.seatIds()) {
                Seat s = show.seats.get(id);
                if (l.lockId().equals(s.lockId)) { s.status = SeatStatus.AVAILABLE; s.lockId = null; }   // only if still ours
            }
            locks.remove(l.lockId());
        }
    }

    static class DemoPay implements PaymentGateway {
        public boolean charge(String user, int cents) { return !user.equals("broke"); }
        public void refund(String user, int cents) { System.out.println("  [refund] " + user + " " + cents + "c"); }
    }

    public static void main(String[] args) throws Exception {
        BookingService svc = new BookingService(Duration.ofMinutes(5), new DemoPay());
        svc.addShow(new Show("S1", new Movie("M1", "Dune 3"), 2, 3, 1200));
        Instant t0 = Instant.parse("2026-11-06T18:00:00Z");

        SeatLock alice = svc.lockSeats("S1", "alice", List.of("A1", "A2"), t0);
        System.out.println("Alice locked " + alice.seatIds());
        try { svc.lockSeats("S1", "bob", List.of("A2", "B1"), t0); } catch (IllegalStateException e) { System.out.println("Bob: " + e.getMessage()); }
        System.out.println("B1 untouched after Bob's failed all-or-nothing request: " + svc.availableSeats("S1", t0));
        SeatLock bob = svc.lockSeats("S1", "bob", List.of("B1"), t0);

        Booking b = svc.confirm(alice.lockId(), t0.plusSeconds(60));
        System.out.println("Alice booked " + b.seatIds() + " total " + b.totalCents() + "c");

        Instant late = t0.plus(Duration.ofMinutes(6));
        SeatLock cy = svc.lockSeats("S1", "cy", List.of("B1"), late);
        System.out.println("Bob's lock expired, Cy got B1 at +6 min");
        try { svc.confirm(bob.lockId(), late); } catch (IllegalStateException e) { System.out.println("Bob confirm: " + e.getMessage()); }
        System.out.println("Cy keeps B1? " + svc.confirm(cy.lockId(), late.plusSeconds(30)).seatIds());

        SeatLock broke = svc.lockSeats("S1", "broke", List.of("B2"), late);
        try { svc.confirm(broke.lockId(), late); } catch (IllegalStateException e) { System.out.println("Broke: " + e.getMessage()); }
        System.out.println("B2 free again: " + svc.availableSeats("S1", late));

        System.out.println("Cancel Alice's booking:");
        svc.cancel(b.id());
        System.out.println("Available: " + svc.availableSeats("S1", late));

        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final String user = "u" + i;
            fs.add(pool.submit(() -> {
                try { svc.lockSeats("S1", user, List.of("B3"), late); wins.incrementAndGet(); } catch (IllegalStateException ignored) {}
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        System.out.println("50 threads raced for B3, winners = " + wins.get());
    }
}
