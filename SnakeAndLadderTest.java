public class SnakeAndLadderTest extends MiniTest {
    static SnakeAndLadder.Game game(SnakeAndLadder.Board b, SnakeAndLadder.Dice d, String... names) {
        return new SnakeAndLadder.Game(b, d, false, names);
    }
    static SnakeAndLadder.Dice dice(int... rolls) { return new SnakeAndLadder.ScriptedDice(rolls); }

    // ---- dice ------------------------------------------------------------------------------------
    void testScriptedDiceRepeatsItsScript() {
        SnakeAndLadder.Dice d = dice(1, 2, 3);
        int[] got = new int[7];
        for (int i = 0; i < got.length; i++) got[i] = d.roll();
        assertEquals(java.util.Arrays.toString(new int[]{1, 2, 3, 1, 2, 3, 1}), java.util.Arrays.toString(got));
    }

    void testRandomDiceStaysInRangeAndIsReproducible() {
        SnakeAndLadder.RandomDice a = new SnakeAndLadder.RandomDice(6, 42), b = new SnakeAndLadder.RandomDice(6, 42);
        for (int i = 0; i < 1000; i++) {
            int x = a.roll();
            assertTrue(x >= 1 && x <= 6, "roll out of range: " + x);
            assertEquals(x, b.roll());
        }
    }

    void testRandomDiceUsesEveryFace() {
        SnakeAndLadder.RandomDice d = new SnakeAndLadder.RandomDice(6, 3);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) seen.add(d.roll());
        assertEquals(6, seen.size());
    }

    // ---- board validation ------------------------------------------------------------------------
    void testResolveReturnsTheJumpTargetOrTheSameCell() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(100);
        b.addJump(4, 25); b.addJump(27, 5);
        assertEquals(25, b.resolve(4));
        assertEquals(5, b.resolve(27));
        assertEquals(50, b.resolve(50));
    }

    void testJumpsMustStayOnTheBoard() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(10);
        assertThrows(IllegalArgumentException.class, () -> b.addJump(5, 11));
        assertThrows(IllegalArgumentException.class, () -> b.addJump(5, 0));
        assertThrows(IllegalArgumentException.class, () -> b.addJump(11, 5));
    }

    void testNothingMayStartOnTheFirstOrLastCell() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(10);
        assertThrows(IllegalArgumentException.class, () -> b.addJump(1, 5));
        assertThrows(IllegalArgumentException.class, () -> b.addJump(10, 5));
    }

    void testJumpToTheSameCellIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SnakeAndLadder.Board(10).addJump(5, 5));
    }

    void testTwoJumpsCannotShareAStartCell() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(20);
        b.addJump(4, 8);
        assertContains(assertThrows(IllegalArgumentException.class, () -> b.addJump(4, 12)).getMessage(), "already has a jump");
    }

    void testChainedJumpsAreRejectedInBothDirections() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(20);
        b.addJump(4, 8);
        assertContains(assertThrows(IllegalArgumentException.class, () -> b.addJump(8, 4)).getMessage(), "Chained");   // starts where another ends
        assertContains(assertThrows(IllegalArgumentException.class, () -> b.addJump(2, 4)).getMessage(), "Chained");   // ends where another starts
    }

    void testJumpMayEndOnTheLastCell() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(10);
        b.addJump(3, 10);
        assertEquals(10, b.resolve(3));
    }

    void testDescribeSaysLadderOrSnake() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(10);
        assertEquals("ladder", b.describe(2, 9));
        assertEquals("snake", b.describe(9, 2));
    }

    // ---- gameplay --------------------------------------------------------------------------------
    void testPlayersTakeTurnsAndTheFirstToTheEndWins() {
        SnakeAndLadder.Game g = game(new SnakeAndLadder.Board(10), dice(5, 1, 5), "Ann", "Ben");
        SnakeAndLadder.Player w = g.play(50);
        assertEquals("Ann", w.name);
        assertEquals(10, w.pos);
        assertEquals(3, g.turns);                               // Ann, Ben, Ann
    }

    void testLadderMovesThePlayerUp() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(10);
        b.addJump(3, 9);
        SnakeAndLadder.Game g = game(b, dice(3, 1), "Ann");
        g.play(1);
        // One turn played: Ann rolled 3 and was lifted to 9. A second turn needs exactly 1 to win.
        assertEquals(10, game(b, dice(3, 1), "Ann").play(2).pos);
    }

    void testSnakeMovesThePlayerDown() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(10);
        b.addJump(6, 2);
        // Without the snake, 6 then 4 would win on turn 2. With it, 6 sends Ann back to 2, so 4 only reaches 6.
        assertNull(game(b, dice(6, 4), "Ann").play(2));
        assertNotNull(game(new SnakeAndLadder.Board(10), dice(6, 4), "Ann").play(2), "control: same rolls win without the snake");
    }

    void testLandingExactlyOnTheLastCellWins() {
        SnakeAndLadder.Game g = game(new SnakeAndLadder.Board(10), dice(4, 6), "Ann");
        SnakeAndLadder.Player w = g.play(10);
        assertEquals(10, w.pos);
        assertEquals(2, g.turns);
    }

    void testOvershootingTheEndMeansStayingPut() {
        SnakeAndLadder.Game g = game(new SnakeAndLadder.Board(10), dice(6, 6, 4), "Ann");
        SnakeAndLadder.Player w = g.play(10);                    // 6, then 12>10 stays at 6, then 4 -> 10
        assertEquals(10, w.pos);
        assertEquals(3, g.turns);
    }

    void testTurnLimitReturnsNullWhenNobodyFinishes() {
        SnakeAndLadder.Game g = game(new SnakeAndLadder.Board(100), dice(1), "Ann", "Ben");
        assertNull(g.play(10));
        assertEquals(11, g.turns, "the counter ticks once more when the loop ends");
    }

    void testZeroTurnLimitPlaysNothing() {
        assertNull(game(new SnakeAndLadder.Board(10), dice(1), "Ann").play(0));
    }

    void testSnakeOnTheLastRollBeforeVictoryCanStillBeEscaped() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(10);
        b.addJump(9, 2);
        SnakeAndLadder.Player w = game(b, dice(9, 6, 2, 6), "Ann").play(20);      // 0->9 snake to 2, then 8, then 10
        assertNotNull(w);
        assertEquals(10, w.pos);
    }

    void testSeededFullGameAlwaysFinishes() {
        SnakeAndLadder.Board b = new SnakeAndLadder.Board(100);
        int[][] js = {{4,25},{13,46},{33,49},{42,63},{50,69},{62,81},{74,92},{27,5},{40,3},{43,18},{54,31},{89,53},{99,41}};
        for (int[] j : js) b.addJump(j[0], j[1]);
        for (long seed = 0; seed < 20; seed++) {
            SnakeAndLadder.Player w = game(b, new SnakeAndLadder.RandomDice(6, seed), "A", "B", "C").play(100_000);
            assertNotNull(w, "seed " + seed + " never finished");
            assertEquals(100, w.pos);
        }
    }

    public static void main(String[] args) throws Exception { System.exit(run(SnakeAndLadderTest.class) == 0 ? 0 : 1); }
}
