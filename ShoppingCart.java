import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shopping Cart / checkout.
 *   - Discounts are Strategies applied in sequence; each sees the running total, so they stack predictably
 *     and can never push the total below zero.
 *   - Inventory is RESERVED at checkout with a time limit, committed when payment succeeds, and released
 *     when payment fails or the hold expires. Reservation is all-or-nothing and thread-safe.
 *   - The order keeps a snapshot of the product records it was priced with, so later price changes do not touch it.
 * All money is whole cents (long).
 */
public class ShoppingCart {
    record Product(String sku, String name, long priceCents) {}
    record Line(Product product, int qty) { long total() { return product.priceCents() * qty; } }

    // ---- inventory with expiring holds -------------------------------------------------------
    static class Inventory {
        private record Hold(Map<String, Integer> qty, Instant expiry) {}
        private final Map<String, Integer> stock = new HashMap<>();
        private final Map<String, Hold> holds = new HashMap<>();

        synchronized void setStock(String sku, int n) { stock.put(sku, n); }
        synchronized int stock(String sku) { return stock.getOrDefault(sku, 0); }

        private void purgeExpired(Instant now) { holds.values().removeIf(h -> !now.isBefore(h.expiry())); }
        private int held(String sku) { return holds.values().stream().mapToInt(h -> h.qty().getOrDefault(sku, 0)).sum(); }
        synchronized int available(String sku, Instant now) { purgeExpired(now); return stock(sku) - held(sku); }

        synchronized void reserve(String orderId, Map<String, Integer> items, Instant now, Duration ttl) {
            purgeExpired(now);
            for (var e : items.entrySet()) {
                int avail = stock(e.getKey()) - held(e.getKey());
                if (avail < e.getValue()) throw new IllegalStateException("Only " + avail + " of " + e.getKey() + " available");
            }
            holds.put(orderId, new Hold(Map.copyOf(items), now.plus(ttl)));        // all-or-nothing
        }
        synchronized void commit(String orderId, Instant now) {
            Hold h = holds.remove(orderId);
            if (h == null || !now.isBefore(h.expiry())) throw new IllegalStateException("Reservation expired");
            h.qty().forEach((sku, q) -> stock.merge(sku, -q, Integer::sum));
        }
        synchronized void release(String orderId) { holds.remove(orderId); }
    }

    // ---- discounts ---------------------------------------------------------------------------
    interface Discount { String name(); long amountOff(List<Line> lines, long runningTotalCents); }

    static class BuyNGetOneFree implements Discount {
        private final String sku; private final int n;
        BuyNGetOneFree(String sku, int n) { this.sku = sku; this.n = n; }
        public String name() { return "Buy " + n + " get 1 free on " + sku; }
        public long amountOff(List<Line> lines, long running) {
            return lines.stream().filter(l -> l.product().sku().equals(sku)).mapToLong(l -> (long) (l.qty() / (n + 1)) * l.product().priceCents()).sum();
        }
    }
    static class PercentOff implements Discount {
        private final String code; private final double pct;
        PercentOff(String code, double pct) { this.code = code; this.pct = pct; }
        public String name() { return code + " (" + (int) pct + "% off)"; }
        public long amountOff(List<Line> lines, long running) { return Math.round(running * pct / 100.0); }
    }
    static class FlatOff implements Discount {
        private final String code; private final long cents, minTotal;
        FlatOff(String code, long cents, long minTotal) { this.code = code; this.cents = cents; this.minTotal = minTotal; }
        public String name() { return code + " (" + cents / 100 + " off over " + minTotal / 100 + ")"; }
        public long amountOff(List<Line> lines, long running) { return running >= minTotal ? cents : 0; }
    }

    record Applied(String name, long offCents) {}
    record Pricing(long subtotal, List<Applied> applied, long total) {}

    static Pricing price(List<Line> lines, List<Discount> discounts) {
        long subtotal = lines.stream().mapToLong(Line::total).sum(), running = subtotal;
        List<Applied> applied = new ArrayList<>();
        for (Discount d : discounts) {
            long off = Math.min(d.amountOff(lines, running), running);               // never below zero
            if (off > 0) { applied.add(new Applied(d.name(), off)); running -= off; }
        }
        return new Pricing(subtotal, applied, running);
    }

    // ---- cart, orders, service ---------------------------------------------------------------
    enum OrderState { PENDING_PAYMENT, PAID, PAYMENT_FAILED, EXPIRED }

    static class Cart {
        final Map<String, Integer> qty = new LinkedHashMap<>();
        void add(String sku, int n) { if (n <= 0) throw new IllegalArgumentException("Quantity must be positive"); qty.merge(sku, n, Integer::sum); }
        void remove(String sku) { qty.remove(sku); }
        void setQty(String sku, int n) { if (n <= 0) qty.remove(sku); else qty.put(sku, n); }
    }

    static class Order {
        final String id; final List<Line> lines; final Pricing pricing; OrderState state = OrderState.PENDING_PAYMENT;
        Order(String id, List<Line> lines, Pricing pricing) { this.id = id; this.lines = lines; this.pricing = pricing; }
    }

    static class Store {
        private final Map<String, Product> catalog = new HashMap<>();
        private final Map<String, Discount> coupons = new LinkedHashMap<>();
        private final List<Discount> automatic = new ArrayList<>();
        private final Map<String, Order> orders = new HashMap<>();
        final Inventory inventory = new Inventory(); private final AtomicInteger seq = new AtomicInteger();
        private final Duration holdTtl = Duration.ofMinutes(10);

