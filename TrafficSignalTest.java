public class TrafficSignalTest extends MiniTest {
    static TrafficSignal.Intersection crossing() {
        TrafficSignal.Intersection x = new TrafficSignal.Intersection();
        x.addGroup("N-S", "North", "South");
        x.addGroup("E-W", "East", "West");
        return x;
    }
    /** Advances the intersection and returns the snapshot after the last tick. */
    static String tick(TrafficSignal.Intersection x, int times) {
        for (int i = 0; i < times - 1; i++) x.tick();
        x.tick();
        return x.snapshot();
    }

    // ---- a single signal -------------------------------------------------------------------------
    void testSignalStartsRedAndStaysRedUntilTold() {
        TrafficSignal.Signal s = new TrafficSignal.Signal("N");
        assertEquals(TrafficSignal.Color.RED, s.color());
        for (int i = 0; i < 50; i++) s.tick();
        assertEquals(TrafficSignal.Color.RED, s.color());
    }

    void testGreenLastsThreeTicksThenYellowThenRed() {
        TrafficSignal.Signal s = new TrafficSignal.Signal("N");
        s.turnGreen();
        s.tick(); s.tick();
        assertEquals(TrafficSignal.Color.GREEN, s.color());
        s.tick();
        assertEquals(TrafficSignal.Color.YELLOW, s.color());
        s.tick();
        assertEquals(TrafficSignal.Color.RED, s.color());
    }

    void testOnlyARedSignalCanTurnGreen() {
        TrafficSignal.Signal s = new TrafficSignal.Signal("N");
        s.turnGreen();
        assertThrows(IllegalStateException.class, s::turnGreen);
        s.forceYellow();
        assertThrows(IllegalStateException.class, s::turnGreen);
    }

    void testForceYellowOnlyAffectsAGreenSignal() {
        TrafficSignal.Signal red = new TrafficSignal.Signal("N");
        red.forceYellow();
        assertEquals(TrafficSignal.Color.RED, red.color());
        TrafficSignal.Signal green = new TrafficSignal.Signal("S");
        green.turnGreen();
        green.forceYellow();
        assertEquals(TrafficSignal.Color.YELLOW, green.color());
    }

    void testExtendGreenRestartsTheTimer() {
        TrafficSignal.Signal s = new TrafficSignal.Signal("N");
        s.turnGreen();
        s.tick(); s.tick();
        s.extendGreen();
        s.tick(); s.tick();
        assertEquals(TrafficSignal.Color.GREEN, s.color());
        s.tick();
        assertEquals(TrafficSignal.Color.YELLOW, s.color());
    }

    void testExtendGreenIgnoresOtherColours() {
        TrafficSignal.Signal s = new TrafficSignal.Signal("N");
        s.extendGreen();
        assertEquals(TrafficSignal.Color.RED, s.color());
    }

    // ---- the intersection ------------------------------------------------------------------------
    void testFirstGroupGoesGreenOnTheFirstTick() {
        assertContains(tick(crossing(), 1), "N-S:GREEN  E-W:RED");
    }

    void testFullCycleFollowsGreenYellowAllRedThenTheOtherDirection() {
        TrafficSignal.Intersection x = crossing();
        String[] expected = {
            "N-S:GREEN  E-W:RED", "N-S:GREEN  E-W:RED", "N-S:GREEN  E-W:RED",   // t=1..3
            "N-S:YELLOW  E-W:RED",                                               // t=4
            "N-S:RED  E-W:RED",                                                  // t=5 all-red clearance
            "N-S:RED  E-W:GREEN", "N-S:RED  E-W:GREEN", "N-S:RED  E-W:GREEN",    // t=6..8
            "N-S:RED  E-W:YELLOW",                                               // t=9
            "N-S:RED  E-W:RED",                                                  // t=10
            "N-S:GREEN  E-W:RED"                                                 // t=11: back to N-S
        };
        for (int t = 0; t < expected.length; t++) { x.tick(); assertContains(x.snapshot(), expected[t]); }
    }

    void testSnapshotFormat() {
        assertTrue(tick(crossing(), 12).startsWith("t=12 "));
    }

    void testNeverTwoConflictingDirectionsAtOnceOverALongRun() {
        TrafficSignal.Intersection x = crossing();
        for (int t = 1; t <= 500; t++) {
            if (t % 7 == 0) x.emergency("N-S");
            if (t % 11 == 0) x.emergency("E-W");
            x.tick();                                          // tick() asserts the invariant and throws if it is broken
        }
    }

    void testThreeWayIntersectionRotatesThroughEveryGroup() {
        TrafficSignal.Intersection x = new TrafficSignal.Intersection();
        x.addGroup("A", "a"); x.addGroup("B", "b"); x.addGroup("C", "c");
        java.util.Set<String> served = new java.util.HashSet<>();
        for (int t = 0; t < 40; t++) {
            x.tick();
            String s = x.snapshot();
            for (String g : new String[]{"A", "B", "C"}) if (s.contains(g + ":GREEN")) served.add(g);
        }
        assertEquals(3, served.size());
    }

    // ---- emergency vehicles ----------------------------------------------------------------------
    void testEmergencyOnAnotherGroupEndsTheCurrentPhaseEarly() {
        TrafficSignal.Intersection x = crossing();
        tick(x, 2);                                            // N-S green for 2 ticks
        x.emergency("E-W");
        x.tick();
        assertContains(x.snapshot(), "N-S:RED  E-W:RED");
        x.tick();
        assertContains(x.snapshot(), "N-S:RED  E-W:GREEN");    // served before the normal 3-tick green would have finished
    }

    void testEmergencyOnTheGreenGroupHoldsItsGreen() {
        TrafficSignal.Intersection x = crossing();
        tick(x, 2);
        x.emergency("N-S");
        x.tick(); x.tick();                                    // t=3, t=4: would have been yellow at t=4 without the emergency
        assertContains(x.snapshot(), "N-S:GREEN");
        x.tick();
        assertContains(x.snapshot(), "N-S:YELLOW");
    }

    void testEmergencyBeforeTheFirstTickIsServedFirst() {
        TrafficSignal.Intersection x = crossing();
        x.emergency("E-W");
        assertContains(tick(x, 1), "N-S:RED  E-W:GREEN");
    }

    void testEmergencyForAnUnknownGroupIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> crossing().emergency("diagonal"));
    }

    void testRotationResumesNormallyAfterAnEmergency() {
        TrafficSignal.Intersection x = crossing();
        tick(x, 2);
        x.emergency("E-W");
        for (int i = 0; i < 30; i++) x.tick();                 // must keep cycling without error
        String s = x.snapshot();
        assertTrue(s.contains("GREEN") || s.contains("YELLOW") || s.contains("RED"));
    }

    public static void main(String[] args) throws Exception { System.exit(run(TrafficSignalTest.class) == 0 ? 0 : 1); }
}
