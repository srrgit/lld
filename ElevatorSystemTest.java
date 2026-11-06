public class ElevatorSystemTest extends MiniTest {
    static ElevatorSystem.Elevator car(int id) { return new ElevatorSystem.Elevator(id); }

    /** Steps the car until it goes idle and returns how many steps that took. */
    static int stepsUntilIdle(ElevatorSystem.Elevator e) {
        int steps = 0;
        while (!e.idle()) {
            e.step();
            if (++steps > 200) fail("elevator never went idle");
        }
        return steps;
    }

    static void moveTo(ElevatorSystem.Elevator e, int floor) { e.addStop(floor); stepsUntilIdle(e); }

    // ---- a single elevator -----------------------------------------------------------------------
    void testNewElevatorIsIdleOnTheGroundFloor() {
        ElevatorSystem.Elevator e = car(0);
        assertTrue(e.idle());
        assertEquals(0, e.floor);
        assertEquals(ElevatorSystem.Direction.IDLE, e.dir);
    }

    void testRequestForTheCurrentFloorKeepsItIdle() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(0);
        assertTrue(e.idle());
    }

    void testTravelsUpToATargetThenGoesIdle() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(4);
        assertEquals(ElevatorSystem.Direction.UP, e.dir);
        assertEquals(4, stepsUntilIdle(e));
        assertEquals(4, e.floor);
    }

    void testTravelsDownToATarget() {
        ElevatorSystem.Elevator e = car(0);
        moveTo(e, 6);
        e.addStop(2);
        assertEquals(ElevatorSystem.Direction.DOWN, e.dir);
        assertEquals(4, stepsUntilIdle(e));
        assertEquals(2, e.floor);
    }

    void testServesStopsInSweepOrderNotRequestOrder() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(5); e.addStop(3);
        for (int i = 0; i < 3; i++) e.step();
        assertEquals(3, e.floor);
        assertFalse(e.idle(), "still has floor 5 to serve");
        assertEquals(2, stepsUntilIdle(e));
        assertEquals(5, e.floor);
    }

    void testDuplicateRequestsAreHarmless() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(3); e.addStop(3);
        assertEquals(3, stepsUntilIdle(e));
    }

    void testReversesAfterFinishingTheUpwardSweep() {
        ElevatorSystem.Elevator e = car(0);
        moveTo(e, 5);
        e.addStop(7); e.addStop(2);
        assertEquals(7, stepsUntilIdle(e));                    // 5 -> 7 (2 steps), then 7 -> 2 (5 steps)
        assertEquals(2, e.floor);
    }

    void testStopBehindTheCarWaitsForTheCurrentSweep() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(6);
        e.step(); e.step();                                    // now at floor 2 heading up
        e.addStop(1);
        assertEquals(ElevatorSystem.Direction.UP, e.dir, "must not turn around mid-sweep");
        assertEquals(9, stepsUntilIdle(e));                    // 2 -> 6 (4 steps), then 6 -> 1 (5 steps)
        assertEquals(1, e.floor);
    }

    void testCanTopFloorAndGroundFloorInOneTrip() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(9);
        for (int i = 0; i < 4; i++) e.step();
        e.addStop(0);
        stepsUntilIdle(e);
        assertEquals(0, e.floor);
    }

    // ---- dispatch cost ---------------------------------------------------------------------------
    void testIdleCarCostIsTheDistance() {
        ElevatorSystem.Elevator e = car(0);
        assertEquals(5, e.cost(5, ElevatorSystem.Direction.UP));
        assertEquals(0, e.cost(0, ElevatorSystem.Direction.DOWN));
    }

    void testCarHeadingTheSameWayCostsOnlyTheGap() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(9);
        e.step(); e.step();
        assertEquals(4, e.cost(6, ElevatorSystem.Direction.UP));
    }

    void testCarHeadingTheOtherWayMustFinishItsSweepFirst() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(9);
        e.step(); e.step();                                    // at 2, heading to 9
        assertEquals(10, e.cost(6, ElevatorSystem.Direction.DOWN));   // 7 up to 9, then 3 back down
    }

    void testTargetBehindTheCarCostsAFullSweep() {
        ElevatorSystem.Elevator e = car(0);
        e.addStop(9);
        for (int i = 0; i < 4; i++) e.step();                  // at 4
        assertEquals(13, e.cost(1, ElevatorSystem.Direction.UP));     // 5 up to 9, then 8 down
    }

    // ---- dispatcher ------------------------------------------------------------------------------
    void testNearestIdleCarWins() {
        ElevatorSystem.Elevator low = car(0), high = car(1);
        moveTo(high, 8);
        ElevatorSystem.NearestCar strategy = new ElevatorSystem.NearestCar();
        assertSame(high, strategy.pick(java.util.List.of(low, high), 7, ElevatorSystem.Direction.UP));
        assertSame(low, strategy.pick(java.util.List.of(low, high), 1, ElevatorSystem.Direction.UP));
    }

    void testCarAlreadyHeadingThereBeatsAnIdleCarThatIsFarther() {
        ElevatorSystem.Elevator idle = car(0), moving = car(1);
        moving.addStop(9);
        for (int i = 0; i < 3; i++) moving.step();             // at 3, heading up
        assertSame(moving, new ElevatorSystem.NearestCar().pick(java.util.List.of(idle, moving), 5, ElevatorSystem.Direction.UP));
    }

    void testTiesGoToTheFirstCar() {
        ElevatorSystem.Elevator a = car(0), b = car(1);
        assertSame(a, new ElevatorSystem.NearestCar().pick(java.util.List.of(a, b), 4, ElevatorSystem.Direction.UP));
    }

    // ---- the whole system ------------------------------------------------------------------------
    void testSystemRejectsFloorsThatDoNotExist() {
        ElevatorSystem sys = new ElevatorSystem(2, 10, new ElevatorSystem.NearestCar());
        assertThrows(IllegalArgumentException.class, () -> sys.externalRequest(10, ElevatorSystem.Direction.UP));
        assertThrows(IllegalArgumentException.class, () -> sys.externalRequest(-1, ElevatorSystem.Direction.UP));
        assertThrows(IllegalArgumentException.class, () -> sys.internalRequest(0, 10));
        assertFalse(sys.anyMoving());
    }

    void testSystemMovesACarToTheCallingFloor() {
        ElevatorSystem sys = new ElevatorSystem(2, 10, new ElevatorSystem.NearestCar());
        assertFalse(sys.anyMoving());
        sys.externalRequest(5, ElevatorSystem.Direction.UP);
        assertTrue(sys.anyMoving());
        for (int i = 0; i < 4; i++) sys.tick();
        assertTrue(sys.anyMoving(), "5 floors need 5 ticks");
        sys.tick();
        assertFalse(sys.anyMoving());
    }

    void testSystemCanServeSeveralCallsAtOnce() {
        ElevatorSystem sys = new ElevatorSystem(2, 10, new ElevatorSystem.NearestCar());
        sys.externalRequest(5, ElevatorSystem.Direction.UP);
        sys.externalRequest(2, ElevatorSystem.Direction.DOWN);
        sys.internalRequest(0, 8);
        int ticks = 0;
        while (sys.anyMoving() && ticks < 100) { sys.tick(); ticks++; }
        assertTrue(ticks < 100, "system never settled");
    }

    public static void main(String[] args) throws Exception { System.exit(run(ElevatorSystemTest.class) == 0 ? 0 : 1); }
}
