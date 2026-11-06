import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parking Lot: Strategy for pricing and spot assignment.
 * Free spots live in a concurrent deque per (floor, spot type), so claiming a
 * spot is one atomic poll(): O(1) and no two vehicles can get the same spot.
 */
public class ParkingLot {
    enum VehicleType { MOTORCYCLE, CAR, TRUCK }
    enum SpotType { MOTORCYCLE, COMPACT, LARGE }

    record Vehicle(String plate, VehicleType type) {}
    record Spot(String id, SpotType type, int floor) {}
    record Ticket(String id, Vehicle vehicle, Spot spot, Instant entry) {}

    static class Floor {
        final int number;
        final Map<SpotType, ConcurrentLinkedDeque<Spot>> free = new EnumMap<>(SpotType.class);
        Floor(int number) {
            this.number = number;
            for (SpotType t : SpotType.values()) free.put(t, new ConcurrentLinkedDeque<>());
        }
        void addSpot(Spot s) { free.get(s.type()).add(s); }
        Optional<Spot> claim(SpotType t) { return Optional.ofNullable(free.get(t).pollFirst()); }
        void release(Spot s) { free.get(s.type()).addFirst(s); }
        int available() { return free.values().stream().mapToInt(Collection::size).sum(); }
    }

    interface PricingStrategy { double fee(Ticket t, Instant exit); }

    static class HourlyPricing implements PricingStrategy {
        private final Map<VehicleType, Double> ratePerHour;
        HourlyPricing(Map<VehicleType, Double> ratePerHour) { this.ratePerHour = ratePerHour; }
        public double fee(Ticket t, Instant exit) {
            long minutes = Duration.between(t.entry(), exit).toMinutes();
            long hours = Math.max(1, (minutes + 59) / 60); // round up, 1 hour minimum
            return hours * ratePerHour.get(t.vehicle().type());
        }
    }

    interface SpotAssignmentStrategy { Optional<Spot> claim(Vehicle v, List<Floor> floors); }

    static class LowestFloorFirst implements SpotAssignmentStrategy {
        // smallest spot that fits first, so large spots stay free for trucks
        private static final Map<VehicleType, List<SpotType>> FITS = Map.of(
            VehicleType.MOTORCYCLE, List.of(SpotType.MOTORCYCLE, SpotType.COMPACT, SpotType.LARGE),
            VehicleType.CAR, List.of(SpotType.COMPACT, SpotType.LARGE),
            VehicleType.TRUCK, List.of(SpotType.LARGE));
        public Optional<Spot> claim(Vehicle v, List<Floor> floors) {
            for (Floor f : floors)
                for (SpotType t : FITS.get(v.type())) {
                    Optional<Spot> s = f.claim(t);
                    if (s.isPresent()) return s;
                }
            return Optional.empty();
        }
    }

    private final List<Floor> floors;
    private final SpotAssignmentStrategy assignment;
    private final PricingStrategy pricing;
    private final Map<String, Ticket> active = new ConcurrentHashMap<>();
    private final Set<String> parkedPlates = ConcurrentHashMap.newKeySet();
    private final AtomicInteger ticketSeq = new AtomicInteger();

    ParkingLot(List<Floor> floors, SpotAssignmentStrategy a, PricingStrategy p) {
        this.floors = floors; this.assignment = a; this.pricing = p;
    }

    Optional<Ticket> park(Vehicle v, Instant now) {
        if (!parkedPlates.add(v.plate())) throw new IllegalStateException("Already parked: " + v.plate());
        Optional<Spot> spot = assignment.claim(v, floors);
        if (spot.isEmpty()) { parkedPlates.remove(v.plate()); return Optional.empty(); }
        Ticket t = new Ticket("T" + ticketSeq.incrementAndGet(), v, spot.get(), now);
        active.put(t.id(), t);
        return Optional.of(t);
    }

    double unpark(String ticketId, Instant exit) {
        Ticket t = active.remove(ticketId);
        if (t == null) throw new IllegalArgumentException("Invalid or already used ticket: " + ticketId);
        double fee = pricing.fee(t, exit);
        floors.get(t.spot().floor()).release(t.spot());
        parkedPlates.remove(t.vehicle().plate());
        return fee;
    }

    int available() { return floors.stream().mapToInt(Floor::available).sum(); }

    public static void main(String[] args) throws Exception {
        Floor f0 = new Floor(0), f1 = new Floor(1);
        f0.addSpot(new Spot("0-M1", SpotType.MOTORCYCLE, 0));
        f0.addSpot(new Spot("0-C1", SpotType.COMPACT, 0));
        f1.addSpot(new Spot("1-C1", SpotType.COMPACT, 1));
        f1.addSpot(new Spot("1-L1", SpotType.LARGE, 1));
        ParkingLot lot = new ParkingLot(List.of(f0, f1), new LowestFloorFirst(),
            new HourlyPricing(Map.of(VehicleType.MOTORCYCLE, 1.0, VehicleType.CAR, 2.0, VehicleType.TRUCK, 5.0)));

        Instant t0 = Instant.parse("2026-11-06T09:00:00Z");
        Ticket car = lot.park(new Vehicle("CAR-1", VehicleType.CAR), t0).orElseThrow();
        Ticket truck = lot.park(new Vehicle("TRK-1", VehicleType.TRUCK), t0).orElseThrow();
        System.out.println("Car -> " + car.spot().id() + ", Truck -> " + truck.spot().id());
        System.out.println("Fee car (2h30m): " + lot.unpark(car.id(), t0.plusSeconds(2 * 3600 + 1800)));
        System.out.println("Fee truck (10m): " + lot.unpark(truck.id(), t0.plusSeconds(600)));
        try { lot.unpark(car.id(), t0); } catch (IllegalArgumentException e) { System.out.println("Reuse blocked: " + e.getMessage()); }

        // Concurrency: 100 threads race for 4 spots; exactly 4 must win.
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int n = i;
            fs.add(pool.submit(() -> {
                if (lot.park(new Vehicle("RACE-" + n, VehicleType.CAR), Instant.now()).isPresent()) wins.incrementAndGet();
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        System.out.println("Concurrent winners: " + wins.get() + " (expected 3 cars: 2 compact + 1 large), available=" + lot.available());
    }
}
