import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RideSharingTest extends MiniTest {
    static RideSharing.Location at(double x, double y) { return new RideSharing.Location(x, y); }
    static RideSharing.Driver car(String id, double x, double y) { return new RideSharing.Driver(id, RideSharing.VehicleType.CAR, at(x, y)); }
    static RideSharing.Driver bike(String id, double x, double y) { return new RideSharing.Driver(id, RideSharing.VehicleType.BIKE, at(x, y)); }
    static RideSharing.Rider rider(String id) { return new RideSharing.Rider(id, id); }
    static List<String> ids(List<RideSharing.Driver> ds) { return ds.stream().map(d -> d.id).toList(); }

    static RideSharing.RideService service(RideSharing.DriverResponder responder, RideSharing.Driver... drivers) {
        RideSharing.RideService s = new RideSharing.RideService(new RideSharing.BasePricing(), responder);
        for (RideSharing.Driver d : drivers) s.addDriver(d);
        return s;
    }
    static RideSharing.Trip request(RideSharing.RideService s, String riderId, RideSharing.VehicleType type) {
        return s.requestRide(rider(riderId), at(0, 0), at(5, 0), type);
    }
    static final RideSharing.DriverResponder ALWAYS = (d, t) -> true, NEVER = (d, t) -> false;

    // ---- small value types -----------------------------------------------------------------------
    void testDistance() {
        assertEquals(5.0, at(0, 0).distanceTo(at(3, 4)), 1e-9);
        assertEquals(0.0, at(2, 2).distanceTo(at(2, 2)), 1e-9);
    }

    void testDriverCanBeReservedOnlyOnceUntilFreed() {
        RideSharing.Driver d = car("d", 0, 0);
        assertTrue(d.tryReserve());
        assertFalse(d.tryReserve());
        assertEquals(RideSharing.DriverStatus.BUSY, d.status());
        d.free();
        assertEquals(RideSharing.DriverStatus.AVAILABLE, d.status());
        assertTrue(d.tryReserve());
    }

    // ---- grid index ------------------------------------------------------------------------------
    void testEmptyIndexFindsNothing() {
        assertEquals(List.of(), new RideSharing.GridIndex(1.0).nearby(at(0, 0), 5));
    }

    void testNearbyReturnsDriversInRadiusNearestFirst() {
        RideSharing.GridIndex g = new RideSharing.GridIndex(1.0);
        for (RideSharing.Driver d : List.of(car("far", 4, 0), car("near", 1, 0), car("mid", 2, 2), car("out", 9, 9))) g.add(d);
        assertEquals(List.of("near", "mid", "far"), ids(g.nearby(at(0, 0), 5)));
    }

    void testRadiusIsInclusive() {
        RideSharing.GridIndex g = new RideSharing.GridIndex(1.0);
        g.add(car("edge", 3, 4));                                  // exactly 5 away
        assertEquals(List.of("edge"), ids(g.nearby(at(0, 0), 5)));
        assertEquals(List.of(), ids(g.nearby(at(0, 0), 4.99)));
    }

    void testNegativeCoordinatesAndCellBoundaries() {
        RideSharing.GridIndex g = new RideSharing.GridIndex(1.0);
        g.add(car("neg", -0.5, -0.5));
        g.add(car("left", 0.9, 0));
        assertEquals(List.of("neg"), ids(g.nearby(at(0.2, 0.2), 1.0)).stream().filter(x -> x.equals("neg")).toList());
        assertEquals(List.of("left"), ids(g.nearby(at(1.1, 0), 0.5)));   // query and driver sit in different cells
    }

    void testRadiusWiderThanOneCellScansEnoughCells() {
        RideSharing.GridIndex g = new RideSharing.GridIndex(1.0);
        g.add(car("d", 3.4, 0));
        assertEquals(List.of("d"), ids(g.nearby(at(0, 0), 3.5)));
    }

    void testMovedDriverIsFoundAtTheNewPlaceOnly() {
        RideSharing.GridIndex g = new RideSharing.GridIndex(1.0);
        RideSharing.Driver d = car("d", 0, 0);
        g.add(d);
        g.move(d, at(20, 20));
        assertEquals(List.of(), ids(g.nearby(at(0, 0), 2)));
        assertEquals(List.of("d"), ids(g.nearby(at(20, 20), 2)));
        assertEquals(at(20, 20), d.location());
    }

    void testMovingWithinTheSameCell() {
        RideSharing.GridIndex g = new RideSharing.GridIndex(1.0);
        RideSharing.Driver d = car("d", 0.1, 0.1);
        g.add(d);
        g.move(d, at(0.8, 0.8));
        assertEquals(List.of("d"), ids(g.nearby(at(0.8, 0.8), 0.1)));
    }

    void testAddingTheSameDriverTwiceDoesNotDuplicateIt() {
        RideSharing.GridIndex g = new RideSharing.GridIndex(1.0);
        RideSharing.Driver d = car("d", 0, 0);
        g.add(d); g.add(d);
        assertEquals(1, g.nearby(at(0, 0), 1).size());
    }

    void testIndexAgreesWithBruteForceAfterManyMoves() {
        Random rnd = new Random(11);
        RideSharing.GridIndex g = new RideSharing.GridIndex(1.5);
        List<RideSharing.Driver> all = new ArrayList<>();
        for (int i = 0; i < 400; i++) { RideSharing.Driver d = car("x" + i, rnd.nextDouble() * 60 - 30, rnd.nextDouble() * 60 - 30); all.add(d); g.add(d); }
        for (int i = 0; i < 300; i++) g.move(all.get(rnd.nextInt(all.size())), at(rnd.nextDouble() * 60 - 30, rnd.nextDouble() * 60 - 30));
        for (int q = 0; q < 300; q++) {
            RideSharing.Location c = at(rnd.nextDouble() * 60 - 30, rnd.nextDouble() * 60 - 30);
            double radius = 0.5 + rnd.nextDouble() * 6;
            Set<RideSharing.Driver> slow = new HashSet<>();
            for (RideSharing.Driver d : all) if (d.location().distanceTo(c) <= radius) slow.add(d);
            assertEquals(slow, new HashSet<>(g.nearby(c, radius)), "query " + q);
        }
    }

    // ---- pricing ---------------------------------------------------------------------------------
    void testBaseFare() {
        assertEquals(1160, new RideSharing.BasePricing().fareCents(5, 12, 1, 1));       // 200 + 120*5 + 30*12
        assertEquals(200, new RideSharing.BasePricing().fareCents(0, 0, 1, 1));
    }

    void testNoSurgeWhenSupplyCoversDemand() {
        RideSharing.PricingStrategy s = new RideSharing.SurgePricing(new RideSharing.BasePricing(), 3.0);
        assertEquals(1160, s.fareCents(5, 12, 2, 2));
        assertEquals(1160, s.fareCents(5, 12, 1, 5));
    }

    void testSurgeGrowsWithTheShortfall() {
        RideSharing.PricingStrategy s = new RideSharing.SurgePricing(new RideSharing.BasePricing(), 3.0);
        assertEquals(1740, s.fareCents(5, 12, 6, 2));                                   // x1.5
    }

    void testSurgeIsCapped() {
        RideSharing.PricingStrategy s = new RideSharing.SurgePricing(new RideSharing.BasePricing(), 3.0);
        assertEquals(3480, s.fareCents(5, 12, 40, 1));
    }

    void testSurgeWithNoDriversAtAllDoesNotDivideByZero() {
        RideSharing.PricingStrategy s = new RideSharing.SurgePricing(new RideSharing.BasePricing(), 3.0);
        assertEquals(2030, s.fareCents(5, 12, 3, 0));                                   // 1160 * (1 + 0.25*3)
    }

    // ---- matching --------------------------------------------------------------------------------
    void testNearestDriverIsMatchedAndBecomesBusy() {
        RideSharing.Driver near = car("near", 1, 1), far = car("far", 3, 3);
        RideSharing.Trip t = request(service(ALWAYS, far, near), "r1", RideSharing.VehicleType.CAR);
        assertEquals(RideSharing.TripState.ACCEPTED, t.state);
        assertSame(near, t.driver);
        assertEquals(RideSharing.DriverStatus.BUSY, near.status());
        assertEquals(RideSharing.DriverStatus.AVAILABLE, far.status());
    }

    void testOnlyTheRequestedVehicleTypeIsConsidered() {
        RideSharing.RideService s = service(ALWAYS, car("c", 2, 2), bike("b", 0.5, 0.5));
        assertEquals("c", request(s, "r1", RideSharing.VehicleType.CAR).driver.id);
        assertEquals("b", request(s, "r2", RideSharing.VehicleType.BIKE).driver.id);
    }

    void testNoDriverWithinTheSearchRadius() {
        RideSharing.Trip t = request(service(ALWAYS, car("far", 20, 20)), "r1", RideSharing.VehicleType.CAR);
        assertEquals(RideSharing.TripState.NO_DRIVER_FOUND, t.state);
        assertNull(t.driver);
    }

    void testDriverExactlyAtTheSearchRadiusIsStillOffered() {
        RideSharing.Trip t = request(service(ALWAYS, car("edge", 3, 4)), "r1", RideSharing.VehicleType.CAR);
        assertEquals(RideSharing.TripState.ACCEPTED, t.state);
    }

    void testNoDriversAtAll() {
        assertEquals(RideSharing.TripState.NO_DRIVER_FOUND, request(service(ALWAYS), "r1", RideSharing.VehicleType.CAR).state);
    }

    void testDeclinedOfferFallsThroughToTheNextDriverAndFreesTheFirst() {
        RideSharing.Driver d1 = car("d1", 1, 1), d2 = car("d2", 2, 2);
        RideSharing.Trip t = request(service((d, trip) -> !d.id.equals("d1"), d1, d2), "r1", RideSharing.VehicleType.CAR);
        assertSame(d2, t.driver);
        assertEquals(List.of("d1"), t.declinedBy);
        assertEquals(RideSharing.DriverStatus.AVAILABLE, d1.status(), "a driver who declined is not left reserved");
    }

    void testEveryoneDecliningEndsWithNoDriverAndNobodyStuckBusy() {
        RideSharing.Driver d1 = car("d1", 1, 1), d2 = car("d2", 2, 2);
        RideSharing.Trip t = request(service(NEVER, d1, d2), "r1", RideSharing.VehicleType.CAR);
        assertEquals(RideSharing.TripState.NO_DRIVER_FOUND, t.state);
        assertEquals(List.of("d1", "d2"), t.declinedBy);
        assertEquals(RideSharing.DriverStatus.AVAILABLE, d1.status());
        assertEquals(RideSharing.DriverStatus.AVAILABLE, d2.status());
    }

    void testAtMostThreeDriversAreOffered() {
        AtomicInteger offers = new AtomicInteger();
        RideSharing.RideService s = service((d, t) -> { offers.incrementAndGet(); return false; },
            car("a", 1, 0), car("b", 2, 0), car("c", 3, 0), car("d", 4, 0), car("e", 5, 0));
        RideSharing.Trip t = request(s, "r1", RideSharing.VehicleType.CAR);
        assertEquals(RideSharing.RideService.MAX_OFFERS, offers.get());
        assertEquals(3, t.declinedBy.size());
        assertEquals(RideSharing.TripState.NO_DRIVER_FOUND, t.state);
    }

    void testBusyDriversAreNotOfferedAnotherRide() {
        RideSharing.RideService s = service(ALWAYS, car("d1", 1, 1), car("d2", 2, 2));
        assertEquals("d1", request(s, "r1", RideSharing.VehicleType.CAR).driver.id);
        assertEquals("d2", request(s, "r2", RideSharing.VehicleType.CAR).driver.id);
        assertEquals(RideSharing.TripState.NO_DRIVER_FOUND, request(s, "r3", RideSharing.VehicleType.CAR).state);
    }

    void testTripIdsAreSequential() {
        RideSharing.RideService s = service(ALWAYS, car("d1", 1, 1));
        assertEquals("T1", request(s, "r1", RideSharing.VehicleType.CAR).id);
        assertEquals("T2", request(s, "r2", RideSharing.VehicleType.CAR).id);
    }

    void testQuoteIsBasedOnTheRouteAndFixedAtRequestTime() {
        RideSharing.RideService s = service(ALWAYS, car("d1", 1, 1));
        RideSharing.Trip t = request(s, "r1", RideSharing.VehicleType.CAR);       // 5 km, 12 min
        assertEquals(1160, t.quoteCents);
        s.start(t);
        assertEquals(1160, s.complete(t));
    }

    void testQuoteIncludesSurgeWhenDriversAreScarce() {
        // One request open, zero free cars of that type nearby: demand 1, supply 0 -> x1.25.
        RideSharing.RideService s = new RideSharing.RideService(new RideSharing.SurgePricing(new RideSharing.BasePricing(), 3.0), ALWAYS);
        RideSharing.Trip t = request(s, "r1", RideSharing.VehicleType.CAR);
        assertEquals(Math.round(1160 * 1.25), t.quoteCents);
    }

    // ---- trip lifecycle --------------------------------------------------------------------------
    void testFullLifecycleMovesTheDriverToTheDropOffAndFreesThem() {
        RideSharing.Driver d = car("d1", 1, 1);
        RideSharing.RideService s = service(ALWAYS, d);
        RideSharing.Trip t = request(s, "r1", RideSharing.VehicleType.CAR);
        s.start(t);
        assertEquals(RideSharing.TripState.STARTED, t.state);
        s.complete(t);
        assertEquals(RideSharing.TripState.COMPLETED, t.state);
        assertEquals(at(5, 0), d.location());
        assertEquals(RideSharing.DriverStatus.AVAILABLE, d.status());
        assertEquals(List.of("d1"), ids(s.index().nearby(at(5, 0), 1)), "the index must know the driver's new position");
    }

    void testStartOnlyWorksFromAccepted() {
        RideSharing.RideService declined = service(NEVER, car("d1", 1, 1));
        RideSharing.Trip none = request(declined, "r1", RideSharing.VehicleType.CAR);       // NO_DRIVER_FOUND
        assertThrows(IllegalStateException.class, () -> declined.start(none));

        RideSharing.RideService s = service(ALWAYS, car("d2", 1, 1));
        RideSharing.Trip t = request(s, "r2", RideSharing.VehicleType.CAR);
        s.start(t);
        assertContains(assertThrows(IllegalStateException.class, () -> s.start(t)).getMessage(), "STARTED");     // not twice
    }

    void testCompleteOnlyWorksFromStarted() {
        RideSharing.RideService s = service(ALWAYS, car("d1", 1, 1));
        RideSharing.Trip t = request(s, "r1", RideSharing.VehicleType.CAR);
        assertThrows(IllegalStateException.class, () -> s.complete(t));               // still only ACCEPTED
        s.start(t);
        s.complete(t);
        assertThrows(IllegalStateException.class, () -> s.complete(t));               // already COMPLETED
    }

    void testCancelBeforeStartFreesTheDriverForOthers() {
        RideSharing.Driver d = car("d1", 1, 1);
        RideSharing.RideService s = service(ALWAYS, d);
        RideSharing.Trip t = request(s, "r1", RideSharing.VehicleType.CAR);
        s.cancel(t);
        assertEquals(RideSharing.TripState.CANCELLED, t.state);
        assertEquals(RideSharing.DriverStatus.AVAILABLE, d.status());
        assertEquals(RideSharing.TripState.ACCEPTED, request(s, "r2", RideSharing.VehicleType.CAR).state);
    }

    void testCancelIsRefusedOnceUnderwayOrFinishedOrAlreadyCancelled() {
        RideSharing.RideService s = service(ALWAYS, car("d1", 1, 1));
        RideSharing.Trip t = request(s, "r1", RideSharing.VehicleType.CAR);
        s.start(t);
        assertContains(assertThrows(IllegalStateException.class, () -> s.cancel(t)).getMessage(), "STARTED");
        s.complete(t);
        assertThrows(IllegalStateException.class, () -> s.cancel(t));

        RideSharing.RideService s2 = service(ALWAYS, car("d2", 1, 1));
        RideSharing.Trip t2 = request(s2, "r2", RideSharing.VehicleType.CAR);
        s2.cancel(t2);
        assertThrows(IllegalStateException.class, () -> s2.cancel(t2));
    }

    void testCancellingATripThatFoundNoDriverIsRefused() {
        RideSharing.RideService s = service(ALWAYS);
        RideSharing.Trip t = request(s, "r1", RideSharing.VehicleType.CAR);
        assertThrows(IllegalStateException.class, () -> s.cancel(t));
    }

    // ---- concurrency -----------------------------------------------------------------------------
    void testTwoRidersRacingForOneDriverNeverBothGetHim() throws Exception {
        for (int round = 0; round < 50; round++) {
            RideSharing.RideService s = service(ALWAYS, car("solo", 0, 0));
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<RideSharing.Trip>> fs = new ArrayList<>();
            for (String id : List.of("rA", "rB")) fs.add(pool.submit(() -> { go.await(); return request(s, id, RideSharing.VehicleType.CAR); }));
            go.countDown();
            int accepted = 0, none = 0;
            for (Future<RideSharing.Trip> f : fs) {
                RideSharing.TripState st = f.get().state;
                if (st == RideSharing.TripState.ACCEPTED) accepted++; else if (st == RideSharing.TripState.NO_DRIVER_FOUND) none++;
            }
            pool.shutdown();
            assertEquals(1, accepted, "round " + round);
            assertEquals(1, none, "round " + round);
        }
    }

    void testManyRidersNeverShareADriver() throws Exception {
        RideSharing.Driver[] drivers = new RideSharing.Driver[10];
        for (int i = 0; i < drivers.length; i++) drivers[i] = car("d" + i, i * 0.1, 0);
        RideSharing.RideService s = service(ALWAYS, drivers);
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<RideSharing.Trip>> fs = new ArrayList<>();
        for (int i = 0; i < 40; i++) { final String id = "r" + i; fs.add(pool.submit(() -> request(s, id, RideSharing.VehicleType.CAR))); }
        Set<String> used = new HashSet<>();
        int accepted = 0;
        for (Future<RideSharing.Trip> f : fs) {
            RideSharing.Trip t = f.get();
            if (t.state == RideSharing.TripState.ACCEPTED) { accepted++; used.add(t.driver.id); }
        }
        pool.shutdown();
        assertTrue(accepted <= drivers.length, "more trips than drivers: " + accepted);
        assertEquals(accepted, used.size(), "a driver was assigned to two trips");
    }

    public static void main(String[] args) throws Exception { System.exit(run(RideSharingTest.class) == 0 ? 0 : 1); }
}
