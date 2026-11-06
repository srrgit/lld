import java.util.*;

/**
 * Snake and Ladder: snakes and ladders are modelled uniformly as "jumps" (start -> end).
 * Dice is an interface so tests can script rolls. Rule: you must roll the exact number to land on the last cell.
 */
public class SnakeAndLadder {
    interface Dice { int roll(); }

    static class RandomDice implements Dice {
        private final Random rnd; private final int faces;
        RandomDice(int faces, long seed) { this.faces = faces; this.rnd = new Random(seed); }
        public int roll() { return rnd.nextInt(faces) + 1; }
    }

    static class ScriptedDice implements Dice {
        private final int[] rolls; private int i;
        ScriptedDice(int... rolls) { this.rolls = rolls; }
        public int roll() { return rolls[i++ % rolls.length]; }
    }

    static class Board {
        final int size;
        private final Map<Integer, Integer> jumps = new HashMap<>();
        Board(int size) { this.size = size; }

        void addJump(int from, int to) {
            if (from <= 1 || from >= size || to < 1 || to > size || from == to)
                throw new IllegalArgumentException("Bad jump " + from + "->" + to);
            if (jumps.containsKey(from)) throw new IllegalArgumentException("Cell " + from + " already has a jump");
            if (jumps.containsKey(to) || jumps.containsValue(from))
                throw new IllegalArgumentException("Chained jump at " + from + "->" + to + " is not allowed");
            jumps.put(from, to);
        }
        int resolve(int pos) { return jumps.getOrDefault(pos, pos); }
        String describe(int from, int to) { return to > from ? "ladder" : "snake"; }
    }

    static class Player {
        final String name; int pos = 0;
        Player(String name) { this.name = name; }
    }

    static class Game {
        private final Board board; private final Dice dice; private final Deque<Player> queue = new ArrayDeque<>();
        private final boolean verbose; int turns;

        Game(Board b, Dice d, boolean verbose, String... names) {
            board = b; dice = d; this.verbose = verbose;
            for (String n : names) queue.add(new Player(n));
        }

        Player play(int maxTurns) {
            while (turns++ < maxTurns) {
                Player p = queue.pollFirst();
                int roll = dice.roll(), target = p.pos + roll;
                if (target > board.size) {
                    if (verbose) System.out.println(p.name + " rolls " + roll + ", needs exact roll, stays at " + p.pos);
                } else {
                    int landed = board.resolve(target);
                    if (verbose || landed != target) {
                        String note = landed != target ? " -> " + board.describe(target, landed) + " to " + landed : "";
                        System.out.println(p.name + " rolls " + roll + ", moves to " + target + note);
                    }
                    p.pos = landed;
                }
                if (p.pos == board.size) return p;
                queue.addLast(p);
            }
            return null;
        }
    }

    public static void main(String[] args) {
        System.out.println("Small deterministic game (size 10, exact roll to win):");
        Board small = new Board(10);
        small.addJump(9, 2);
        small.addJump(4, 8);
        Player w = new Game(small, new ScriptedDice(6, 3, 6, 3, 4), true, "Ann", "Ben").play(50);
        System.out.println("Winner: " + w.name);

        System.out.println("\nValidation:");
        try { small.addJump(8, 4); } catch (IllegalArgumentException e) { System.out.println("  " + e.getMessage()); }
        try { small.addJump(4, 6); } catch (IllegalArgumentException e) { System.out.println("  " + e.getMessage()); }

        System.out.println("\nFull 100-cell game with seeded dice (only jumps are printed):");
        Board big = new Board(100);
        int[][] js = {{4,25},{13,46},{33,49},{42,63},{50,69},{62,81},{74,92},{27,5},{40,3},{43,18},{54,31},{89,53},{99,41}};
        for (int[] j : js) big.addJump(j[0], j[1]);
        Game g = new Game(big, new RandomDice(6, 7), false, "Ann", "Ben", "Cy");
        Player winner = g.play(10_000);
        System.out.println("Winner: " + winner.name + " after " + g.turns + " turns");
    }
}
