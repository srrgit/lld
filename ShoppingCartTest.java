import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ShoppingCartTest extends MiniTest {
    static final Instant T0 = Instant.parse("2026-11-06T18:00:00Z");
    static final ShoppingCart.Product LAP = new ShoppingCart.Product("LAP", "Laptop", 100_000),
        MSE = new ShoppingCart.Product("MSE", "Mouse", 2_500);

    static Instant plusMin(long m) { return T0.plus(Duration.ofMinutes(m)); }
    static ShoppingCart.Cart cartOf(Object... skuQty) {
        ShoppingCart.Cart c = new ShoppingCart.Cart();
        for (int i = 0; i < skuQty.length; i += 2) c.add((String) skuQty[i], (Integer) skuQty[i + 1]);
        return c;
    }
    static ShoppingCart.Store store() {
        ShoppingCart.Store s = new ShoppingCart.Store();
        s.addProduct(LAP, 3);
        s.addProduct(MSE, 10);
        return s;
    }

    // ---- pricing and discounts -------------------------------------------------------------------
    void testPriceWithoutDiscountsIsTheSubtotal() {
        ShoppingCart.Pricing p = ShoppingCart.price(List.of(new ShoppingCart.Line(LAP, 2), new ShoppingCart.Line(MSE, 4)), List.of());
        assertEquals(210_000, p.subtotal());
        assertEquals(210_000, p.total());
        assertTrue(p.applied().isEmpty());
    }

    void testEmptyBasketPricesAtZero() {
        assertEquals(0, ShoppingCart.price(List.of(), List.of(new ShoppingCart.PercentOff("X", 10))).total());
    }

    void testBuyNGetOneFreeCountsCompleteGroups() {
        ShoppingCart.Discount d = new ShoppingCart.BuyNGetOneFree("MSE", 3);
        assertEquals(0, ShoppingCart.price(List.of(new ShoppingCart.Line(MSE, 3)), List.of(d)).applied().size());     // 3 is not a full group of 4
        assertEquals(2_500, ShoppingCart.price(List.of(new ShoppingCart.Line(MSE, 4)), List.of(d)).applied().get(0).offCents());
        assertEquals(2_500, ShoppingCart.price(List.of(new ShoppingCart.Line(MSE, 7)), List.of(d)).applied().get(0).offCents());
        assertEquals(5_000, ShoppingCart.price(List.of(new ShoppingCart.Line(MSE, 8)), List.of(d)).applied().get(0).offCents());
    }

    void testBuyNGetOneFreeOnlyAffectsItsOwnSku() {
        ShoppingCart.Pricing p = ShoppingCart.price(List.of(new ShoppingCart.Line(LAP, 4)), List.of(new ShoppingCart.BuyNGetOneFree("MSE", 3)));
        assertEquals(400_000, p.total());
    }

    void testPercentOffRounds() {
        ShoppingCart.Pricing p = ShoppingCart.price(List.of(new ShoppingCart.Line(new ShoppingCart.Product("P", "P", 2_501), 1)), List.of(new ShoppingCart.PercentOff("TEN", 10)));
        assertEquals(250, p.applied().get(0).offCents());
        assertEquals(2_251, p.total());
    }

    void testFlatOffNeedsTheMinimumTotal() {
        ShoppingCart.Discount d = new ShoppingCart.FlatOff("FLAT50", 5_000, 100_000);
        assertEquals(95_000, ShoppingCart.price(List.of(new ShoppingCart.Line(LAP, 1)), List.of(d)).total());
        assertEquals(99_999, ShoppingCart.price(List.of(new ShoppingCart.Line(new ShoppingCart.Product("H", "H", 99_999), 1)), List.of(d)).total(),
            "99,999 is one cent short of the minimum, so no discount");
    }

    void testDiscountsStackAgainstTheRunningTotal() {
        List<ShoppingCart.Line> lines = List.of(new ShoppingCart.Line(LAP, 1));
        ShoppingCart.Discount pct = new ShoppingCart.PercentOff("TEN", 10), flat = new ShoppingCart.FlatOff("FLAT", 5_000, 100_000);
        assertEquals(85_500, ShoppingCart.price(lines, List.of(flat, pct)).total());     // 100,000 - 5,000 = 95,000; then 10% of that
        assertEquals(90_000, ShoppingCart.price(lines, List.of(pct, flat)).total());     // 10% first leaves 90,000, below the flat-off minimum
    }

    void testDiscountsNeverPushTheTotalBelowZero() {
        List<ShoppingCart.Line> lines = List.of(new ShoppingCart.Line(new ShoppingCart.Product("P", "P", 3_000), 1));
        ShoppingCart.Pricing p = ShoppingCart.price(lines, List.of(new ShoppingCart.FlatOff("BIG", 5_000, 0), new ShoppingCart.PercentOff("TEN", 10)));
        assertEquals(0, p.total());
        assertEquals(1, p.applied().size(), "a discount that takes off nothing is not listed");
        assertEquals(3_000, p.applied().get(0).offCents());
    }

    void testDiscountNames() {
        assertEquals("Buy 3 get 1 free on MSE", new ShoppingCart.BuyNGetOneFree("MSE", 3).name());
        assertEquals("SAVE10 (10% off)", new ShoppingCart.PercentOff("SAVE10", 10).name());
    }

    // ---- cart ------------------------------------------------------------------------------------
    void testCartAddsAndMergesQuantities() {
        ShoppingCart.Cart c = cartOf("LAP", 1, "MSE", 2);
        c.add("LAP", 2);
        assertEquals(3, c.qty.get("LAP"));
        assertEquals(2, c.qty.get("MSE"));
    }

    void testCartRejectsNonPositiveQuantities() {
        ShoppingCart.Cart c = new ShoppingCart.Cart();
        assertThrows(IllegalArgumentException.class, () -> c.add("LAP", 0));
        assertThrows(IllegalArgumentException.class, () -> c.add("LAP", -1));
        assertTrue(c.qty.isEmpty());
    }

    void testCartRemoveAndSetQty() {
        ShoppingCart.Cart c = cartOf("LAP", 1, "MSE", 2);
        c.setQty("MSE", 5);
        assertEquals(5, c.qty.get("MSE"));
        c.setQty("MSE", 0);
        assertFalse(c.qty.containsKey("MSE"), "setting the quantity to zero removes the line");
        c.remove("LAP");
        c.remove("LAP");                                         // removing twice is harmless
        assertTrue(c.qty.isEmpty());
    }

    // ---- inventory -------------------------------------------------------------------------------
    void testUnknownSkuHasNoStock() {
        assertEquals(0, new ShoppingCart.Inventory().stock("NOPE"));
    }

    void testReserveReducesAvailabilityButNotStock() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 5);
        inv.reserve("o1", Map.of("A", 2), T0, Duration.ofMinutes(10));
        assertEquals(3, inv.available("A", T0));
        assertEquals(5, inv.stock("A"));
    }

    void testReserveCanTakeExactlyTheRemainingStock() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 2);
        inv.reserve("o1", Map.of("A", 2), T0, Duration.ofMinutes(10));
        assertEquals(0, inv.available("A", T0));
    }

    void testReserveIsAllOrNothing() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 5); inv.setStock("B", 1);
        assertContains(assertThrows(IllegalStateException.class,
            () -> inv.reserve("o1", Map.of("A", 2, "B", 3), T0, Duration.ofMinutes(10))).getMessage(), "Only 1 of B");
        assertEquals(5, inv.available("A", T0), "A must not stay reserved when B was short");
    }

    void testHoldsFromDifferentOrdersAddUp() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 3);
        inv.reserve("o1", Map.of("A", 2), T0, Duration.ofMinutes(10));
        assertThrows(IllegalStateException.class, () -> inv.reserve("o2", Map.of("A", 2), T0, Duration.ofMinutes(10)));
        inv.reserve("o2", Map.of("A", 1), T0, Duration.ofMinutes(10));
    }

    void testHoldExpiresExactlyAtItsDeadline() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 1);
        inv.reserve("o1", Map.of("A", 1), T0, Duration.ofMinutes(10));
        assertEquals(0, inv.available("A", plusMin(9)));
        assertEquals(1, inv.available("A", plusMin(10)));
    }

    void testCommitConsumesStockAndRemovesTheHold() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 5);
        inv.reserve("o1", Map.of("A", 2), T0, Duration.ofMinutes(10));
        inv.commit("o1", plusMin(1));
        assertEquals(3, inv.stock("A"));
        assertEquals(3, inv.available("A", plusMin(1)));
    }

    void testCommitAfterExpiryOrWithoutAReservationFails() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 5);
        inv.reserve("o1", Map.of("A", 2), T0, Duration.ofMinutes(10));
        assertThrows(IllegalStateException.class, () -> inv.commit("o1", plusMin(10)));
        assertThrows(IllegalStateException.class, () -> inv.commit("ghost", T0));
        assertEquals(5, inv.stock("A"));
    }

    void testCommitTwiceFailsTheSecondTime() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 5);
        inv.reserve("o1", Map.of("A", 1), T0, Duration.ofMinutes(10));
        inv.commit("o1", T0);
        assertThrows(IllegalStateException.class, () -> inv.commit("o1", T0));
        assertEquals(4, inv.stock("A"));
    }

    void testReleaseFreesTheHoldAndIsHarmlessWhenRepeated() {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("A", 2);
        inv.reserve("o1", Map.of("A", 2), T0, Duration.ofMinutes(10));
        inv.release("o1"); inv.release("o1"); inv.release("never-existed");
        assertEquals(2, inv.available("A", T0));
    }

    void testConcurrentReservationsNeverOversell() throws Exception {
        ShoppingCart.Inventory inv = new ShoppingCart.Inventory();
        inv.setStock("HOT", 5);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger wins = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            final String id = "o" + i;
            fs.add(pool.submit(() -> { try { inv.reserve(id, Map.of("HOT", 1), T0, Duration.ofMinutes(10)); wins.incrementAndGet(); } catch (IllegalStateException ignored) { } }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertEquals(5, wins.get());
        assertEquals(0, inv.available("HOT", T0));
    }

    // ---- checkout --------------------------------------------------------------------------------
    void testCheckoutPricesReservesAndClearsTheCart() {
        ShoppingCart.Store s = store();
        ShoppingCart.Cart cart = cartOf("LAP", 1, "MSE", 2);
        ShoppingCart.Order o = s.startCheckout(cart, List.of(), T0);
        assertEquals(105_000, o.pricing.total());
        assertEquals(ShoppingCart.OrderState.PENDING_PAYMENT, o.state);
        assertTrue(cart.qty.isEmpty());
        assertEquals(2, s.inventory.available("LAP", T0));
    }

    void testEmptyCartCannotCheckOut() {
        assertContains(assertThrows(IllegalStateException.class, () -> store().startCheckout(new ShoppingCart.Cart(), List.of(), T0)).getMessage(), "empty");
    }

    void testUnknownProductIsRejectedAndLeavesTheCartAlone() {
        ShoppingCart.Cart cart = cartOf("GHOST", 1);
        assertThrows(IllegalArgumentException.class, () -> store().startCheckout(cart, List.of(), T0));
        assertEquals(1, cart.qty.get("GHOST"));
    }

    void testInvalidCouponIsRejectedBeforeAnythingIsReserved() {
        ShoppingCart.Store s = store();
        ShoppingCart.Cart cart = cartOf("LAP", 1);
        assertContains(assertThrows(IllegalArgumentException.class, () -> s.startCheckout(cart, List.of("BOGUS"), T0)).getMessage(), "Invalid coupon");
        assertEquals(3, s.inventory.available("LAP", T0));
        assertEquals(1, cart.qty.get("LAP"));
    }

    void testInsufficientStockKeepsTheCartSoTheCustomerCanRetry() {
        ShoppingCart.Store s = store();
        ShoppingCart.Cart cart = cartOf("LAP", 5);
        assertThrows(IllegalStateException.class, () -> s.startCheckout(cart, List.of(), T0));
        assertEquals(5, cart.qty.get("LAP"));
        cart.setQty("LAP", 3);
        s.startCheckout(cart, List.of(), T0);
    }

    void testPromotionsAndCouponsBothApply() {
        ShoppingCart.Store s = store();
        s.addPromotion(new ShoppingCart.BuyNGetOneFree("MSE", 3));
        s.addCoupon("SAVE10", new ShoppingCart.PercentOff("SAVE10", 10));
        ShoppingCart.Order o = s.startCheckout(cartOf("MSE", 4), List.of("SAVE10"), T0);
        assertEquals(10_000, o.pricing.subtotal());
        assertEquals(2, o.pricing.applied().size());
        assertEquals(6_750, o.pricing.total());                  // one mouse free (7,500), then 10% off
    }

    void testOrderKeepsTheItsPriceSnapshot() {
        ShoppingCart.Store s = store();
        ShoppingCart.Order o = s.startCheckout(cartOf("LAP", 1), List.of(), T0);
        s.setPrice("LAP", 1);
        assertEquals(100_000, o.pricing.total());
        assertEquals(100_000, o.lines.get(0).product().priceCents());
        assertEquals(1, s.startCheckout(cartOf("LAP", 1), List.of(), T0).pricing.total(), "new orders see the new price");
    }

    void testOrderIdsAreUnique() {
        ShoppingCart.Store s = store();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 3; i++) ids.add(s.startCheckout(cartOf("MSE", 1), List.of(), T0).id);
        assertEquals(3, ids.size());
    }

    // ---- payment ---------------------------------------------------------------------------------
    void testSuccessfulPaymentCommitsTheStock() {
        ShoppingCart.Store s = store();
        ShoppingCart.Order o = s.startCheckout(cartOf("LAP", 2), List.of(), T0);
        assertEquals(ShoppingCart.OrderState.PAID, s.completePayment(o, true, plusMin(5)).state);
        assertEquals(1, s.inventory.stock("LAP"));
    }

    void testFailedPaymentReleasesTheHoldWithoutTouchingStock() {
        ShoppingCart.Store s = store();
        ShoppingCart.Order o = s.startCheckout(cartOf("LAP", 3), List.of(), T0);
        assertEquals(ShoppingCart.OrderState.PAYMENT_FAILED, s.completePayment(o, false, plusMin(1)).state);
        assertEquals(3, s.inventory.stock("LAP"));
        assertEquals(3, s.inventory.available("LAP", plusMin(1)));
    }

    void testPayingAfterTheHoldExpiredMarksTheOrderExpired() {
        ShoppingCart.Store s = store();
        ShoppingCart.Order o = s.startCheckout(cartOf("LAP", 1), List.of(), T0);
        assertEquals(ShoppingCart.OrderState.EXPIRED, s.completePayment(o, true, plusMin(10)).state);
        assertEquals(3, s.inventory.stock("LAP"), "no stock is consumed for an expired order");
    }

    void testAnOrderCanOnlyBeCompletedOnce() {
        ShoppingCart.Store s = store();
        ShoppingCart.Order o = s.startCheckout(cartOf("LAP", 1), List.of(), T0);
        s.completePayment(o, true, plusMin(1));
        assertContains(assertThrows(IllegalStateException.class, () -> s.completePayment(o, true, plusMin(2))).getMessage(), "already PAID");
        assertEquals(2, s.inventory.stock("LAP"));
    }

    void testAnExpiredHoldLetsAnotherCustomerBuyTheLastItems() {
        ShoppingCart.Store s = store();
        ShoppingCart.Order alice = s.startCheckout(cartOf("LAP", 3), List.of(), T0);
        assertThrows(IllegalStateException.class, () -> s.startCheckout(cartOf("LAP", 1), List.of(), plusMin(5)));
        ShoppingCart.Order bob = s.startCheckout(cartOf("LAP", 3), List.of(), plusMin(10));
        assertEquals(ShoppingCart.OrderState.EXPIRED, s.completePayment(alice, true, plusMin(11)).state);
        assertEquals(ShoppingCart.OrderState.PAID, s.completePayment(bob, true, plusMin(12)).state);
        assertEquals(0, s.inventory.stock("LAP"));
    }

    void testMoneyFormatting() {
        assertEquals("$1,000.00".replace(",", ""), ShoppingCart.money(100_000));
        assertEquals("$0.05", ShoppingCart.money(5));
    }

    public static void main(String[] args) throws Exception { System.exit(run(ShoppingCartTest.class) == 0 ? 0 : 1); }
}
