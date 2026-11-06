import java.util.*;

/**
 * Food Delivery (Swiggy/Zomato style).
 *   - Order is a state machine with an explicit transition table, so illegal jumps are impossible.
 *   - Agent assignment is a Strategy (nearest agent here; least-loaded would be another class).
 *   - Observer: listeners get every state change (this is where push notifications hook in).
 *   - The order stores a PRICE SNAPSHOT (line items with the unit price at order time), so later menu
 *     price changes never alter an existing order.
 *   - A cart may only hold items from one restaurant.
 *   - If no agent is free when cooking starts, the order waits in a queue and is dispatched as soon as an agent frees up.
 */
public class FoodDelivery {
    enum OrderState { PLACED, ACCEPTED, REJECTED, PREPARING, READY_FOR_PICKUP, PICKED_UP, DELIVERED, CANCELLED }

    private static final Map<OrderState, Set<OrderState>> NEXT = new EnumMap<>(OrderState.class);
    static {
        NEXT.put(OrderState.PLACED, EnumSet.of(OrderState.ACCEPTED, OrderState.REJECTED, OrderState.CANCELLED));
        NEXT.put(OrderState.ACCEPTED, EnumSet.of(OrderState.PREPARING, OrderState.CANCELLED));
        NEXT.put(OrderState.PREPARING, EnumSet.of(OrderState.READY_FOR_PICKUP, OrderState.CANCELLED));
        NEXT.put(OrderState.READY_FOR_PICKUP, EnumSet.of(OrderState.PICKED_UP, OrderState.CANCELLED));
        NEXT.put(OrderState.PICKED_UP, EnumSet.of(OrderState.DELIVERED));            // too late to cancel once picked up
        for (OrderState s : List.of(OrderState.REJECTED, OrderState.DELIVERED, OrderState.CANCELLED)) NEXT.put(s, EnumSet.noneOf(OrderState.class));
    }

    record MenuItem(String id, String name, int priceCents) {}
    record LineItem(String name, int unitPriceCents, int qty) {}

    static class Restaurant {
        final String id, name; final double x, y;
        final Map<String, MenuItem> menu = new LinkedHashMap<>(); final Set<String> soldOut = new HashSet<>();
        Restaurant(String id, String name, double x, double y) { this.id = id; this.name = name; this.x = x; this.y = y; }
        Restaurant item(String id, String name, int price) { menu.put(id, new MenuItem(id, name, price)); return this; }
    }

    record Customer(String id, String name) {}

    static class Cart {
        final Customer customer; String restaurantId; final Map<String, Integer> qty = new LinkedHashMap<>();
        Cart(Customer c) { customer = c; }
    }

    static class Agent {
        final String id; double x, y; boolean busy; int completed;
        Agent(String id, double x, double y) { this.id = id; this.x = x; this.y = y; }
    }

    static class Order {
        final String id; final Customer customer; final Restaurant restaurant; final List<LineItem> lines; final int totalCents;
        OrderState state = OrderState.PLACED; Agent agent;
        Order(String id, Customer c, Restaurant r, List<LineItem> lines, int total) { this.id = id; customer = c; restaurant = r; this.lines = lines; totalCents = total; }
    }

    interface AssignmentStrategy { Optional<Agent> pick(List<Agent> freeAgents, Restaurant from); }

    static class NearestAgent implements AssignmentStrategy {
        public Optional<Agent> pick(List<Agent> free, Restaurant r) {
            return free.stream().min(Comparator.comparingDouble(a -> Math.hypot(a.x - r.x, a.y - r.y)));
        }
    }

    interface OrderListener { void onChange(Order o, OrderState from, OrderState to); }

    static class Platform {
        private final Map<String, Restaurant> restaurants = new HashMap<>();
        private final List<Agent> agents = new ArrayList<>();
        private final Deque<Order> awaitingAgent = new ArrayDeque<>();
        private final List<OrderListener> listeners = new ArrayList<>();
        private final AssignmentStrategy strategy; private int seq;

        Platform(AssignmentStrategy s) { strategy = s; }
        void addRestaurant(Restaurant r) { restaurants.put(r.id, r); }
        void addAgent(Agent a) { agents.add(a); }
        void listen(OrderListener l) { listeners.add(l); }

        void addToCart(Cart cart, String restaurantId, String itemId, int qty) {
            Restaurant r = restaurants.get(restaurantId);
            if (r == null || !r.menu.containsKey(itemId)) throw new IllegalArgumentException("Unknown restaurant or item");
            if (cart.restaurantId != null && !cart.restaurantId.equals(restaurantId))
                throw new IllegalStateException("Cart already has items from another restaurant; clear it first");
            if (r.soldOut.contains(itemId)) throw new IllegalStateException(r.menu.get(itemId).name() + " is sold out");
            cart.restaurantId = restaurantId;
            cart.qty.merge(itemId, qty, Integer::sum);
        }

