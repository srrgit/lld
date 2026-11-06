import java.util.*;

/**
 * Chess: each piece type implements its own movement rule (polymorphism). The Game adds
 * turn order, path blocking, self-check prevention, promotion (to queen) and
 * checkmate/stalemate detection. Castling and en passant are left out on purpose;
 * in an interview, say where they would plug in (a MoveRule list or special-move handlers).
 */
public class Chess {
    enum Color { WHITE, BLACK; Color other() { return this == WHITE ? BLACK : WHITE; } }

    static abstract class Piece {
        final Color color;
        Piece(Color c) { color = c; }
        /** Geometric rule only; the Game checks turn, own-piece capture and king safety. */
        abstract boolean canMove(Piece[][] b, int fr, int fc, int tr, int tc);
        abstract char symbol();
        char display() { return color == Color.WHITE ? symbol() : Character.toLowerCase(symbol()); }

        static boolean pathClear(Piece[][] b, int fr, int fc, int tr, int tc) {
            int dr = Integer.signum(tr - fr), dc = Integer.signum(tc - fc);
            for (int r = fr + dr, c = fc + dc; r != tr || c != tc; r += dr, c += dc)
                if (b[r][c] != null) return false;
            return true;
        }
    }

    static class Rook extends Piece {
        Rook(Color c) { super(c); }
        boolean canMove(Piece[][] b, int fr, int fc, int tr, int tc) { return (fr == tr || fc == tc) && pathClear(b, fr, fc, tr, tc); }
        char symbol() { return 'R'; }
    }
    static class Bishop extends Piece {
        Bishop(Color c) { super(c); }
        boolean canMove(Piece[][] b, int fr, int fc, int tr, int tc) { return Math.abs(tr - fr) == Math.abs(tc - fc) && pathClear(b, fr, fc, tr, tc); }
        char symbol() { return 'B'; }
    }
    static class Queen extends Piece {
        Queen(Color c) { super(c); }
        boolean canMove(Piece[][] b, int fr, int fc, int tr, int tc) {
            boolean straight = fr == tr || fc == tc, diagonal = Math.abs(tr - fr) == Math.abs(tc - fc);
            return (straight || diagonal) && pathClear(b, fr, fc, tr, tc);
        }
        char symbol() { return 'Q'; }
    }
    static class Knight extends Piece {
        Knight(Color c) { super(c); }
        boolean canMove(Piece[][] b, int fr, int fc, int tr, int tc) {
            int dr = Math.abs(tr - fr), dc = Math.abs(tc - fc);
            return dr * dc == 2;   // (1,2) or (2,1)
        }
        char symbol() { return 'N'; }
    }
    static class King extends Piece {
        King(Color c) { super(c); }
        boolean canMove(Piece[][] b, int fr, int fc, int tr, int tc) { return Math.max(Math.abs(tr - fr), Math.abs(tc - fc)) == 1; }
        char symbol() { return 'K'; }
    }
    static class Pawn extends Piece {
        Pawn(Color c) { super(c); }
        boolean canMove(Piece[][] b, int fr, int fc, int tr, int tc) {
            int dir = color == Color.WHITE ? -1 : 1;           // row 0 is rank 8
            int startRow = color == Color.WHITE ? 6 : 1;
            Piece target = b[tr][tc];
            if (fc == tc && target == null) {
                if (tr - fr == dir) return true;
                return fr == startRow && tr - fr == 2 * dir && b[fr + dir][fc] == null;
            }
            return Math.abs(tc - fc) == 1 && tr - fr == dir && target != null && target.color != color;
        }
        char symbol() { return 'P'; }
    }

    static class Game {
        final Piece[][] b = new Piece[8][8];
        Color turn = Color.WHITE;

        Game() {
            for (Color c : Color.values()) {
                int back = c == Color.WHITE ? 7 : 0, pawns = c == Color.WHITE ? 6 : 1;
                b[back][0] = new Rook(c); b[back][1] = new Knight(c); b[back][2] = new Bishop(c); b[back][3] = new Queen(c);
                b[back][4] = new King(c); b[back][5] = new Bishop(c); b[back][6] = new Knight(c); b[back][7] = new Rook(c);
                for (int col = 0; col < 8; col++) b[pawns][col] = new Pawn(c);
            }
        }

        boolean inCheck(Color c) {
            int kr = -1, kc = -1;
            for (int r = 0; r < 8; r++) for (int col = 0; col < 8; col++)
                if (b[r][col] instanceof King && b[r][col].color == c) { kr = r; kc = col; }
            for (int r = 0; r < 8; r++) for (int col = 0; col < 8; col++) {
                Piece p = b[r][col];
                if (p != null && p.color != c && p.canMove(b, r, col, kr, kc)) return true;
            }
            return false;
        }

        private boolean legal(int fr, int fc, int tr, int tc) {
            Piece p = b[fr][fc];
            if (p == null || p.color != turn || (fr == tr && fc == tc)) return false;
            if (b[tr][tc] != null && b[tr][tc].color == turn) return false;
            if (!p.canMove(b, fr, fc, tr, tc)) return false;
            Piece captured = b[tr][tc];                        // try it, test king safety, undo
            b[tr][tc] = p; b[fr][fc] = null;
            boolean safe = !inCheck(turn);
            b[fr][fc] = p; b[tr][tc] = captured;
            return safe;
        }

        boolean hasLegalMove() {
            for (int fr = 0; fr < 8; fr++) for (int fc = 0; fc < 8; fc++)
                for (int tr = 0; tr < 8; tr++) for (int tc = 0; tc < 8; tc++)
                    if (legal(fr, fc, tr, tc)) return true;
            return false;
        }

        /** Moves like "e2" -> "e4". Returns a status line. */
        String move(String from, String to) {
            int fr = 8 - (from.charAt(1) - '0'), fc = from.charAt(0) - 'a';
            int tr = 8 - (to.charAt(1) - '0'), tc = to.charAt(0) - 'a';
            if (!legal(fr, fc, tr, tc)) throw new IllegalArgumentException("Illegal move " + from + "-" + to + " for " + turn);
            Piece p = b[fr][fc];
            b[tr][tc] = p; b[fr][fc] = null;
            if (p instanceof Pawn && (tr == 0 || tr == 7)) b[tr][tc] = new Queen(p.color);   // auto-promote
            turn = turn.other();
            boolean check = inCheck(turn), moves = hasLegalMove();
            if (!moves) return check ? "CHECKMATE, " + turn.other() + " wins" : "STALEMATE";
            return check ? "CHECK on " + turn : "ok";
        }

        void print() {
            for (int r = 0; r < 8; r++) {
                StringBuilder sb = new StringBuilder((8 - r) + " ");
                for (int c = 0; c < 8; c++) sb.append(b[r][c] == null ? '.' : b[r][c].display()).append(' ');
                System.out.println(sb);
            }
            System.out.println("  a b c d e f g h");
        }
    }

    public static void main(String[] args) {
        Game g = new Game();
        String[][] fools = {{"f2","f3"},{"e7","e5"},{"g2","g4"},{"d8","h4"}};
        for (String[] m : fools) System.out.println(m[0] + "-" + m[1] + ": " + g.move(m[0], m[1]));
        g.print();

        Game h = new Game();
        for (String[] bad : new String[][]{{"a1","a5"},{"e7","e5"},{"e2","e5"}}) {
            try { h.move(bad[0], bad[1]); } catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        }
    }
}
