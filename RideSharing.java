import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ride Sharing (Uber style).
 *   - GridIndex: a uniform grid for "drivers near me". Only the cells around the rider are scanned, instead of every driver
 *     (a geohash or quadtree plays the same role at real scale).
 *   - Matching: candidates sorted by distance; each is RESERVED atomically before being offered the trip, so a driver can
 *     never be given two riders at once. A decline or timeout frees the driver and the next candidate is tried.
 *   - Trip is a state machine: REQUESTED -> ACCEPTED -> STARTED -> COMPLETED (cancel allowed before STARTED).
 *   - Pricing is a Strategy; SurgePricing is a Decorator over any base pricing.
 */
public class RideSharing {
    record Location(double x, double y) { double distanceTo(Location o) { return Math.hypot(x - o.x, y - o.y); } }
    enum VehicleType { BIKE, CAR }
    enum DriverStatus { AVAILABLE, BUSY }
    enum TripState { REQUESTED, ACCEPTED, STARTED, COMPLETED, CANCELLED, NO_DRIVER_FOUND }

    record Rider(String id, String name) {}

    static class Driver {
        final String id; final VehicleType type; private Location loc; private DriverStatus status = DriverStatus.AVAILABLE;
        Driver(String id, VehicleType type, Location loc) { this.id = id; this.type = type; this.loc = loc; }
        synchronized Location location() { return loc; }
        synchronized void setLocation(Location l) { loc = l; }
        synchronized DriverStatus status() { return status; }
        synchronized boolean tryReserve() { if (status != DriverStatus.AVAILABLE) return false; status = DriverStatus.BUSY; return true; }
        synchronized void free() { status = DriverStatus.AVAILABLE; }
    }

    static class GridIndex {
        private final double cell; private final Map<Long, Set<Driver>> cells = new HashMap<>();
        GridIndex(double cellKm) { cell = cellKm; }
        private static long key(int cx, int cy) { return ((long) cx << 32) | (cy & 0xffffffffL); }
        private long keyOf(Location l) { return key((int) Math.floor(l.x() / cell), (int) Math.floor(l.y() / cell)); }

        synchronized void add(Driver d) { cells.computeIfAbsent(keyOf(d.location()), k -> new HashSet<>()).add(d); }
        synchronized void move(Driver d, Location to) {
            Set<Driver> old = cells.get(keyOf(d.location()));
            if (old != null) { old.remove(d); if (old.isEmpty()) cells.remove(keyOf(d.location())); }
            d.setLocation(to); add(d);
        }
        /** Drivers within radius, nearest first. */
        synchronized List<Driver> nearby(Location c, double radius) {
            int span = (int) Math.ceil(radius / cell), cx = (int) Math.floor(c.x() / cell), cy = (int) Math.floor(c.y() / cell);
            List<Driver> out = new ArrayList<>();
            for (int dx = -span; dx <= span; dx++)
                for (int dy = -span; dy <= span; dy++) {
                    Set<Driver> s = cells.get(key(cx + dx, cy + dy));
                    if (s != null) for (Driver d : s) if (d.location().distanceTo(c) <= radius) out.add(d);
                }
            out.sort(Comparator.comparingDouble(d -> d.location().distanceTo(c)));
            return out;
        }
    }

    interface PricingStrategy { long fareCents(double km, double minutes, int demand, int supply); }

    static class BasePricing implements PricingStrategy {
        public long fareCents(double km, double minutes, int demand, int supply) { return Math.round(200 + 120 * km + 30 * minutes); }
    }

    static class SurgePricing implements PricingStrategy {
        private final PricingStrategy inner; private final double cap;
        SurgePricing(PricingStrategy inner, double cap) { this.inner = inner; this.cap = cap; }
        public long fareCents(double km, double minutes, int demand, int supply) {
            double multiplier = Math.min(cap, 1 + 0.25 * Math.max(0, demand - supply) / Math.max(1, supply));
            return Math.round(inner.fareCents(km, minutes, demand, supply) * multiplier);
        }
    }

    static class Trip {
        final String id; final Rider rider; final Location pickup, drop; final VehicleType type;
        TripState state = TripState.REQUESTED; Driver driver; long quoteCents; final List<String> declinedBy = new ArrayList<>();
        Trip(String id, Rider r, Location pickup, Location drop, VehicleType t) { this.id = id; rider = r; this.pickup = pickup; this.drop = drop; type = t; }
    }

    /** Stands in for the driver's app: true = accepted, false = declined or timed out. */
    interface DriverResponder { boolean accepts(Driver d, Trip t); }

    static class RideService {
        static final double SEARCH_RADIUS_KM = 5; static final int MAX_OFFERS = 3;
        private final GridIndex index = new GridIndex(1.0);
        private final PricingStrategy pricing; private final DriverResponder responder;
        private final AtomicInteger seq = new AtomicInteger(), openRequests = new AtomicInteger();

        RideService(PricingStrategy p, DriverResponder r) { pricing = p; responder = r; }
        GridIndex index() { return index; }
        void addDriver(Driver d) { index.add(d); }

        Trip requestRide(Rider rider, Location pickup, Location drop, VehicleType type) {
            Trip t = new Trip("T" + seq.incrementAndGet(), rider, pickup, drop, type);
            int demand = openRequests.incrementAndGet();
            try {
                List<Driver> candidates = index.nearby(pickup, SEARCH_RADIUS_KM).stream()
                    .filter(d -> d.type == type && d.status() == DriverStatus.AVAILABLE).toList();
                double km = pickup.distanceTo(drop);
                t.quoteCents = pricing.fareCents(km, km * 2.4, demand, candidates.size());   // price is locked at request time
                int offers = 0;
                for (Driver d : candidates) {
                    if (offers >= MAX_OFFERS) break;
                    if (!d.tryReserve()) continue;                  // another rider reserved this driver first
                    offers++;
                    if (responder.accepts(d, t)) { t.driver = d; t.state = TripState.ACCEPTED; return t; }
                    d.free(); t.declinedBy.add(d.id);               // declined or timed out: try the next one
                }
                t.state = TripState.NO_DRIVER_FOUND;
                return t;
            } finally { openRequests.decrementAndGet(); }
        }

