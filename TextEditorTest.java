public class TextEditorTest extends MiniTest {
    static TextEditor.Editor editor() { return new TextEditor.Editor(100); }
    static void typeKeys(TextEditor.Editor e, int from, String s) { for (int i = 0; i < s.length(); i++) e.type(from + i, String.valueOf(s.charAt(i))); }

    // ---- document --------------------------------------------------------------------------------
    void testDocumentInsertDeleteAndSearch() {
        TextEditor.Document d = new TextEditor.Document();
        d.insert(0, "hello world");
        assertEquals(11, d.length());
        assertEquals(6, d.indexOf("world", 0));
        assertEquals(-1, d.indexOf("zzz", 0));
        assertEquals("lo w", d.delete(3, 4));
        assertEquals("helorld", d.text());
    }

    // ---- basic editing and undo/redo -------------------------------------------------------------
    void testTypeInsertsAtThePosition() {
        TextEditor.Editor e = editor();
        e.type(0, "ac");
        e.breakTypingRun();
        e.type(1, "b");
        assertEquals("abc", e.text());
    }

    void testUndoAndRedoOnAnEmptyHistoryDoNothing() {
        TextEditor.Editor e = editor();
        assertFalse(e.undo());
        assertFalse(e.redo());
        assertEquals("", e.text());
    }

    void testUndoRemovesTheLastEditAndRedoBringsItBack() {
        TextEditor.Editor e = editor();
        e.type(0, "abc");
        e.breakTypingRun();
        e.type(3, "def");
        assertTrue(e.undo());
        assertEquals("abc", e.text());
        assertTrue(e.redo());
        assertEquals("abcdef", e.text());
        assertFalse(e.redo(), "nothing left to redo");
    }

    void testUndoAllTheWayBackToEmpty() {
        TextEditor.Editor e = editor();
        for (String s : new String[]{"a", "b", "c"}) { e.type(e.text().length(), s); e.breakTypingRun(); }
        while (e.undo()) { }
        assertEquals("", e.text());
        assertEquals(0, e.undoDepth());
    }

    void testANewEditClearsTheRedoHistory() {
        TextEditor.Editor e = editor();
        e.type(0, "abc"); e.breakTypingRun();
        e.type(3, "def");
        e.undo();
        e.type(3, "X");
        assertFalse(e.redo());
        assertEquals("abcX", e.text());
    }

    void testUndoOfADeleteRestoresTextInTheMiddle() {
        TextEditor.Editor e = editor();
        e.type(0, "abcdef"); e.breakTypingRun();
        e.delete(2, 2);
        assertEquals("abef", e.text());
        e.undo();
        assertEquals("abcdef", e.text());
    }

    void testMultipleUndoRedoRoundTripsAreStable() {
        TextEditor.Editor e = editor();
        e.type(0, "one"); e.breakTypingRun();
        e.type(3, " two"); e.breakTypingRun();
        e.delete(0, 4);
        String finalText = e.text();
        for (int i = 0; i < 3; i++) e.undo();
        assertEquals("", e.text());
        for (int i = 0; i < 3; i++) e.redo();
        assertEquals(finalText, e.text());
    }

    // ---- merging ---------------------------------------------------------------------------------
    void testConsecutiveTypingMergesIntoOneUndoStep() {
        TextEditor.Editor e = editor();
        typeKeys(e, 0, "Hello");
        assertEquals(1, e.undoDepth());
        e.undo();
        assertEquals("", e.text());
    }

    void testTypingAwayFromTheCursorDoesNotMerge() {
        TextEditor.Editor e = editor();
        e.type(0, "ab");
        e.type(0, "X");                                          // not adjacent to the end of the previous insert
        assertEquals(2, e.undoDepth());
        assertEquals("Xab", e.text());
    }

    void testBreakTypingRunStartsANewStep() {
        TextEditor.Editor e = editor();
        typeKeys(e, 0, "Hello");
        e.breakTypingRun();
        e.type(5, " world");
        assertEquals(2, e.undoDepth());
        e.undo();
        assertEquals("Hello", e.text());
    }

    void testTypingAfterUndoStartsANewStepInsteadOfMergingIntoOldHistory() {
        TextEditor.Editor e = editor();
        typeKeys(e, 0, "abc");
        e.undo();
        typeKeys(e, 0, "xy");
        assertEquals(1, e.undoDepth());
        e.undo();
        assertEquals("", e.text());
    }

    void testBackspaceChainMergesAndUndoRestoresEverything() {
        TextEditor.Editor e = editor();
        e.type(0, "abcdef"); e.breakTypingRun();
        e.delete(5, 1); e.delete(4, 1); e.delete(3, 1);
        assertEquals("abc", e.text());
        assertEquals(2, e.undoDepth());
        e.undo();
        assertEquals("abcdef", e.text());
    }

    void testForwardDeleteChainMerges() {
        TextEditor.Editor e = editor();
        e.type(0, "abcdef"); e.breakTypingRun();
        e.delete(2, 1); e.delete(2, 1);
        assertEquals("abef", e.text());
        assertEquals(2, e.undoDepth());
        e.undo();
        assertEquals("abcdef", e.text());
    }

    void testNonAdjacentDeletesStaySeparate() {
        TextEditor.Editor e = editor();
        e.type(0, "abcdef"); e.breakTypingRun();
        e.delete(0, 1); e.delete(3, 1);
        assertEquals(3, e.undoDepth());
    }

    void testTypingDoesNotMergeIntoADeleteOrViceVersa() {
        TextEditor.Editor e = editor();
        e.type(0, "abc"); e.breakTypingRun();
        e.delete(2, 1);
        e.type(2, "Z");
        assertEquals(3, e.undoDepth());
        assertEquals("abZ", e.text());
    }

    // ---- invalid edits ---------------------------------------------------------------------------
    void testDeleteOutOfRangeIsRejectedAndLeavesNoTrace() {
        TextEditor.Editor e = editor();
        e.type(0, "abc"); e.breakTypingRun();
        assertThrows(IllegalArgumentException.class, () -> e.delete(2, 5));
        assertThrows(IllegalArgumentException.class, () -> e.delete(-1, 1));
        assertEquals("abc", e.text());
        assertEquals(1, e.undoDepth(), "a failed edit must not appear in the history");
    }

    void testInsertOutOfRangeIsRejectedAndLeavesNoTrace() {
        TextEditor.Editor e = editor();
        e.type(0, "abc"); e.breakTypingRun();
        assertThrows(IndexOutOfBoundsException.class, () -> e.type(10, "x"));
        assertEquals("abc", e.text());
        assertEquals(1, e.undoDepth());
    }

    // ---- replace all -----------------------------------------------------------------------------
    void testReplaceAllReplacesEveryOccurrenceAndCountsThem() {
        TextEditor.Editor e = editor();
        e.type(0, "hello hello world hello");
        assertEquals(3, e.replaceAll("hello", "bye"));
        assertEquals("bye bye world bye", e.text());
    }

    void testReplaceAllIsOneUndoStep() {
        TextEditor.Editor e = editor();
        e.type(0, "a-b-c-d"); e.breakTypingRun();
        int before = e.undoDepth();
        e.replaceAll("-", "+++");
        assertEquals(before + 1, e.undoDepth());
        e.undo();
        assertEquals("a-b-c-d", e.text());
        e.redo();
        assertEquals("a+++b+++c+++d", e.text());
    }

    void testReplaceAllWithNoMatchChangesNothingAndAddsNoHistory() {
        TextEditor.Editor e = editor();
        e.type(0, "abc"); e.breakTypingRun();
        assertEquals(0, e.replaceAll("zzz", "y"));
        assertEquals(1, e.undoDepth());
        assertEquals("abc", e.text());
    }

    void testReplaceAllWithDifferentLengthsKeepsPositionsCorrect() {
        TextEditor.Editor e = editor();
        e.type(0, "aXbXXc");
        e.replaceAll("X", "--");
        assertEquals("a--b----c", e.text());
        e.replaceAll("--", "");
        assertEquals("abc", e.text());
    }

    void testReplaceAllDoesNotRescanItsOwnOutput() {
        TextEditor.Editor e = editor();
        e.type(0, "aa");
        e.replaceAll("a", "aa");
        assertEquals("aaaa", e.text());
    }

    void testReplaceAllWithEmptySearchTextIsRejected() {
        TextEditor.Editor e = editor();
        e.type(0, "abc");
        assertThrows(IllegalArgumentException.class, () -> e.replaceAll("", "x"));
        assertEquals("abc", e.text());
    }

    void testTypingAfterReplaceAllIsNotMergedIntoIt() {
        TextEditor.Editor e = editor();
        e.type(0, "cat"); e.breakTypingRun();
        e.replaceAll("cat", "dog");
        e.type(3, "s");
        assertEquals("dogs", e.text());
        e.undo();
        assertEquals("dog", e.text());
    }

    // ---- bounded history -------------------------------------------------------------------------
    void testHistoryIsCappedAndTheOldestStepsAreForgotten() {
        TextEditor.Editor e = new TextEditor.Editor(3);
        for (int i = 0; i < 6; i++) { e.type(e.text().length(), String.valueOf((char) ('a' + i))); e.breakTypingRun(); }
        assertEquals(3, e.undoDepth());
        int undone = 0;
        while (e.undo()) undone++;
        assertEquals(3, undone);
        assertEquals("abc", e.text());
    }

    void testHistoryOfZeroKeepsNothing() {
        TextEditor.Editor e = new TextEditor.Editor(0);
        e.type(0, "abc");
        assertEquals(0, e.undoDepth());
        assertFalse(e.undo());
        assertEquals("abc", e.text());
    }

    public static void main(String[] args) throws Exception { System.exit(run(TextEditorTest.class) == 0 ? 0 : 1); }
}