        void addProduct(Product p, int stock) { catalog.put(p.sku(), p); inventory.setStock(p.sku(), stock); }
        void setPrice(String sku, long cents) { Product p = catalog.get(sku); catalog.put(sku, new Product(sku, p.name(), cents)); }
        void addPromotion(Discount d) { automatic.add(d); }
        void addCoupon(String code, Discount d) { coupons.put(code, d); }

        Order startCheckout(Cart cart, List<String> couponCodes, Instant now) {
            if (cart.qty.isEmpty()) throw new IllegalStateException("Cart is empty");
            List<Line> lines = new ArrayList<>();
            for (var e : cart.qty.entrySet()) {
                Product p = catalog.get(e.getKey());
                if (p == null) throw new IllegalArgumentException("Unknown product " + e.getKey());
                lines.add(new Line(p, e.getValue()));                                   // snapshot at today's price
            }
            List<Discount> ds = new ArrayList<>(automatic);
            for (String c : couponCodes) {
                Discount d = coupons.get(c);
                if (d == null) throw new IllegalArgumentException("Invalid coupon " + c);
                ds.add(d);
            }
            Order o = new Order("O" + seq.incrementAndGet(), List.copyOf(lines), price(lines, ds));
            inventory.reserve(o.id, Map.copyOf(cart.qty), now, holdTtl);              // throws if any item is short
            orders.put(o.id, o);
            cart.qty.clear();
            return o;
        }

        Order completePayment(Order o, boolean paymentSucceeded, Instant now) {
            if (o.state != OrderState.PENDING_PAYMENT) throw new IllegalStateException("Order already " + o.state);
            if (!paymentSucceeded) { inventory.release(o.id); o.state = OrderState.PAYMENT_FAILED; return o; }
            try { inventory.commit(o.id, now); o.state = OrderState.PAID; }
            catch (IllegalStateException expired) { o.state = OrderState.EXPIRED; }  // paid too late: refund in real life
            return o;
        }
    }

    static String money(long cents) { return String.format("$%.2f", cents / 100.0); }

    public static void main(String[] args) throws Exception {
        Store store = new Store();
        store.addProduct(new Product("LAP", "Laptop", 100_000), 3);
        store.addProduct(new Product("MSE", "Mouse", 2_500), 10);
        store.addProduct(new Product("KBD", "Keyboard", 7_000), 5);
        store.addPromotion(new BuyNGetOneFree("MSE", 3));
        store.addCoupon("SAVE10", new PercentOff("SAVE10", 10));
        store.addCoupon("FLAT50", new FlatOff("FLAT50", 5_000, 100_000));
        Instant t0 = Instant.parse("2026-11-06T18:00:00Z");

        Cart alice = new Cart();
        alice.add("LAP", 1); alice.add("MSE", 4); alice.add("KBD", 1);
        Order a = store.startCheckout(alice, List.of("SAVE10", "FLAT50"), t0);
        System.out.println("Alice subtotal " + money(a.pricing.subtotal()));
        a.pricing.applied().forEach(d -> System.out.println("  - " + d.name() + ": -" + money(d.offCents())));
        System.out.println("  total " + money(a.pricing.total()));

        store.setPrice("LAP", 90_000);
        System.out.println("Laptop price dropped later; Alice's order total is still " + money(a.pricing.total()));
        try { store.startCheckout(cartOf("X", 1), List.of(), t0); } catch (IllegalArgumentException e) { System.out.println("Unknown SKU: " + e.getMessage()); }

        Cart bob = new Cart(); bob.add("LAP", 3);
        try { store.startCheckout(bob, List.of(), t0.plus(Duration.ofMinutes(1))); } catch (IllegalStateException e) { System.out.println("Bob at +1m: " + e.getMessage() + " (Alice's hold is counted)"); }
        Order b = store.startCheckout(bob, List.of(), t0.plus(Duration.ofMinutes(11)));   // the failed attempt left Bob's cart intact
        System.out.println("Bob at +11m: reserved 3 laptops because Alice's hold expired");
        System.out.println("Alice pays at +12m -> " + store.completePayment(a, true, t0.plus(Duration.ofMinutes(12))).state);
        System.out.println("Bob pays at +13m   -> " + store.completePayment(b, true, t0.plus(Duration.ofMinutes(13))).state + ", laptop stock now " + store.inventory.stock("LAP"));

        Cart cy = cartOf("MSE", 2);
        Order c = store.startCheckout(cy, List.of(), t0.plus(Duration.ofMinutes(20)));
        System.out.println("Cy holds 2 mice, available " + store.inventory.available("MSE", t0.plus(Duration.ofMinutes(20))));
        System.out.println("Cy's card is declined -> " + store.completePayment(c, false, t0.plus(Duration.ofMinutes(21))).state
            + ", available again " + store.inventory.available("MSE", t0.plus(Duration.ofMinutes(21))));

        Inventory inv = new Inventory(); inv.setStock("HOT", 5);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final String id = "ord" + i;
            fs.add(pool.submit(() -> { try { inv.reserve(id, Map.of("HOT", 1), t0, Duration.ofMinutes(10)); wins.incrementAndGet(); } catch (IllegalStateException ignored) {} }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        System.out.println("20 threads reserving 1 of 5 units: successes = " + wins.get());
    }

    private static Cart cartOf(String sku, int n) { Cart c = new Cart(); c.add(sku, n); return c; }
}
