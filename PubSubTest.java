import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class PubSubTest extends MiniTest {
    /** Polls until the condition holds (handlers run on worker threads) or fails after the timeout. */
    static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(2);
        }
    }
    static PubSub.Broker broker(String... topics) { PubSub.Broker b = new PubSub.Broker(); for (String t : topics) b.createTopic(t); return b; }

    // ---- topic log -------------------------------------------------------------------------------
    void testTopicIsAnAppendOnlyLogWithSequentialIds() {
        PubSub.Topic t = new PubSub.Topic();
        PubSub.Message a = t.append("orders", "x"), b = t.append("orders", "y");
        assertEquals(0L, a.id()); assertEquals(1L, b.id());
        assertEquals("y", t.read(1).payload());
        assertEquals(2, t.size());
    }

    void testReadingPastTheEndGivesNull() {
        PubSub.Topic t = new PubSub.Topic();
        assertNull(t.read(0));
        t.append("orders", "x");
        assertNull(t.read(1));
    }

    // ---- delivery --------------------------------------------------------------------------------
    void testSubscriberReceivesMessagesInPublishOrder() throws Exception {
        PubSub.Broker b = broker("orders");
        List<String> got = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "billing", m -> got.add(m.payload()), false, 1);
        for (int i = 0; i < 200; i++) b.publish("orders", "m" + i);
        await(() -> got.size() == 200, "200 messages");
        for (int i = 0; i < 200; i++) assertEquals("m" + i, got.get(i));
        b.shutdown();
    }

    void testEverySubscriberGetsEveryMessage() throws Exception {
        PubSub.Broker b = broker("orders");
        List<String> one = new CopyOnWriteArrayList<>(), two = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "one", m -> one.add(m.payload()), false, 1);
        b.subscribe("orders", "two", m -> two.add(m.payload()), false, 1);
        b.publish("orders", "a"); b.publish("orders", "b");
        await(() -> one.size() == 2 && two.size() == 2, "fan-out");
        assertEquals(List.of("a", "b"), one);
        assertEquals(List.of("a", "b"), two);
        b.shutdown();
    }

    void testMessageCarriesItsTopicAndId() throws Exception {
        PubSub.Broker b = broker("orders");
        List<PubSub.Message> got = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "s", got::add, false, 1);
        b.publish("orders", "a"); b.publish("orders", "b");
        await(() -> got.size() == 2, "two messages");
        assertEquals("orders", got.get(0).topic());
        assertEquals(0L, got.get(0).id()); assertEquals(1L, got.get(1).id());
        b.shutdown();
    }

    void testPublishWithNoSubscribersIsFine() {
        broker("orders").publish("orders", "nobody listening");
    }

    void testTopicsAreIsolatedFromEachOther() throws Exception {
        PubSub.Broker b = broker("orders", "payments");
        List<String> orders = new CopyOnWriteArrayList<>(), payments = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "o", m -> orders.add(m.payload()), false, 1);
        b.subscribe("payments", "p", m -> payments.add(m.payload()), false, 1);
        b.publish("orders", "o1"); b.publish("payments", "p1");
        await(() -> orders.size() == 1 && payments.size() == 1, "both topics");
        assertEquals(List.of("o1"), orders);
        assertEquals(List.of("p1"), payments);
        b.shutdown();
    }

    // ---- start position and replay ---------------------------------------------------------------
    void testLateSubscriberSeesOnlyNewMessagesByDefault() throws Exception {
        PubSub.Broker b = broker("orders");
        b.publish("orders", "old");
        List<String> got = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "late", m -> got.add(m.payload()), false, 1);
        b.publish("orders", "new");
        await(() -> got.size() == 1, "new message");
        b.shutdown();
        assertEquals(List.of("new"), got);
    }

    void testReplayFromTheBeginningThenKeepsUpWithNewMessages() throws Exception {
        PubSub.Broker b = broker("orders");
        b.publish("orders", "1"); b.publish("orders", "2");
        List<String> got = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "replayer", m -> got.add(m.payload()), true, 1);
        b.publish("orders", "3");
        await(() -> got.size() == 3, "replay plus live");
        assertEquals(List.of("1", "2", "3"), got);
        b.shutdown();
    }

    void testReplayOnAnEmptyTopicIsHarmless() throws Exception {
        PubSub.Broker b = broker("orders");
        List<String> got = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "s", m -> got.add(m.payload()), true, 1);
        b.publish("orders", "first");
        await(() -> got.size() == 1, "first message");
        b.shutdown();
    }

    // ---- retries and dead letters ----------------------------------------------------------------
    void testHandlerThatRecoversWithinTheRetryBudgetDeliversOnce() throws Exception {
        PubSub.Broker b = broker("orders");
        AtomicInteger attempts = new AtomicInteger();
        List<String> ok = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "flaky", m -> {
            if (attempts.incrementAndGet() < 3) throw new RuntimeException("timeout");
            ok.add(m.payload());
        }, false, 3);
        b.publish("orders", "x");
        await(() -> ok.size() == 1, "eventual success");
        assertEquals(3, attempts.get());
        assertTrue(b.deadLetters.isEmpty());
        b.shutdown();
    }

    void testHandlerThatKeepsFailingGoesToTheDeadLetterListAfterExactlyMaxAttempts() throws Exception {
        PubSub.Broker b = broker("orders");
        AtomicInteger attempts = new AtomicInteger();
        b.subscribe("orders", "audit", m -> { attempts.incrementAndGet(); throw new RuntimeException("store down"); }, false, 3);
        b.publish("orders", "x");
        await(() -> b.deadLetters.size() == 1, "dead letter");
        assertEquals(3, attempts.get());
        PubSub.DeadLetter d = b.deadLetters.get(0);
        assertEquals("audit", d.subscriber());
        assertEquals("x", d.message().payload());
        assertEquals("store down", d.error());
        b.shutdown();
    }

    void testSingleAttemptSubscriptionDoesNotRetry() throws Exception {
        PubSub.Broker b = broker("orders");
        AtomicInteger attempts = new AtomicInteger();
        b.subscribe("orders", "once", m -> { attempts.incrementAndGet(); throw new RuntimeException("no"); }, false, 1);
        b.publish("orders", "x");
        await(() -> b.deadLetters.size() == 1, "dead letter");
        assertEquals(1, attempts.get());
        b.shutdown();
    }

    void testAPoisonMessageDoesNotBlockTheOnesBehindIt() throws Exception {
        PubSub.Broker b = broker("orders");
        List<String> got = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "s", m -> { if (m.payload().equals("bad")) throw new RuntimeException("boom"); got.add(m.payload()); }, false, 2);
        b.publish("orders", "a"); b.publish("orders", "bad"); b.publish("orders", "c");
        await(() -> got.size() == 2 && b.deadLetters.size() == 1, "a, c and one dead letter");
        assertEquals(List.of("a", "c"), got);
        b.shutdown();
    }

    void testFailureInOneSubscriberDoesNotAffectAnother() throws Exception {
        PubSub.Broker b = broker("orders");
        List<String> good = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "bad", m -> { throw new RuntimeException("always"); }, false, 1);
        b.subscribe("orders", "good", m -> good.add(m.payload()), false, 1);
        b.publish("orders", "x");
        await(() -> good.size() == 1, "good subscriber");
        b.shutdown();
    }

    void testExceptionWithoutAMessageIsStillDeadLettered() throws Exception {
        PubSub.Broker b = broker("orders");
        b.subscribe("orders", "s", m -> { throw new IllegalStateException(); }, false, 1);
        b.publish("orders", "x");
        await(() -> b.deadLetters.size() == 1, "dead letter");
        assertNull(b.deadLetters.get(0).error());
        b.shutdown();
    }

    // ---- isolation between subscribers -----------------------------------------------------------
    void testSlowSubscriberDoesNotHoldUpAFastOne() throws Exception {
        PubSub.Broker b = broker("orders");
        CountDownLatch release = new CountDownLatch(1);
        List<String> fast = new CopyOnWriteArrayList<>(), slow = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "slow", m -> { release.await(); slow.add(m.payload()); }, false, 1);
        b.subscribe("orders", "fast", m -> fast.add(m.payload()), false, 1);
        for (int i = 0; i < 20; i++) b.publish("orders", "m" + i);
        await(() -> fast.size() == 20, "fast subscriber finishing while slow is stuck");
        assertEquals(0, slow.size());
        release.countDown();
        await(() -> slow.size() == 20, "slow subscriber catching up");
        b.shutdown();
    }

    // ---- validation ------------------------------------------------------------------------------
    void testUnknownTopicsAreRejected() {
        PubSub.Broker b = broker("orders");
        assertContains(assertThrows(IllegalArgumentException.class, () -> b.publish("nope", "x")).getMessage(), "No such topic");
        assertThrows(IllegalArgumentException.class, () -> b.subscribe("nope", "s", m -> { }, false, 1));
    }

    void testCreatingATopicTwiceKeepsItsMessagesAndSubscribers() throws Exception {
        PubSub.Broker b = broker("orders");
        List<String> got = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "s", m -> got.add(m.payload()), false, 1);
        b.publish("orders", "a");
        b.createTopic("orders");
        b.publish("orders", "b");
        await(() -> got.size() == 2, "both messages");
        assertEquals(List.of("a", "b"), got);
        b.shutdown();
    }

    void testAtLeastOneAttemptIsRequired() {
        PubSub.Broker b = broker("orders");
        assertThrows(IllegalArgumentException.class, () -> b.subscribe("orders", "s", m -> { }, false, 0));
        assertThrows(IllegalArgumentException.class, () -> b.subscribe("orders", "s", m -> { }, false, -1));
    }

    // ---- shutdown --------------------------------------------------------------------------------
    void testShutdownLetsSubscribersFinishWhatWasAlreadyPublished() throws Exception {
        PubSub.Broker b = broker("orders");
        List<String> got = new CopyOnWriteArrayList<>();
        b.subscribe("orders", "s", m -> { Thread.sleep(1); got.add(m.payload()); }, false, 1);
        for (int i = 0; i < 100; i++) b.publish("orders", "m" + i);
        b.shutdown();
        assertEquals(100, got.size());
    }

    void testPublishingOrSubscribingAfterShutdownIsRefusedCleanly() throws Exception {
        PubSub.Broker b = broker("orders");
        b.subscribe("orders", "s", m -> { }, false, 1);
        b.shutdown();
        assertContains(assertThrows(IllegalStateException.class, () -> b.publish("orders", "late")).getMessage(), "shut down");
        assertThrows(IllegalStateException.class, () -> b.subscribe("orders", "t", m -> { }, false, 1));
    }

    // ---- concurrency -----------------------------------------------------------------------------
    void testConcurrentPublishersEverySubscriberSeesEverythingOnceInLogOrder() throws Exception {
        PubSub.Broker b = broker("orders");
        List<List<Long>> seen = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            List<Long> ids = new CopyOnWriteArrayList<>();
            seen.add(ids);
            b.subscribe("orders", "sub" + s, m -> ids.add(m.id()), false, 1);
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (int p = 0; p < 4; p++) { final int pid = p; fs.add(pool.submit(() -> { for (int i = 0; i < 250; i++) b.publish("orders", pid + ":" + i); })); }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        await(() -> seen.stream().allMatch(l -> l.size() == 1000), "1000 messages per subscriber");
        for (List<Long> ids : seen) for (int i = 0; i < 1000; i++) assertEquals((long) i, (long) ids.get(i), "position " + i);
        b.shutdown();
    }

    public static void main(String[] args) throws Exception { System.exit(run(PubSubTest.class) == 0 ? 0 : 1); }
}
