public class TicTacToeTest extends MiniTest {
    static TicTacToe.Game game(int n) {
        return new TicTacToe.Game(n, new TicTacToe.Player("Ann", TicTacToe.Mark.X), new TicTacToe.Player("Ben", TicTacToe.Mark.O));
    }
    /** Plays the moves in order and returns the status after the last one. */
    static TicTacToe.Status play(TicTacToe.Game g, int[]... moves) {
        TicTacToe.Status s = null;
        for (int[] m : moves) s = g.move(m[0], m[1]);
        return s;
    }
    static int[] at(int r, int c) { return new int[]{r, c}; }

    void testXMovesFirstAndPlayersAlternate() {
        TicTacToe.Game g = game(3);
        assertEquals("Ann", g.current().name);
        g.move(0, 0);
        assertEquals("Ben", g.current().name);
        g.move(1, 1);
        assertEquals("Ann", g.current().name);
    }

    void testRowWin() {
        TicTacToe.Game g = game(3);
        assertEquals(TicTacToe.Status.WIN, play(g, at(0, 0), at(1, 0), at(0, 1), at(1, 1), at(0, 2)));
        assertEquals("Ann", g.winner().name);
    }

    void testColumnWin() {
        TicTacToe.Game g = game(3);
        assertEquals(TicTacToe.Status.WIN, play(g, at(0, 2), at(0, 0), at(1, 2), at(1, 1), at(2, 2)));
    }

    void testMainDiagonalWin() {
        TicTacToe.Game g = game(3);
        assertEquals(TicTacToe.Status.WIN, play(g, at(0, 0), at(0, 1), at(1, 1), at(0, 2), at(2, 2)));
    }

    void testAntiDiagonalWin() {
        TicTacToe.Game g = game(3);
        assertEquals(TicTacToe.Status.WIN, play(g, at(0, 2), at(0, 0), at(1, 1), at(0, 1), at(2, 0)));
    }

    void testSecondPlayerCanWin() {
        TicTacToe.Game g = game(3);
        assertEquals(TicTacToe.Status.WIN, play(g, at(0, 0), at(1, 0), at(0, 1), at(1, 1), at(2, 2), at(1, 2)));
        assertEquals("Ben", g.winner().name);
    }

    void testWinOnTheVeryLastCellIsAWinNotADraw() {
        // The ninth and final move fills the board AND completes column 2 for X, so the result must be WIN.
        //   X O X
        //   O O X
        //   O X X
        TicTacToe.Game g = game(3);
        TicTacToe.Status s = play(g,
            at(0, 0), at(0, 1), at(0, 2), at(1, 0),
            at(1, 2), at(1, 1), at(2, 1), at(2, 0),
            at(2, 2));
        assertTrue(g.board.full());
        assertEquals(TicTacToe.Status.WIN, s);
        assertEquals("Ann", g.winner().name);
    }

    void testFullBoardWithoutALineIsADraw() {
        TicTacToe.Game g = game(3);
        assertEquals(TicTacToe.Status.DRAW,
            play(g, at(0, 0), at(0, 1), at(0, 2), at(1, 1), at(1, 0), at(1, 2), at(2, 1), at(2, 0), at(2, 2)));
        assertNull(g.winner());
    }

    void testStatusStaysInProgressBeforeTheEnd() {
        TicTacToe.Game g = game(3);
        assertEquals(TicTacToe.Status.IN_PROGRESS, g.move(1, 1));
        assertEquals(TicTacToe.Status.IN_PROGRESS, g.move(0, 0));
    }

    void testNoMovesAfterTheGameIsOver() {
        TicTacToe.Game g = game(3);
        play(g, at(0, 0), at(1, 0), at(0, 1), at(1, 1), at(0, 2));
        assertContains(assertThrows(IllegalStateException.class, () -> g.move(2, 2)).getMessage(), "over");
    }

    void testOccupiedCellIsRejectedAndTheTurnIsNotLost() {
        TicTacToe.Game g = game(3);
        g.move(0, 0);
        assertContains(assertThrows(IllegalStateException.class, () -> g.move(0, 0)).getMessage(), "taken");
        assertEquals("Ben", g.current().name);
    }

    void testOffBoardMovesAreRejected() {
        TicTacToe.Game g = game(3);
        assertThrows(IllegalArgumentException.class, () -> g.move(3, 0));
        assertThrows(IllegalArgumentException.class, () -> g.move(0, 3));
        assertThrows(IllegalArgumentException.class, () -> g.move(-1, 0));
        assertEquals("Ann", g.current().name);
    }

    void testMixedMarksNeverWinALine() {
        TicTacToe.Game g = game(3);
        // Row 0 ends up X O X: the +1 / -1 counters cancel and must not read as a win.
        assertEquals(TicTacToe.Status.IN_PROGRESS, play(g, at(0, 0), at(0, 1), at(0, 2)));
    }

    void testLargerBoardNeedsAFullLine() {
        TicTacToe.Game g = game(5);
        TicTacToe.Status s = play(g, at(0, 0), at(1, 0), at(0, 1), at(1, 1), at(0, 2), at(1, 2), at(0, 3), at(1, 3));
        assertEquals(TicTacToe.Status.IN_PROGRESS, s, "four in a row is not enough on a 5x5 board");
        assertEquals(TicTacToe.Status.WIN, g.move(0, 4));
    }

    void testOneByOneBoardIsWonByTheFirstMove() {
        TicTacToe.Game g = game(1);
        assertEquals(TicTacToe.Status.WIN, g.move(0, 0));
    }

    void testCenterCellCountsForBothDiagonals() {
        TicTacToe.Game g = game(3);
        // Both diagonals run through (1,1); X owns both ends of the anti-diagonal and the centre.
        assertEquals(TicTacToe.Status.WIN, play(g, at(2, 0), at(0, 0), at(1, 1), at(0, 1), at(0, 2)));
    }

    void testBoardRendering() {
        TicTacToe.Game g = game(3);
        g.move(0, 0); g.move(1, 1);
        assertEquals("X . . \n. O . \n. . . \n", g.board.toString());
    }

    public static void main(String[] args) throws Exception { System.exit(run(TicTacToeTest.class) == 0 ? 0 : 1); }
}
