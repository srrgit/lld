import java.util.*;

/**
 * Traffic Signal: State pattern per signal (RED -> GREEN -> YELLOW -> RED), and an
 * Intersection that rotates between groups of non-conflicting signals. The invariant
 * "conflicting directions are never green together" is enforced and asserted every tick.
 */
public class TrafficSignal {
    enum Color { RED, GREEN, YELLOW }

    interface SignalState {
        Color color();
        int duration();           // ticks before auto-advance; 0 = waits for an external trigger
        SignalState next();
    }

    static class RedState implements SignalState {
        public Color color() { return Color.RED; }
        public int duration() { return 0; }
        public SignalState next() { return this; }
    }
    static class GreenState implements SignalState {
        public Color color() { return Color.GREEN; }
        public int duration() { return 3; }
        public SignalState next() { return new YellowState(); }
    }
    static class YellowState implements SignalState {
        public Color color() { return Color.YELLOW; }
        public int duration() { return 1; }
        public SignalState next() { return new RedState(); }
    }

    static class Signal {
        final String name;
        private SignalState state = new RedState();
        private int elapsed;
        Signal(String name) { this.name = name; }
        Color color() { return state.color(); }

        void tick() {
            elapsed++;
            if (state.duration() > 0 && elapsed >= state.duration()) { state = state.next(); elapsed = 0; }
        }
        void turnGreen() {
            if (state.color() != Color.RED) throw new IllegalStateException(name + " must be red before turning green");
            state = new GreenState(); elapsed = 0;
        }
        void forceYellow() {
            if (state.color() == Color.GREEN) { state = new YellowState(); elapsed = 0; }
        }
        void extendGreen() {
            if (state.color() == Color.GREEN) elapsed = 0;
        }
    }

    static class Intersection {
        private static final int CLEARANCE_TICKS = 2;   // all-red gap between phases
        private final Map<String, List<Signal>> groups = new LinkedHashMap<>();
        private final List<String> order = new ArrayList<>();
        private int active = -1;
        private int clearTicks = CLEARANCE_TICKS;
        private String forcedNext;                        // emergency vehicle request
        private int time;

        void addGroup(String name, String... signalNames) {
            List<Signal> list = new ArrayList<>();
            for (String s : signalNames) list.add(new Signal(s));
            groups.put(name, list); order.add(name);
        }

        /** Emergency vehicle approaching on `group`: end the current phase early and serve it next. */
        void emergency(String group) {
            if (!groups.containsKey(group)) throw new IllegalArgumentException("No group " + group);
            if (active >= 0 && order.get(active).equals(group)) {   // already being served: hold the green
                groups.get(group).forEach(Signal::extendGreen);
                return;
            }
            forcedNext = group;
            if (active >= 0) for (Signal s : groups.get(order.get(active))) s.forceYellow();
        }

        void tick() {
            time++;
            groups.values().forEach(l -> l.forEach(Signal::tick));
            boolean activeAllRed = active < 0 || groups.get(order.get(active)).stream().allMatch(s -> s.color() == Color.RED);
            if (activeAllRed) {
                if (++clearTicks >= CLEARANCE_TICKS) {
                    active = forcedNext != null ? order.indexOf(forcedNext) : (active + 1) % order.size();
                    forcedNext = null;
                    groups.get(order.get(active)).forEach(Signal::turnGreen);
                    clearTicks = 0;
                }
            }
            assertSafe();
        }

        private void assertSafe() {
            long busy = groups.values().stream()
                .filter(l -> l.stream().anyMatch(s -> s.color() != Color.RED)).count();
            if (busy > 1) throw new AssertionError("Conflicting directions are both moving at t=" + time);
        }

        String snapshot() {
            StringBuilder sb = new StringBuilder(String.format("t=%02d ", time));
            for (var e : groups.entrySet()) sb.append(e.getKey()).append(':').append(e.getValue().get(0).color()).append("  ");
            return sb.toString();
        }
    }

    public static void main(String[] args) {
        Intersection x = new Intersection();
        x.addGroup("N-S", "North", "South");
        x.addGroup("E-W", "East", "West");
        for (int i = 0; i < 12; i++) { x.tick(); System.out.println(x.snapshot()); }
        System.out.println("-- emergency vehicle approaching on E-W while N-S is green --");
        x.emergency("E-W");
        for (int i = 0; i < 4; i++) { x.tick(); System.out.println(x.snapshot()); }
        System.out.println("-- second emergency on E-W, which is already green: hold the green --");
        x.emergency("E-W");
        for (int i = 0; i < 5; i++) { x.tick(); System.out.println(x.snapshot()); }
        System.out.println("Safety invariant held for every tick.");
    }
}
