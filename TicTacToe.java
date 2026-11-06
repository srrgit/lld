import java.util.*;

/**
 * Tic-Tac-Toe (NxN): O(1) win detection per move using row/column/diagonal counters.
 * X adds +1 and O adds -1, so a line is won when its counter reaches +n or -n.
 */
public class TicTacToe {
    enum Mark { X, O }
    enum Status { IN_PROGRESS, WIN, DRAW }

    static class Player {
        final String name; final Mark mark;
        Player(String name, Mark mark) { this.name = name; this.mark = mark; }
    }

    static class Board {
        final int n;
        private final Mark[][] cells;
        private final int[] rows, cols;
        private int diag, anti, filled;

        Board(int n) { this.n = n; cells = new Mark[n][n]; rows = new int[n]; cols = new int[n]; }

        /** Places a mark and returns true if it completes a line. */
        boolean place(int r, int c, Mark m) {
            if (r < 0 || r >= n || c < 0 || c >= n) throw new IllegalArgumentException("Off the board: " + r + "," + c);
            if (cells[r][c] != null) throw new IllegalStateException("Cell taken: " + r + "," + c);
            cells[r][c] = m; filled++;
            int d = m == Mark.X ? 1 : -1;
            rows[r] += d; cols[c] += d;
            if (r == c) diag += d;
            if (r + c == n - 1) anti += d;
            return Math.abs(rows[r]) == n || Math.abs(cols[c]) == n || Math.abs(diag) == n || Math.abs(anti) == n;
        }
        boolean full() { return filled == n * n; }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            for (Mark[] row : cells) {
                for (Mark m : row) sb.append(m == null ? '.' : m.name().charAt(0)).append(' ');
                sb.append('\n');
            }
            return sb.toString();
        }
    }

    static class Game {
        final Board board;
        private final Player[] players;
        private int turn;
        private Status status = Status.IN_PROGRESS;
        private Player winner;

        Game(int n, Player a, Player b) { board = new Board(n); players = new Player[]{a, b}; }

        Player current() { return players[turn]; }
        Player winner() { return winner; }

        Status move(int r, int c) {
            if (status != Status.IN_PROGRESS) throw new IllegalStateException("Game is over");
            Player p = current();
            if (board.place(r, c, p.mark)) { status = Status.WIN; winner = p; }
            else if (board.full()) status = Status.DRAW;
            else turn = 1 - turn;
            return status;
        }
    }

    public static void main(String[] args) {
        System.out.println("Game 1: X wins on the diagonal");
        Game g = new Game(3, new Player("Ann", Mark.X), new Player("Ben", Mark.O));
        int[][] moves = {{0,0},{0,1},{1,1},{0,2},{2,2}};
        Status s = null;
        for (int[] m : moves) s = g.move(m[0], m[1]);
        System.out.print(g.board);
        System.out.println(s + ", winner = " + g.winner().name);
        try { g.move(1, 0); } catch (IllegalStateException e) { System.out.println("After game over: " + e.getMessage()); }

        System.out.println("\nGame 2: draw, plus invalid moves");
        Game d = new Game(3, new Player("Ann", Mark.X), new Player("Ben", Mark.O));
        int[][] draw = {{0,0},{0,1},{0,2},{1,1},{1,0},{1,2},{2,1},{2,0},{2,2}};
        d.move(0, 0);
        try { d.move(0, 0); } catch (IllegalStateException e) { System.out.println(e.getMessage()); }
        try { d.move(3, 3); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        for (int i = 1; i < draw.length; i++) s = d.move(draw[i][0], draw[i][1]);
        System.out.print(d.board);
        System.out.println(s);
    }
}
