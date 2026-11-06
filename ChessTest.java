public class ChessTest extends MiniTest {
    static final Chess.Color WHITE = Chess.Color.WHITE, BLACK = Chess.Color.BLACK;

    static void play(Chess.Game g, String... moves) {
        for (int i = 0; i < moves.length; i += 2) g.move(moves[i], moves[i + 1]);
    }
    static void illegal(Chess.Game g, String from, String to) {
        assertThrows(IllegalArgumentException.class, () -> g.move(from, to));
    }
    /** An empty board where the test places exactly the pieces it needs. */
    static Chess.Game empty(Chess.Color toMove) {
        Chess.Game g = new Chess.Game();
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++) g.b[r][c] = null;
        g.turn = toMove;
        return g;
    }
    static void put(Chess.Game g, String square, Chess.Piece p) { g.b[8 - (square.charAt(1) - '0')][square.charAt(0) - 'a'] = p; }
    static Chess.Piece at(Chess.Game g, String square) { return g.b[8 - (square.charAt(1) - '0')][square.charAt(0) - 'a']; }

    // ---- set-up ----------------------------------------------------------------------------------
    void testStartingPosition() {
        Chess.Game g = new Chess.Game();
        int pieces = 0;
        for (Chess.Piece[] row : g.b) for (Chess.Piece p : row) if (p != null) pieces++;
        assertEquals(32, pieces);
        assertTrue(at(g, "e1") instanceof Chess.King && at(g, "e1").color == WHITE);
        assertTrue(at(g, "d8") instanceof Chess.Queen && at(g, "d8").color == BLACK);
        assertTrue(at(g, "a1") instanceof Chess.Rook && at(g, "g8") instanceof Chess.Knight);
        assertEquals(WHITE, g.turn);
    }

    void testPieceSymbolsAreUpperCaseForWhiteAndLowerCaseForBlack() {
        assertEquals('Q', new Chess.Queen(WHITE).display());
        assertEquals('q', new Chess.Queen(BLACK).display());
        assertEquals('n', new Chess.Knight(BLACK).display());
    }

    // ---- turn order and basic legality -----------------------------------------------------------
    void testWhiteMovesFirstAndTurnsAlternate() {
        Chess.Game g = new Chess.Game();
        illegal(g, "e7", "e5");
        assertEquals("ok", g.move("e2", "e4"));
        assertEquals(BLACK, g.turn);
        illegal(g, "d2", "d4");
        assertEquals("ok", g.move("e7", "e5"));
    }

    void testIllegalMoveDoesNotChangeTheTurnOrTheBoard() {
        Chess.Game g = new Chess.Game();
        illegal(g, "e2", "e5");
        assertEquals(WHITE, g.turn);
        assertTrue(at(g, "e2") instanceof Chess.Pawn);
        assertNull(at(g, "e5"));
    }

    void testMovingFromAnEmptySquareOrToTheSameSquareIsIllegal() {
        Chess.Game g = new Chess.Game();
        illegal(g, "e4", "e5");
        illegal(g, "e2", "e2");
    }

    void testCannotCaptureYourOwnPiece() {
        illegal(new Chess.Game(), "a1", "a2");
        illegal(new Chess.Game(), "d1", "d2");
    }

    // ---- individual pieces -----------------------------------------------------------------------
    void testPawnMovesOneOrTwoFromTheStartRowButOnlyOneAfterwards() {
        Chess.Game g = new Chess.Game();
        assertEquals("ok", g.move("e2", "e3"));
        g.move("a7", "a6");
        illegal(g, "e3", "e5");                                      // no double step once it has moved
        assertEquals("ok", g.move("e3", "e4"));
    }

    void testPawnCannotMoveThreeOrBackwardsOrSideways() {
        Chess.Game g = new Chess.Game();
        illegal(g, "e2", "e5");
        g.move("e2", "e4"); g.move("a7", "a6");
        illegal(g, "e4", "e3");
        illegal(g, "e4", "d4");
    }

    void testPawnIsBlockedStraightAheadAndCannotJump() {
        Chess.Game g = new Chess.Game();
        play(g, "e2", "e4", "e7", "e5");
        illegal(g, "e4", "e5");                                      // blocked by the black pawn
        Chess.Game h = new Chess.Game();
        play(h, "b1", "c3", "a7", "a6");
        illegal(h, "c2", "c4");                                      // path blocked by the knight on c3? c3 is in front of c2
    }

    void testPawnCapturesDiagonallyOnly() {
        Chess.Game g = new Chess.Game();
        illegal(g, "e2", "d3");                                      // diagonal without a victim
        play(g, "e2", "e4", "d7", "d5");
        assertEquals("ok", g.move("e4", "d5"));
        assertTrue(at(g, "d5") instanceof Chess.Pawn && at(g, "d5").color == WHITE);
        assertNull(at(g, "e4"));
    }

    void testKnightJumpsOverPiecesAndOnlyInLShapes() {
        Chess.Game g = new Chess.Game();
        assertEquals("ok", g.move("g1", "f3"));
        illegal(g, "b8", "b6");
        assertEquals("ok", g.move("b8", "c6"));
    }

    void testBishopAndRookAreBlockedByPiecesInTheWay() {
        Chess.Game g = new Chess.Game();
        illegal(g, "f1", "c4");                                      // e2 pawn in the way
        illegal(g, "a1", "a3");                                      // a2 pawn in the way
        play(g, "e2", "e3", "a7", "a6");
        assertEquals("ok", g.move("f1", "c4"));
    }

    void testQueenMovesLikeRookAndBishopButNotLikeAKnight() {
        for (String target : new String[]{"d7", "g4", "b6", "a7"})   // file, rank, diagonal, diagonal (not a1: that checks the king on h8)
            assertEquals("ok", queenGame().move("d4", target));
        for (String target : new String[]{"f5", "e6", "c1"})         // knight-shaped or off-line squares
            illegal(queenGame(), "d4", target);
    }

    static Chess.Game queenGame() {
        Chess.Game g = empty(WHITE);
        put(g, "e1", new Chess.King(WHITE)); put(g, "h8", new Chess.King(BLACK)); put(g, "d4", new Chess.Queen(WHITE));
        return g;
    }

    void testKingMovesOneSquareOnly() {
        Chess.Game g = empty(WHITE);
        put(g, "e4", new Chess.King(WHITE)); put(g, "a8", new Chess.King(BLACK));
        illegal(g, "e4", "e6");
        assertEquals("ok", g.move("e4", "f5"));
    }

    void testCastlingIsNotSupported() {
        Chess.Game g = empty(WHITE);
        put(g, "e1", new Chess.King(WHITE)); put(g, "h1", new Chess.Rook(WHITE)); put(g, "e8", new Chess.King(BLACK));
        illegal(g, "e1", "g1");
    }

    // ---- check, pins, mate, stalemate ------------------------------------------------------------
    void testCheckIsReported() {
        Chess.Game g = new Chess.Game();
        play(g, "e2", "e4", "f7", "f6");
        assertEquals("CHECK on BLACK", g.move("d1", "h5"));
        assertTrue(g.inCheck(BLACK));
        assertFalse(g.inCheck(WHITE));
    }

    void testMustAnswerACheck() {
        Chess.Game g = new Chess.Game();
        play(g, "e2", "e4", "f7", "f6", "d1", "h5");
        illegal(g, "a7", "a6");                                      // does nothing about the check
        assertEquals("ok", g.move("g7", "g6"));                      // blocks it
    }

    void testPinnedPieceCannotMove() {
        Chess.Game g = new Chess.Game();
        play(g, "e2", "e4", "a7", "a6", "f1", "b5");                 // bishop pins the d7 pawn to the king on e8
        illegal(g, "d7", "d6");
        illegal(g, "d7", "d5");
        assertEquals("ok", g.move("b8", "c6"));                      // interposing is fine
    }

    void testKingCannotMoveIntoCheck() {
        Chess.Game g = empty(WHITE);
        put(g, "e1", new Chess.King(WHITE)); put(g, "a2", new Chess.Rook(BLACK)); put(g, "h8", new Chess.King(BLACK));
        illegal(g, "e1", "e2");                                      // rank 2 is covered by the rook
        assertEquals("ok", g.move("e1", "d1"));
    }

    void testFoolsMate() {
        Chess.Game g = new Chess.Game();
        assertEquals("ok", g.move("f2", "f3"));
        assertEquals("ok", g.move("e7", "e5"));
        assertEquals("ok", g.move("g2", "g4"));
        assertEquals("CHECKMATE, BLACK wins", g.move("d8", "h4"));
    }

    void testStalemateIsNotCheckmate() {
        Chess.Game g = empty(WHITE);
        put(g, "f7", new Chess.King(WHITE)); put(g, "g5", new Chess.Queen(WHITE)); put(g, "h8", new Chess.King(BLACK));
        assertEquals("STALEMATE", g.move("g5", "g6"));
    }

    void testHasLegalMoveIsTrueAtTheStart() {
        assertTrue(new Chess.Game().hasLegalMove());
    }

    // ---- promotion -------------------------------------------------------------------------------
    void testWhitePawnPromotesToQueenOnTheLastRank() {
        Chess.Game g = empty(WHITE);
        put(g, "a7", new Chess.Pawn(WHITE)); put(g, "e1", new Chess.King(WHITE)); put(g, "h6", new Chess.King(BLACK));
        assertEquals("ok", g.move("a7", "a8"));
        assertTrue(at(g, "a8") instanceof Chess.Queen && at(g, "a8").color == WHITE);
    }

    void testBlackPawnPromotesToQueenOnTheFirstRank() {
        Chess.Game g = empty(BLACK);
        put(g, "h2", new Chess.Pawn(BLACK)); put(g, "c3", new Chess.King(WHITE)); put(g, "f8", new Chess.King(BLACK));
        g.move("h2", "h1");
        assertTrue(at(g, "h1") instanceof Chess.Queen && at(g, "h1").color == BLACK);
    }

    void testPromotionCanGiveCheck() {
        Chess.Game g = empty(WHITE);
        put(g, "a7", new Chess.Pawn(WHITE)); put(g, "e1", new Chess.King(WHITE)); put(g, "h8", new Chess.King(BLACK));
        assertEquals("CHECK on BLACK", g.move("a7", "a8"));          // new queen sees along the eighth rank
    }

    public static void main(String[] args) throws Exception { System.exit(run(ChessTest.class) == 0 ? 0 : 1); }
}
