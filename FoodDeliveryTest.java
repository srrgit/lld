import java.util.*;

public class FoodDeliveryTest extends MiniTest {
    static final FoodDelivery.OrderState PLACED = FoodDelivery.OrderState.PLACED, ACCEPTED = FoodDelivery.OrderState.ACCEPTED,
        REJECTED = FoodDelivery.OrderState.REJECTED, PREPARING = FoodDelivery.OrderState.PREPARING,
        READY = FoodDelivery.OrderState.READY_FOR_PICKUP, PICKED = FoodDelivery.OrderState.PICKED_UP,
        DELIVERED = FoodDelivery.OrderState.DELIVERED, CANCELLED = FoodDelivery.OrderState.CANCELLED;

    static class Rig {
        final FoodDelivery.Platform p = new FoodDelivery.Platform(new FoodDelivery.NearestAgent());
        final FoodDelivery.Restaurant hub = new FoodDelivery.Restaurant("r1", "Spice Hub", 0, 0).item("bir", "Biryani", 1200).item("nan", "Naan", 300);
        final FoodDelivery.Restaurant cafe = new FoodDelivery.Restaurant("r2", "Late Cafe", 9, 9).item("lat", "Latte", 450);
        final FoodDelivery.Customer ann = new FoodDelivery.Customer("c1", "Ann");
        Rig(FoodDelivery.Agent... agents) {
            p.addRestaurant(hub); p.addRestaurant(cafe);
            for (FoodDelivery.Agent a : agents) p.addAgent(a);
        }
        FoodDelivery.Order order() { return order(hub, "nan"); }
        FoodDelivery.Order order(FoodDelivery.Restaurant r, String item) {
            FoodDelivery.Cart c = new FoodDelivery.Cart(ann);
            p.addToCart(c, r.id, item, 1);
            return p.placeOrder(c);
        }
        void go(FoodDelivery.Order o, FoodDelivery.OrderState... path) { for (FoodDelivery.OrderState s : path) p.advance(o, s); }
    }
    static FoodDelivery.Agent agent(String id, double x, double y) { return new FoodDelivery.Agent(id, x, y); }

    // ---- cart ------------------------------------------------------------------------------------
    void testAddToCartMergesQuantities() {
        Rig r = new Rig();
        FoodDelivery.Cart c = new FoodDelivery.Cart(r.ann);
        r.p.addToCart(c, "r1", "bir", 1); r.p.addToCart(c, "r1", "bir", 2);
        assertEquals(3, c.qty.get("bir"));
        assertEquals("r1", c.restaurantId);
    }

    void testUnknownRestaurantOrItemIsRejected() {
        Rig r = new Rig();
        FoodDelivery.Cart c = new FoodDelivery.Cart(r.ann);
        assertThrows(IllegalArgumentException.class, () -> r.p.addToCart(c, "nope", "bir", 1));
        assertThrows(IllegalArgumentException.class, () -> r.p.addToCart(c, "r1", "pizza", 1));
        assertTrue(c.qty.isEmpty());
    }

    void testCartIsLimitedToOneRestaurant() {
        Rig r = new Rig();
        FoodDelivery.Cart c = new FoodDelivery.Cart(r.ann);
        r.p.addToCart(c, "r1", "bir", 1);
        assertContains(assertThrows(IllegalStateException.class, () -> r.p.addToCart(c, "r2", "lat", 1)).getMessage(), "another restaurant");
        assertEquals(1, c.qty.size());
    }

    void testSoldOutItemCannotBeAdded() {
        Rig r = new Rig();
        r.hub.soldOut.add("nan");
        FoodDelivery.Cart c = new FoodDelivery.Cart(r.ann);
        assertContains(assertThrows(IllegalStateException.class, () -> r.p.addToCart(c, "r1", "nan", 1)).getMessage(), "sold out");
        assertNull(c.restaurantId, "a refused item must not pin the cart to this restaurant");
    }

