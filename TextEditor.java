import java.util.*;

/**
 * Text Editor with Undo/Redo: Command pattern.
 *   - Every edit is an object that knows how to execute AND reverse itself.
 *   - Two stacks: undo and redo. A brand-new edit clears the redo stack.
 *   - Consecutive typing (or consecutive deletes) is MERGED into one command, so undoing "hello"
 *     removes the whole word rather than one letter at a time. Real editors also break a merge on a pause or cursor move.
 *   - CompositeCommand groups several edits (replace-all) into ONE undo step.
 *   - History is bounded so memory use cannot grow forever.
 * Alternative: Memento snapshots of the whole text; simpler but costs O(document size) per edit.
 */
public class TextEditor {
    static class Document {
        private final StringBuilder text = new StringBuilder();
        String text() { return text.toString(); }
        void insert(int pos, String s) { text.insert(pos, s); }
        String delete(int pos, int len) { String gone = text.substring(pos, pos + len); text.delete(pos, pos + len); return gone; }
        int length() { return text.length(); }
        int indexOf(String s, int from) { return text.indexOf(s, from); }
    }

    interface Command {
        void execute(Document d);
        void undo(Document d);
        /** Absorb `next` (already executed) into this command; false if they cannot be combined. */
        default boolean merge(Command next) { return false; }
    }

    static class Insert implements Command {
        private int pos; private String text;
        Insert(int pos, String text) { this.pos = pos; this.text = text; }
        public void execute(Document d) { d.insert(pos, text); }
        public void undo(Document d) { d.delete(pos, text.length()); }
        public boolean merge(Command next) {
            if (next instanceof Insert n && n.pos == pos + text.length()) { text += n.text; return true; }   // typed right after
            return false;
        }
    }

    static class Delete implements Command {
        private int pos, len; private String deleted;
        Delete(int pos, int len) { this.pos = pos; this.len = len; }
        public void execute(Document d) {
            if (pos < 0 || pos + len > d.length()) throw new IllegalArgumentException("Delete out of range");
            deleted = d.delete(pos, len);
        }
        public void undo(Document d) { d.insert(pos, deleted); }
        public boolean merge(Command next) {
            if (!(next instanceof Delete n)) return false;
            if (n.pos == pos) { deleted += n.deleted; len += n.len; return true; }                          // forward delete chain
            if (n.pos + n.len == pos) { deleted = n.deleted + deleted; pos = n.pos; len += n.len; return true; } // backspace chain
            return false;
        }
    }

    static class CompositeCommand implements Command {
        private final List<Command> parts;
        CompositeCommand(List<Command> parts) { this.parts = parts; }
        public void execute(Document d) { parts.forEach(c -> c.execute(d)); }
        public void undo(Document d) { for (int i = parts.size() - 1; i >= 0; i--) parts.get(i).undo(d); }
    }

    static class Editor {
        private final Document doc = new Document();
        private final Deque<Command> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
        private final int maxHistory;
        private boolean breakMerge = true;                       // first edit after undo/redo or an explicit break starts a new command

        Editor(int maxHistory) { this.maxHistory = maxHistory; }

        void execute(Command c) {
            c.execute(doc);
            Command top = undoStack.peek();
            if (!breakMerge && top != null && top.merge(c)) { /* absorbed into the previous command */ }
            else {
                undoStack.push(c);
                if (undoStack.size() > maxHistory) undoStack.removeLast();   // drop the oldest step
            }
            breakMerge = false;
            redoStack.clear();                                   // a new edit invalidates the redo history
        }

        void type(int pos, String s) { execute(new Insert(pos, s)); }
        void delete(int pos, int len) { execute(new Delete(pos, len)); }
        void breakTypingRun() { breakMerge = true; }

        /** Replace every occurrence of `find` as ONE undoable step. Works from the end so earlier positions stay valid. */
        int replaceAll(String find, String with) {
            if (find.isEmpty()) throw new IllegalArgumentException("Search text must not be empty");   // "" matches everywhere and never advances
            List<Integer> hits = new ArrayList<>();
            for (int i = doc.indexOf(find, 0); i >= 0; i = doc.indexOf(find, i + find.length())) hits.add(i);
            if (hits.isEmpty()) return 0;
            List<Command> parts = new ArrayList<>();
            for (int i = hits.size() - 1; i >= 0; i--) { parts.add(new Delete(hits.get(i), find.length())); parts.add(new Insert(hits.get(i), with)); }
            breakMerge = true;
            execute(new CompositeCommand(parts));
            breakMerge = true;
            return hits.size();
        }

        boolean undo() {
            if (undoStack.isEmpty()) return false;
            Command c = undoStack.pop(); c.undo(doc); redoStack.push(c); breakMerge = true; return true;
        }
        boolean redo() {
            if (redoStack.isEmpty()) return false;
            Command c = redoStack.pop(); c.execute(doc); undoStack.push(c); breakMerge = true; return true;
        }
        String text() { return doc.text(); }
        int undoDepth() { return undoStack.size(); }
    }

    static void show(String label, Editor e) { System.out.printf("%-34s \"%s\"   (undo steps: %d)%n", label, e.text(), e.undoDepth()); }

    public static void main(String[] args) {
        Editor e = new Editor(100);
        String word = "Hello";
        for (int i = 0; i < word.length(); i++) e.type(i, String.valueOf(word.charAt(i)));
        show("typed H,e,l,l,o one key at a time", e);
        e.breakTypingRun();
        e.type(5, " world");
        show("typed ' world' (after a pause)", e);
        e.undo(); show("undo -> removes ' world' only", e);
        e.undo(); show("undo -> removes all of 'Hello'", e);
        e.redo(); show("redo -> 'Hello' is back", e);
        e.redo(); show("redo -> ' world' is back", e);

        e.undo();
        e.type(5, "!");
        show("new edit after undo", e);
        System.out.println("redo possible after a new edit? " + e.redo() + " (redo history was cleared)");

        e.breakTypingRun();
        e.type(6, " hello hello");
        show("added more text", e);
        int n = e.replaceAll("hello", "bye");
        e.undo();
        e.redo();
        show("replace-all " + n + " hits, then undo+redo", e);
        e.undo(); show("one undo reverts the whole replace-all", e);

        Editor bs = new Editor(100);
        bs.type(0, "abcdef");
        bs.breakTypingRun();
        bs.delete(5, 1); bs.delete(4, 1); bs.delete(3, 1);
        show("three backspaces", bs);
        bs.undo(); show("undo -> all three restored together", bs);

        Editor small = new Editor(3);
        for (int i = 0; i < 6; i++) { small.type(small.text().length(), String.valueOf((char) ('a' + i))); small.breakTypingRun(); }
        int undone = 0; while (small.undo()) undone++;
        System.out.println("History capped at 3: typed 6 separate edits, could undo " + undone + ", text left \"" + small.text() + "\"");
    }
}
