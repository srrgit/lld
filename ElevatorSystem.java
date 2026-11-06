import java.util.*;

/**
 * Elevator System: each Elevator is a state machine using SCAN scheduling
 * (keep going in the current direction, serve stops on the way, then reverse).
 * A Dispatcher (Strategy) picks which elevator takes an external call.
 */
public class ElevatorSystem {
    enum Direction { UP, DOWN, IDLE }

    static class Elevator {
        final int id;
        int floor = 0;
        Direction dir = Direction.IDLE;
        private final TreeSet<Integer> upStops = new TreeSet<>();
        private final TreeSet<Integer> downStops = new TreeSet<>();

        Elevator(int id) { this.id = id; }

        void addStop(int target) {
            if (target == floor) { System.out.println("  E" + id + " doors open at " + floor); return; }
            if (target > floor) upStops.add(target); else downStops.add(target);
            if (dir == Direction.IDLE) dir = target > floor ? Direction.UP : Direction.DOWN;
        }

        boolean idle() { return dir == Direction.IDLE; }

        /** Cost used by the dispatcher: how many floors until this car could reach `target` going `want`. */
        int cost(int target, Direction want) {
            if (idle()) return Math.abs(floor - target);
            if (dir == Direction.UP && want == Direction.UP && target >= floor) return target - floor;
            if (dir == Direction.DOWN && want == Direction.DOWN && target <= floor) return floor - target;
            // otherwise it must finish its current sweep first
            int far = dir == Direction.UP ? (upStops.isEmpty() ? floor : upStops.last())
                                          : (downStops.isEmpty() ? floor : downStops.first());
            return Math.abs(far - floor) + Math.abs(far - target);
        }

        /** Advance one floor (one simulation tick). */
        void step() {
            if (dir == Direction.IDLE) return;
            floor += dir == Direction.UP ? 1 : -1;
            TreeSet<Integer> current = dir == Direction.UP ? upStops : downStops;
            if (current.remove(floor)) System.out.println("  E" + id + " stops at " + floor + " (doors open)");
            if (current.isEmpty()) {
                // current sweep finished: reverse if the other side has work, else go idle
                TreeSet<Integer> other = dir == Direction.UP ? downStops : upStops;
                if (!other.isEmpty()) dir = dir == Direction.UP ? Direction.DOWN : Direction.UP;
                else dir = Direction.IDLE;
                // stops in the other set that are now "ahead" in the new direction stay valid
            }
        }
    }

    interface DispatchStrategy { Elevator pick(List<Elevator> cars, int floor, Direction want); }

    static class NearestCar implements DispatchStrategy {
        public Elevator pick(List<Elevator> cars, int floor, Direction want) {
            return cars.stream().min(Comparator.comparingInt(e -> e.cost(floor, want))).orElseThrow();
        }
    }

    private final List<Elevator> cars = new ArrayList<>();
    private final DispatchStrategy dispatcher;
    private final int floors;

    ElevatorSystem(int cars, int floors, DispatchStrategy d) {
        for (int i = 0; i < cars; i++) this.cars.add(new Elevator(i));
        this.floors = floors; this.dispatcher = d;
    }

    /** Hall button pressed on `floor` wanting to go `want`. */
    void externalRequest(int floor, Direction want) {
        validate(floor);
        Elevator e = dispatcher.pick(cars, floor, want);
        System.out.println("Call at floor " + floor + " " + want + " -> E" + e.id);
        e.addStop(floor);
    }

    /** Floor button pressed inside a car. */
    void internalRequest(int carId, int target) {
        validate(target);
        System.out.println("E" + carId + " passenger selects " + target);
        cars.get(carId).addStop(target);
    }

    void tick() { for (Elevator e : cars) e.step(); }
    boolean anyMoving() { return cars.stream().anyMatch(e -> !e.idle()); }
    private void validate(int f) { if (f < 0 || f >= floors) throw new IllegalArgumentException("No such floor " + f); }

    public static void main(String[] args) {
        ElevatorSystem sys = new ElevatorSystem(2, 10, new NearestCar());
        sys.externalRequest(5, Direction.UP);
        sys.externalRequest(2, Direction.DOWN);
        for (int t = 1; t <= 3; t++) sys.tick();
        sys.internalRequest(0, 8);
        sys.externalRequest(6, Direction.UP);
        int guard = 0;
        while (sys.anyMoving() && guard++ < 50) sys.tick();
        for (Elevator e : sys.cars) System.out.println("E" + e.id + " final floor " + e.floor + " " + e.dir);
    }
}
