import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hotel Reservation. Availability is a date-range overlap check:
 *   two stays conflict iff  a.in < b.out  &&  b.in < a.out
 * so a guest checking in on the day another checks out is fine (check-out is exclusive).
 * Each room's check-then-insert is atomic (synchronized on the room), which is the
 * in-memory equivalent of a transaction or a unique-constraint check in a database.
 */
public class HotelReservation {
    enum RoomType { SINGLE, DOUBLE, SUITE }
    enum Status { CONFIRMED, CANCELLED }

    static class Reservation {
        final String id, guest; final Room room; final LocalDate in, out; final long totalCents;
        Status status = Status.CONFIRMED;
        Reservation(String id, String guest, Room room, LocalDate in, LocalDate out) {
            this.id = id; this.guest = guest; this.room = room; this.in = in; this.out = out;
            this.totalCents = ChronoUnit.DAYS.between(in, out) * room.rateCents;
        }
        boolean overlaps(LocalDate otherIn, LocalDate otherOut) { return in.isBefore(otherOut) && otherIn.isBefore(out); }
    }

    static class Room {
        final String number; final RoomType type; final long rateCents;
        private final List<Reservation> reservations = new ArrayList<>();
        Room(String number, RoomType type, long rateCents) { this.number = number; this.type = type; this.rateCents = rateCents; }

        synchronized boolean isFree(LocalDate in, LocalDate out) {
            return reservations.stream().noneMatch(r -> r.status == Status.CONFIRMED && r.overlaps(in, out));
        }
        /** Atomic check-and-insert. */
        synchronized Optional<Reservation> tryBook(String id, String guest, LocalDate in, LocalDate out) {
            if (!isFree(in, out)) return Optional.empty();
            Reservation r = new Reservation(id, guest, this, in, out);
            reservations.add(r);
            return Optional.of(r);
        }
    }

    static class Hotel {
        private final Map<String, Room> rooms = new LinkedHashMap<>();
        private final Map<String, Reservation> byId = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();

        void addRoom(Room r) { rooms.put(r.number, r); }

        private static void validate(LocalDate in, LocalDate out) {
            if (!out.isAfter(in)) throw new IllegalArgumentException("Check-out must be after check-in");
        }

        List<Room> search(RoomType type, LocalDate in, LocalDate out) {
            validate(in, out);
            return rooms.values().stream().filter(r -> r.type == type && r.isFree(in, out)).toList();
        }

        Reservation book(String guest, String roomNumber, LocalDate in, LocalDate out) {
            validate(in, out);
            Room room = rooms.get(roomNumber);
            if (room == null) throw new IllegalArgumentException("No room " + roomNumber);
            Reservation r = room.tryBook("R" + seq.incrementAndGet(), guest, in, out)
                .orElseThrow(() -> new IllegalStateException("Room " + roomNumber + " is not free for those dates"));
            byId.put(r.id, r);
            return r;
        }

        /** Picks any free room of the type; tryBook is atomic so two guests can never share a room. */
        Optional<Reservation> bookAny(String guest, RoomType type, LocalDate in, LocalDate out) {
            validate(in, out);
            for (Room room : rooms.values()) {
                if (room.type != type) continue;
                Optional<Reservation> r = room.tryBook("R" + seq.incrementAndGet(), guest, in, out);
                if (r.isPresent()) { byId.put(r.get().id, r.get()); return r; }
            }
            return Optional.empty();
        }

        void cancel(String reservationId) {
            Reservation r = byId.get(reservationId);
            if (r == null) throw new IllegalArgumentException("No reservation " + reservationId);
            synchronized (r.room) { r.status = Status.CANCELLED; }
        }
    }

    static String money(long cents) { return String.format("$%.2f", cents / 100.0); }

    public static void main(String[] args) throws Exception {
        Hotel h = new Hotel();
        h.addRoom(new Room("101", RoomType.SINGLE, 9000));
        h.addRoom(new Room("102", RoomType.SINGLE, 9000));
        h.addRoom(new Room("201", RoomType.SUITE, 25000));
        LocalDate d = LocalDate.of(2026, 12, 20);

        Reservation a = h.book("Ann", "101", d, d.plusDays(3));
        System.out.println("Ann booked 101 for 3 nights: " + money(a.totalCents));
        try { h.book("Ben", "101", d.plusDays(2), d.plusDays(5)); } catch (IllegalStateException e) { System.out.println("Overlap rejected: " + e.getMessage()); }
        Reservation back = h.book("Ben", "101", d.plusDays(3), d.plusDays(5));
        System.out.println("Back-to-back stay allowed (checkout day = next check-in): " + back.id);
        System.out.println("Free singles " + d + " to " + d.plusDays(3) + ": " + h.search(RoomType.SINGLE, d, d.plusDays(3)).stream().map(r -> r.number).toList());

        h.cancel(a.id);
        System.out.println("After Ann cancels, 101 free again: " + h.search(RoomType.SINGLE, d, d.plusDays(3)).stream().map(r -> r.number).toList());
        try { h.book("Cy", "101", d.plusDays(4), d.plusDays(4)); } catch (IllegalArgumentException e) { System.out.println("Bad dates: " + e.getMessage()); }

        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger wins = new AtomicInteger();
        LocalDate x = d.plusDays(30);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final String guest = "g" + i;
            fs.add(pool.submit(() -> { if (h.bookAny(guest, RoomType.SINGLE, x, x.plusDays(2)).isPresent()) wins.incrementAndGet(); }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        System.out.println("20 guests raced for 2 single rooms, confirmed = " + wins.get());
    }
}