    // ---- placing orders --------------------------------------------------------------------------
    void testPlaceOrderTotalsTheLines() {
        Rig r = new Rig();
        FoodDelivery.Cart c = new FoodDelivery.Cart(r.ann);
        r.p.addToCart(c, "r1", "bir", 2); r.p.addToCart(c, "r1", "nan", 3);
        FoodDelivery.Order o = r.p.placeOrder(c);
        assertEquals(3300, o.totalCents);
        assertEquals(2, o.lines.size());
        assertEquals(PLACED, o.state);
        assertNull(o.agent);
    }

    void testPlacingAnOrderEmptiesTheCartSoAnotherRestaurantCanBeUsed() {
        Rig r = new Rig();
        FoodDelivery.Cart c = new FoodDelivery.Cart(r.ann);
        r.p.addToCart(c, "r1", "bir", 1);
        r.p.placeOrder(c);
        assertTrue(c.qty.isEmpty());
        r.p.addToCart(c, "r2", "lat", 1);                          // no longer blocked by the old restaurant
    }

    void testEmptyCartCannotBeOrdered() {
        Rig r = new Rig();
        assertContains(assertThrows(IllegalStateException.class, () -> r.p.placeOrder(new FoodDelivery.Cart(r.ann))).getMessage(), "empty");
    }

    void testItemSoldOutBetweenAddAndPlaceBlocksTheOrderButKeepsTheCart() {
        Rig r = new Rig();
        FoodDelivery.Cart c = new FoodDelivery.Cart(r.ann);
        r.p.addToCart(c, "r1", "nan", 1);
        r.hub.soldOut.add("nan");
        assertContains(assertThrows(IllegalStateException.class, () -> r.p.placeOrder(c)).getMessage(), "just sold out");
        assertEquals(1, c.qty.size());
    }

    void testOrderKeepsThePriceItWasPlacedAt() {
        Rig r = new Rig();
        FoodDelivery.Order o = r.order(r.hub, "bir");
        r.hub.menu.put("bir", new FoodDelivery.MenuItem("bir", "Biryani", 9999));
        assertEquals(1200, o.totalCents);
        assertEquals(1200, o.lines.get(0).unitPriceCents());
    }

    void testOrderIdsAreUnique() {
        Rig r = new Rig();
        assertEquals("O1", r.order().id);
        assertEquals("O2", r.order().id);
    }

    // ---- state machine ---------------------------------------------------------------------------
    void testHappyPathReachesDelivered() {
        Rig r = new Rig(agent("A1", 1, 1));
        FoodDelivery.Order o = r.order();
        r.go(o, ACCEPTED, PREPARING, READY, PICKED, DELIVERED);
        assertEquals(DELIVERED, o.state);
    }

    void testStatesCannotBeSkippedOrReversed() {
        Rig r = new Rig(agent("A1", 1, 1));
        FoodDelivery.Order o = r.order();
        assertContains(assertThrows(IllegalStateException.class, () -> r.p.advance(o, PREPARING)).getMessage(), "PLACED -> PREPARING");
        assertThrows(IllegalStateException.class, () -> r.p.advance(o, DELIVERED));
        r.go(o, ACCEPTED, PREPARING);
        assertThrows(IllegalStateException.class, () -> r.p.advance(o, ACCEPTED));
        assertEquals(PREPARING, o.state);
    }

    void testRestaurantCanReject() {
        Rig r = new Rig();
        FoodDelivery.Order o = r.order();
        r.p.advance(o, REJECTED);
        assertThrows(IllegalStateException.class, () -> r.p.advance(o, ACCEPTED));
    }

    void testCancelIsAllowedUntilPickup() {
        for (FoodDelivery.OrderState[] path : new FoodDelivery.OrderState[][]{{}, {ACCEPTED}, {ACCEPTED, PREPARING}, {ACCEPTED, PREPARING, READY}}) {
            Rig r = new Rig(agent("A1", 1, 1));
            FoodDelivery.Order o = r.order();
            r.go(o, path);
            r.p.advance(o, CANCELLED);
            assertEquals(CANCELLED, o.state);
        }
    }

    void testCannotCancelOnceFoodIsPickedUp() {
        Rig r = new Rig(agent("A1", 1, 1));
        FoodDelivery.Order o = r.order();
        r.go(o, ACCEPTED, PREPARING, READY, PICKED);
        assertThrows(IllegalStateException.class, () -> r.p.advance(o, CANCELLED));
        assertEquals(PICKED, o.state);
    }