        void start(Trip t) {
            if (t.state != TripState.ACCEPTED) throw new IllegalStateException("Trip is " + t.state + ", cannot start");
            t.state = TripState.STARTED;
        }

        long complete(Trip t) {
            if (t.state != TripState.STARTED) throw new IllegalStateException("Trip is " + t.state + ", cannot complete");
            t.state = TripState.COMPLETED;
            index.move(t.driver, t.drop);
            t.driver.free();
            return t.quoteCents;
        }

        void cancel(Trip t) {
            if (t.state != TripState.REQUESTED && t.state != TripState.ACCEPTED)
                throw new IllegalStateException("Cannot cancel a trip that is " + t.state);
            if (t.driver != null) t.driver.free();
            t.state = TripState.CANCELLED;
        }
    }

    static String money(long cents) { return String.format("$%.2f", cents / 100.0); }

    public static void main(String[] args) throws Exception {
        // 1) The grid index must agree with a brute-force scan.
        Random rnd = new Random(3);
        GridIndex grid = new GridIndex(1.0);
        List<Driver> all = new ArrayList<>();
        for (int i = 0; i < 500; i++) { Driver d = new Driver("x" + i, VehicleType.CAR, new Location(rnd.nextDouble() * 40 - 20, rnd.nextDouble() * 40 - 20)); all.add(d); grid.add(d); }
        for (int i = 0; i < 100; i++) grid.move(all.get(i), new Location(rnd.nextDouble() * 40 - 20, rnd.nextDouble() * 40 - 20));
        boolean same = true;
        for (int q = 0; q < 200; q++) {
            Location c = new Location(rnd.nextDouble() * 40 - 20, rnd.nextDouble() * 40 - 20);
            Set<Driver> fast = new HashSet<>(grid.nearby(c, 3.5)), slow = new HashSet<>();
            for (Driver d : all) if (d.location().distanceTo(c) <= 3.5) slow.add(d);
            same &= fast.equals(slow);
        }
        System.out.println("Grid index matches brute force on 200 queries (after 100 moves): " + same);

        // 2) Matching with a decline, then a trip lifecycle.
        RideService svc = new RideService(new SurgePricing(new BasePricing(), 3.0),
            (d, t) -> !(d.id.equals("d1") && t.rider.id().equals("r1")));      // d1 declines r1 only
        svc.addDriver(new Driver("d1", VehicleType.CAR, new Location(1, 1)));
        svc.addDriver(new Driver("d2", VehicleType.CAR, new Location(2, 2)));
        svc.addDriver(new Driver("d3", VehicleType.BIKE, new Location(0.5, 0.5)));
        svc.addDriver(new Driver("d4", VehicleType.CAR, new Location(20, 20)));

        Trip t1 = svc.requestRide(new Rider("r1", "Ann"), new Location(0, 0), new Location(5, 0), VehicleType.CAR);
        System.out.println("Trip " + t1.id + ": " + t1.state + " driver=" + t1.driver.id + " declinedBy=" + t1.declinedBy + " quote=" + money(t1.quoteCents));
        svc.start(t1);
        try { svc.cancel(t1); } catch (IllegalStateException e) { System.out.println("Cancel mid-trip: " + e.getMessage()); }
        System.out.println("Completed, charged " + money(svc.complete(t1)) + "; d2 now at " + t1.driver.location());

        Trip t2 = svc.requestRide(new Rider("r2", "Ben"), new Location(0, 0), new Location(3, 0), VehicleType.CAR);
        System.out.println("Trip " + t2.id + " matched to " + t2.driver.id + ", then rider cancels");
        svc.cancel(t2);
        System.out.println("  " + t2.driver.id + " is " + t2.driver.status() + " again");

        Trip far = svc.requestRide(new Rider("r3", "Cy"), new Location(50, 50), new Location(51, 50), VehicleType.CAR);
        System.out.println("Rider far from every driver: " + far.state);

        // 3) Surge pricing: 5 km, 12 min.
        PricingStrategy surge = new SurgePricing(new BasePricing(), 3.0);
        System.out.println("Fare 5km/12min  balanced market: " + money(surge.fareCents(5, 12, 2, 2))
            + "   6 requests/2 drivers: " + money(surge.fareCents(5, 12, 6, 2)) + "   40 requests/1 driver (capped 3x): " + money(surge.fareCents(5, 12, 40, 1)));

        // 4) Two riders race for the only driver: exactly one may get him.
        RideService race = new RideService(new BasePricing(), (d, t) -> true);
        race.addDriver(new Driver("solo", VehicleType.CAR, new Location(0, 0)));
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Trip>> fs = new ArrayList<>();
        for (String id : List.of("rA", "rB")) fs.add(pool.submit(() -> { go.await(); return race.requestRide(new Rider(id, id), new Location(0.1, 0.1), new Location(2, 2), VehicleType.CAR); }));
        go.countDown();
        int accepted = 0, none = 0;
        for (Future<Trip> f : fs) { if (f.get().state == TripState.ACCEPTED) accepted++; else if (f.get().state == TripState.NO_DRIVER_FOUND) none++; }
        pool.shutdown();
        System.out.println("Race for one driver: ACCEPTED=" + accepted + " NO_DRIVER_FOUND=" + none);
    }
}