        Order placeOrder(Cart cart) {
            if (cart.qty.isEmpty()) throw new IllegalStateException("Cart is empty");
            Restaurant r = restaurants.get(cart.restaurantId);
            List<LineItem> lines = new ArrayList<>(); int total = 0;
            for (var e : cart.qty.entrySet()) {
                if (r.soldOut.contains(e.getKey())) throw new IllegalStateException(r.menu.get(e.getKey()).name() + " just sold out");
                MenuItem mi = r.menu.get(e.getKey());
                lines.add(new LineItem(mi.name(), mi.priceCents(), e.getValue()));      // snapshot the price now
                total += mi.priceCents() * e.getValue();
            }
            Order o = new Order("O" + (++seq), cart.customer, r, List.copyOf(lines), total);
            cart.qty.clear(); cart.restaurantId = null;
            return o;
        }

        void advance(Order o, OrderState to) {
            if (!NEXT.get(o.state).contains(to)) throw new IllegalStateException("Cannot go " + o.state + " -> " + to);
            if (to == OrderState.PICKED_UP && o.agent == null) throw new IllegalStateException("No delivery agent assigned yet");
            OrderState from = o.state; o.state = to;
            listeners.forEach(l -> l.onChange(o, from, to));
            switch (to) {
                case PREPARING -> assignOrQueue(o);
                case DELIVERED -> { o.agent.busy = false; o.agent.completed++; dispatchPending(); }
                case CANCELLED, REJECTED -> { awaitingAgent.remove(o); if (o.agent != null) { o.agent.busy = false; dispatchPending(); } }
                default -> {}
            }
        }

        private void assignOrQueue(Order o) {
            Optional<Agent> a = strategy.pick(agents.stream().filter(x -> !x.busy).toList(), o.restaurant);
            if (a.isPresent()) { a.get().busy = true; o.agent = a.get(); } else awaitingAgent.addLast(o);
        }

        private void dispatchPending() {
            while (!awaitingAgent.isEmpty()) {
                Order next = awaitingAgent.peekFirst();
                Optional<Agent> a = strategy.pick(agents.stream().filter(x -> !x.busy).toList(), next.restaurant);
                if (a.isEmpty()) return;
                awaitingAgent.pollFirst();
                a.get().busy = true; next.agent = a.get();
            }
        }
    }

    static String money(int cents) { return String.format("$%.2f", cents / 100.0); }

    public static void main(String[] args) {
        Platform p = new Platform(new NearestAgent());
        Restaurant hub = new Restaurant("r1", "Spice Hub", 0, 0).item("bir", "Biryani", 1200).item("nan", "Naan", 300);
        Restaurant cafe = new Restaurant("r2", "Late Cafe", 9, 9).item("lat", "Latte", 450);
        p.addRestaurant(hub); p.addRestaurant(cafe);
        p.addAgent(new Agent("A1", 1, 1)); p.addAgent(new Agent("A2", 5, 5));
        List<String> notifications = new ArrayList<>();
        p.listen((o, from, to) -> { if (to != OrderState.ACCEPTED) notifications.add(o.id + " -> " + o.customer.name() + " is told: " + to); });

        Customer ann = new Customer("c1", "Ann");
        Cart cart = new Cart(ann);
        p.addToCart(cart, "r1", "bir", 2); p.addToCart(cart, "r1", "nan", 3);
        try { p.addToCart(cart, "r2", "lat", 1); } catch (IllegalStateException e) { System.out.println("Mixed cart rejected: " + e.getMessage()); }
        Order o1 = p.placeOrder(cart);
        System.out.println("Order " + o1.id + " total " + money(o1.totalCents));
        hub.menu.put("bir", new MenuItem("bir", "Biryani", 1500));   // price change after the order
        System.out.println("Menu price changed to $15.00, order total still " + money(o1.totalCents));

        for (OrderState s : List.of(OrderState.ACCEPTED, OrderState.PREPARING)) p.advance(o1, s);
        System.out.println("O1 assigned to nearest agent: " + o1.agent.id);

        Order o2 = quickOrder(p, new Customer("c2", "Ben"), hub);
        Order o3 = quickOrder(p, new Customer("c3", "Cy"), hub);
        for (Order o : List.of(o2, o3)) { p.advance(o, OrderState.ACCEPTED); p.advance(o, OrderState.PREPARING); }
        System.out.println("O2 agent: " + o2.agent.id + ", O3 agent: " + (o3.agent == null ? "none free, queued" : o3.agent.id));

        p.advance(o1, OrderState.READY_FOR_PICKUP); p.advance(o1, OrderState.PICKED_UP);
        try { p.advance(o1, OrderState.CANCELLED); } catch (IllegalStateException e) { System.out.println("Cancel after pickup: " + e.getMessage()); }
        p.advance(o3, OrderState.READY_FOR_PICKUP);                 // food is ready, but still no agent for O3
        try { p.advance(o3, OrderState.PICKED_UP); } catch (IllegalStateException e) { System.out.println("Pickup without agent: " + e.getMessage()); }
        p.advance(o1, OrderState.DELIVERED);
        System.out.println("After O1 delivered, queued O3 got agent: " + o3.agent.id);

        hub.soldOut.add("nan");
        try { p.addToCart(new Cart(ann), "r1", "nan", 1); } catch (IllegalStateException e) { System.out.println(e.getMessage()); }
        notifications.forEach(n -> System.out.println("  notify " + n));
    }

    private static Order quickOrder(Platform p, Customer c, Restaurant r) {
        Cart cart = new Cart(c);
        p.addToCart(cart, r.id, "nan", 1);
        return p.placeOrder(cart);
    }
}
