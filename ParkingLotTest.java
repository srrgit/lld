import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ParkingLotTest extends MiniTest {
    static final Instant T0 = Instant.parse("2026-11-06T09:00:00Z");

    static ParkingLot.Floor floor(int number, ParkingLot.SpotType... types) {
        ParkingLot.Floor f = new ParkingLot.Floor(number);
        for (int i = 0; i < types.length; i++) f.addSpot(new ParkingLot.Spot(number + "-" + types[i].name().charAt(0) + i, types[i], number));
        return f;
    }
    static ParkingLot lot(ParkingLot.Floor... floors) {
        return new ParkingLot(List.of(floors), new ParkingLot.LowestFloorFirst(), new ParkingLot.HourlyPricing(Map.of(
            ParkingLot.VehicleType.MOTORCYCLE, 1.0, ParkingLot.VehicleType.CAR, 2.0, ParkingLot.VehicleType.TRUCK, 5.0)));
    }
    static ParkingLot.Vehicle vehicle(String plate, ParkingLot.VehicleType t) { return new ParkingLot.Vehicle(plate, t); }
    static ParkingLot.Vehicle car(String plate) { return vehicle(plate, ParkingLot.VehicleType.CAR); }

    static double feeFor(ParkingLot.VehicleType type, long minutes) {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.LARGE));              // a large spot fits every vehicle
        ParkingLot.Ticket t = l.park(vehicle("P", type), T0).orElseThrow();
        return l.unpark(t.id(), T0.plusSeconds(minutes * 60));
    }

    // ---- spot assignment -----------------------------------------------------------------------
    void testCarPrefersCompactOverLargeSpot() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.LARGE, ParkingLot.SpotType.COMPACT));
        assertEquals(ParkingLot.SpotType.COMPACT, l.park(car("A"), T0).orElseThrow().spot().type());
    }

    void testTruckOnlyFitsLargeSpots() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.LARGE));
        ParkingLot.Vehicle truck = vehicle("T1", ParkingLot.VehicleType.TRUCK);
        assertEquals(ParkingLot.SpotType.LARGE, l.park(truck, T0).orElseThrow().spot().type());
        assertTrue(l.park(vehicle("T2", ParkingLot.VehicleType.TRUCK), T0).isEmpty(), "second truck must not squeeze into a compact spot");
    }

    void testMotorcycleFallsBackFromSmallestToLargest() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.LARGE, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.MOTORCYCLE));
        List<ParkingLot.SpotType> got = new ArrayList<>();
        for (int i = 0; i < 3; i++) got.add(l.park(vehicle("M" + i, ParkingLot.VehicleType.MOTORCYCLE), T0).orElseThrow().spot().type());
        assertEquals(List.of(ParkingLot.SpotType.MOTORCYCLE, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.LARGE), got);
        assertTrue(l.park(vehicle("M3", ParkingLot.VehicleType.MOTORCYCLE), T0).isEmpty());
    }

    void testLowestFloorIsFilledFirst() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT), floor(1, ParkingLot.SpotType.COMPACT));
        assertEquals(0, l.park(car("A"), T0).orElseThrow().spot().floor());
        assertEquals(1, l.park(car("B"), T0).orElseThrow().spot().floor());
    }

    void testEmptyLotRejectsEveryone() {
        ParkingLot l = lot(new ParkingLot.Floor(0));
        assertTrue(l.park(car("A"), T0).isEmpty());
        assertEquals(0, l.available());
    }

    // ---- plates, tickets, availability -----------------------------------------------------------
    void testFullLotDoesNotLeakThePlateOfARejectedVehicle() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT));
        ParkingLot.Ticket a = l.park(car("A"), T0).orElseThrow();
        assertTrue(l.park(car("B"), T0).isEmpty());
        l.unpark(a.id(), T0.plusSeconds(60));
        assertTrue(l.park(car("B"), T0).isPresent(), "B was turned away earlier, so its plate must not be stuck as parked");
    }

    void testSamePlateCannotParkTwice() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT));
        l.park(car("A"), T0);
        assertThrows(IllegalStateException.class, () -> l.park(car("A"), T0));
        assertEquals(1, l.available());
    }

    void testPlateCanReturnAfterLeaving() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT));
        ParkingLot.Ticket t = l.park(car("A"), T0).orElseThrow();
        l.unpark(t.id(), T0.plusSeconds(3600));
        assertTrue(l.park(car("A"), T0.plusSeconds(7200)).isPresent());
    }

    void testUnparkFreesTheSpot() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT));
        ParkingLot.Ticket t = l.park(car("A"), T0).orElseThrow();
        assertEquals(1, l.available());
        l.unpark(t.id(), T0.plusSeconds(60));
        assertEquals(2, l.available());
    }

    void testUnknownAndReusedTicketsAreRejected() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT));
        assertThrows(IllegalArgumentException.class, () -> l.unpark("nope", T0));
        ParkingLot.Ticket t = l.park(car("A"), T0).orElseThrow();
        l.unpark(t.id(), T0.plusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> l.unpark(t.id(), T0.plusSeconds(120)));
        assertEquals(1, l.available(), "a reused ticket must not free a second spot");
    }

    void testTicketIdsAreUnique() {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT));
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) ids.add(l.park(car("C" + i), T0).orElseThrow().id());
        assertEquals(3, ids.size());
    }

    // ---- pricing ---------------------------------------------------------------------------------
    void testFeeRoundsUpToWholeHours() {
        assertEquals(2.0, feeFor(ParkingLot.VehicleType.CAR, 60), 1e-9);     // exactly 1 hour
        assertEquals(4.0, feeFor(ParkingLot.VehicleType.CAR, 61), 1e-9);     // one minute over -> 2 hours
        assertEquals(6.0, feeFor(ParkingLot.VehicleType.CAR, 150), 1e-9);    // 2h30 -> 3 hours
    }

    void testOneHourMinimumApplies() {
        assertEquals(2.0, feeFor(ParkingLot.VehicleType.CAR, 0), 1e-9);
        assertEquals(2.0, feeFor(ParkingLot.VehicleType.CAR, 1), 1e-9);
        assertEquals(5.0, feeFor(ParkingLot.VehicleType.TRUCK, 10), 1e-9);
    }

    void testRateDependsOnVehicleType() {
        assertEquals(1.0, feeFor(ParkingLot.VehicleType.MOTORCYCLE, 30), 1e-9);
        assertEquals(2.0, feeFor(ParkingLot.VehicleType.CAR, 30), 1e-9);
        assertEquals(5.0, feeFor(ParkingLot.VehicleType.TRUCK, 30), 1e-9);
    }

    void testLongStayAcrossDays() {
        assertEquals(2.0 * 48, feeFor(ParkingLot.VehicleType.CAR, 48 * 60), 1e-9);
    }

    // ---- concurrency -----------------------------------------------------------------------------
    void testConcurrentParkingNeverDoubleAllocatesASpot() throws Exception {
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Optional<ParkingLot.Ticket>>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) { final int n = i; futures.add(pool.submit(() -> l.park(car("RACE-" + n), T0))); }
        Set<String> spots = new HashSet<>();
        int winners = 0;
        for (Future<Optional<ParkingLot.Ticket>> f : futures) {
            Optional<ParkingLot.Ticket> t = f.get();
            if (t.isPresent()) { winners++; spots.add(t.get().spot().id()); }
        }
        pool.shutdown();
        assertEquals(3, winners);
        assertEquals(3, spots.size(), "every winner must hold a distinct spot");
        assertEquals(0, l.available());
    }

    void testParkUnparkChurnNeverOversubscribes() throws Exception {
        int capacity = 4;
        ParkingLot l = lot(floor(0, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT, ParkingLot.SpotType.COMPACT));
        AtomicInteger inUse = new AtomicInteger(), maxSeen = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < 300; i++) {
                    Optional<ParkingLot.Ticket> ticket = l.park(car("T" + id + "-" + i), T0);
                    if (ticket.isEmpty()) continue;
                    maxSeen.accumulateAndGet(inUse.incrementAndGet(), Math::max);
                    inUse.decrementAndGet();
                    l.unpark(ticket.get().id(), T0.plusSeconds(60));
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        assertTrue(maxSeen.get() <= capacity, "more cars parked at once than there are spots: " + maxSeen.get());
        assertEquals(capacity, l.available());
    }

    public static void main(String[] args) throws Exception { System.exit(run(ParkingLotTest.class) == 0 ? 0 : 1); }
}