    void testTerminalStatesAllowNoFurtherTransitions() {
        for (FoodDelivery.OrderState terminal : List.of(REJECTED, CANCELLED, DELIVERED)) {
            Rig r = new Rig(agent("A1", 1, 1));
            FoodDelivery.Order o = r.order();
            if (terminal == REJECTED) r.go(o, REJECTED);
            else if (terminal == CANCELLED) r.go(o, CANCELLED);
            else r.go(o, ACCEPTED, PREPARING, READY, PICKED, DELIVERED);
            for (FoodDelivery.OrderState next : FoodDelivery.OrderState.values())
                assertThrows(IllegalStateException.class, () -> r.p.advance(o, next), terminal + " -> " + next);
        }
    }

    // ---- agent assignment ------------------------------------------------------------------------
    void testNearestFreeAgentIsAssignedWhenCookingStarts() {
        FoodDelivery.Agent near = agent("A1", 1, 1), far = agent("A2", 5, 5);
        Rig r = new Rig(far, near);
        FoodDelivery.Order o = r.order();
        r.go(o, ACCEPTED);
        assertNull(o.agent, "no agent is committed before cooking starts");
        r.go(o, PREPARING);
        assertSame(near, o.agent);
        assertTrue(near.busy);
        assertFalse(far.busy);
    }

    void testNearestIsMeasuredFromTheRestaurant() {
        FoodDelivery.Agent a1 = agent("A1", 1, 1), a2 = agent("A2", 5, 5);
        Rig r = new Rig(a1, a2);
        FoodDelivery.Order o = r.order(r.cafe, "lat");               // the cafe is at (9, 9)
        r.go(o, ACCEPTED, PREPARING);
        assertSame(a2, o.agent);
    }

    void testBusyAgentsAreSkipped() {
        FoodDelivery.Agent a1 = agent("A1", 1, 1), a2 = agent("A2", 5, 5);
        Rig r = new Rig(a1, a2);
        FoodDelivery.Order first = r.order(), second = r.order();
        r.go(first, ACCEPTED, PREPARING);
        r.go(second, ACCEPTED, PREPARING);
        assertSame(a1, first.agent);
        assertSame(a2, second.agent);
    }

    void testOrderWaitsInAQueueWhenEveryAgentIsBusy() {
        Rig r = new Rig(agent("A1", 1, 1));
        FoodDelivery.Order first = r.order(), second = r.order();
        r.go(first, ACCEPTED, PREPARING);
        r.go(second, ACCEPTED, PREPARING);
        assertNull(second.agent);
        assertEquals(PREPARING, second.state);
    }

    void testQueuedOrdersGetFreedAgentsInFirstComeOrder() {
        FoodDelivery.Agent a = agent("A1", 1, 1);
        Rig r = new Rig(a);
        FoodDelivery.Order o1 = r.order(), o2 = r.order(), o3 = r.order();
        for (FoodDelivery.Order o : List.of(o1, o2, o3)) r.go(o, ACCEPTED, PREPARING);
        r.go(o1, READY, PICKED, DELIVERED);
        assertSame(a, o2.agent);
        assertNull(o3.agent, "o3 is still waiting behind o2");
        assertEquals(1, a.completed);
    }

    void testDeliveryFreesTheAgentAndCountsIt() {
        FoodDelivery.Agent a = agent("A1", 1, 1);
        Rig r = new Rig(a);
        FoodDelivery.Order o = r.order();
        r.go(o, ACCEPTED, PREPARING, READY, PICKED);
        assertTrue(a.busy);
        r.go(o, DELIVERED);
        assertFalse(a.busy);
        assertEquals(1, a.completed);
    }

    void testCannotPickUpBeforeAnAgentExists() {
        Rig r = new Rig(agent("A1", 1, 1));
        FoodDelivery.Order busy = r.order(), waiting = r.order();
        r.go(busy, ACCEPTED, PREPARING);
        r.go(waiting, ACCEPTED, PREPARING, READY);
        assertContains(assertThrows(IllegalStateException.class, () -> r.p.advance(waiting, PICKED)).getMessage(), "No delivery agent");
        assertEquals(READY, waiting.state, "a refused transition leaves the state alone");
    }

    void testWithNoAgentsAtAllOrdersJustQueue() {
        Rig r = new Rig();
        FoodDelivery.Order o = r.order();
        r.go(o, ACCEPTED, PREPARING);
        assertNull(o.agent);
    }

    void testCancellingAnOrderThatHasAnAgentFreesItForTheQueue() {
        FoodDelivery.Agent a = agent("A1", 1, 1);
        Rig r = new Rig(a);
        FoodDelivery.Order o1 = r.order(), o2 = r.order();
        r.go(o1, ACCEPTED, PREPARING);
        r.go(o2, ACCEPTED, PREPARING);
        r.go(o1, CANCELLED);
        assertSame(a, o2.agent);
        assertTrue(a.busy);
    }

    void testCancellingAQueuedOrderRemovesItFromTheQueue() {
        FoodDelivery.Agent a = agent("A1", 1, 1);
        Rig r = new Rig(a);
        FoodDelivery.Order o1 = r.order(), o2 = r.order(), o3 = r.order();
        for (FoodDelivery.Order o : List.of(o1, o2, o3)) r.go(o, ACCEPTED, PREPARING);
        r.go(o2, CANCELLED);
        r.go(o1, READY, PICKED, DELIVERED);
        assertNull(o2.agent, "a cancelled order must never be handed an agent");
        assertSame(a, o3.agent);
    }

    void testRejectingAnOrderThatNeverHadAnAgentIsHarmless() {
        Rig r = new Rig(agent("A1", 1, 1));
        FoodDelivery.Order o = r.order();
        r.p.advance(o, REJECTED);
        assertEquals(REJECTED, o.state);
    }

    // ---- assignment strategy on its own ----------------------------------------------------------
    void testNearestAgentStrategy() {
        FoodDelivery.NearestAgent s = new FoodDelivery.NearestAgent();
        FoodDelivery.Restaurant at = new FoodDelivery.Restaurant("r", "R", 10, 0);
        assertTrue(s.pick(List.of(), at).isEmpty());
        FoodDelivery.Agent a = agent("A", 0, 0), b = agent("B", 8, 1), c = agent("C", 12, 5);
        assertSame(b, s.pick(List.of(a, b, c), at).orElseThrow());
    }

    void testNearestAgentTieGoesToTheFirstListed() {
        FoodDelivery.NearestAgent s = new FoodDelivery.NearestAgent();
        FoodDelivery.Restaurant at = new FoodDelivery.Restaurant("r", "R", 0, 0);
        FoodDelivery.Agent a = agent("A", 3, 0), b = agent("B", 0, 3);
        assertSame(a, s.pick(List.of(a, b), at).orElseThrow());
    }

    // ---- listeners -------------------------------------------------------------------------------
    void testListenersSeeEveryTransitionWithFromAndTo() {
        Rig r = new Rig(agent("A1", 1, 1));
        List<String> seen = new ArrayList<>(), seen2 = new ArrayList<>();
        r.p.listen((o, from, to) -> seen.add(from + ">" + to));
        r.p.listen((o, from, to) -> seen2.add(o.id));
        FoodDelivery.Order o = r.order();
        r.go(o, ACCEPTED, PREPARING);
        assertEquals(List.of("PLACED>ACCEPTED", "ACCEPTED>PREPARING"), seen);
        assertEquals(List.of("O1", "O1"), seen2);
    }

    void testListenersAreNotToldAboutRefusedTransitions() {
        Rig r = new Rig();
        List<String> seen = new ArrayList<>();
        r.p.listen((o, from, to) -> seen.add(to.name()));
        FoodDelivery.Order o = r.order();
        assertThrows(IllegalStateException.class, () -> r.p.advance(o, DELIVERED));
        assertTrue(seen.isEmpty());
    }

    void testMoneyFormatting() {
        assertEquals("$12.00", FoodDelivery.money(1200));
        assertEquals("$0.07", FoodDelivery.money(7));
    }

    public static void main(String[] args) throws Exception { System.exit(run(FoodDeliveryTest.class) == 0 ? 0 : 1); }
}
