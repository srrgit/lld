import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class RateLimiterTest extends MiniTest {
    final AtomicLong now = new AtomicLong(0);

    int burst(RateLimiter.Limiter l, String key, int n) {
        int ok = 0;
        for (int i = 0; i < n; i++) if (l.allow(key)) ok++;
        return ok;
    }

    // ---- token bucket ----------------------------------------------------------------------------
    void testTokenBucketAllowsABurstUpToCapacity() {
        RateLimiter.Limiter l = new RateLimiter.TokenBucket(5, 1.0, now::get);
        assertEquals(5, burst(l, "u", 7));
    }

    void testTokenBucketRefillsOverTime() {
        RateLimiter.Limiter l = new RateLimiter.TokenBucket(5, 1.0, now::get);
        burst(l, "u", 5);
        now.set(2000);
        assertEquals(2, burst(l, "u", 4));
    }

    void testTokenBucketNeverRefillsAboveCapacity() {
        RateLimiter.Limiter l = new RateLimiter.TokenBucket(5, 1.0, now::get);
        burst(l, "u", 5);
        now.set(1_000_000);
        assertEquals(5, burst(l, "u", 20));
    }

    void testTokenBucketHandlesFractionalTokens() {
        RateLimiter.Limiter l = new RateLimiter.TokenBucket(1, 1.0, now::get);
        assertTrue(l.allow("u"));
        now.set(500);
        assertFalse(l.allow("u"), "only half a token so far");
        now.set(1000);
        assertTrue(l.allow("u"), "a full token has accumulated");
    }

    void testTokenBucketWithNoRefillNeverRecovers() {
        RateLimiter.Limiter l = new RateLimiter.TokenBucket(2, 0, now::get);
        assertEquals(2, burst(l, "u", 5));
        now.set(10_000_000);
        assertEquals(0, burst(l, "u", 5));
    }

    void testTokenBucketKeysAreIndependent() {
        RateLimiter.Limiter l = new RateLimiter.TokenBucket(2, 0.5, now::get);
        assertEquals(2, burst(l, "alice", 3));
        assertEquals(1, burst(l, "bob", 1));
        assertEquals(1, burst(l, "bob", 3));
    }

    // ---- leaky bucket (meter) --------------------------------------------------------------------
    void testLeakyBucketRejectsOverflow() {
        RateLimiter.Limiter l = new RateLimiter.LeakyBucket(5, 1.0, now::get);
        assertEquals(5, burst(l, "u", 7));
    }

    void testLeakyBucketDrainsOverTime() {
        RateLimiter.Limiter l = new RateLimiter.LeakyBucket(5, 1.0, now::get);
        burst(l, "u", 5);
        now.set(2000);
        assertEquals(2, burst(l, "u", 4));
    }

    void testLeakyBucketCannotBankCreditWhileIdle() {
        RateLimiter.Limiter l = new RateLimiter.LeakyBucket(5, 1.0, now::get);
        now.set(1_000_000);                                      // a long quiet spell
        assertEquals(5, burst(l, "u", 10), "an empty bucket still only holds `capacity`");
    }

    void testLeakyBucketHandlesFractionalLevels() {
        RateLimiter.Limiter l = new RateLimiter.LeakyBucket(1, 1.0, now::get);
        assertTrue(l.allow("u"));
        now.set(500);
        assertFalse(l.allow("u"));
        now.set(1000);
        assertTrue(l.allow("u"));
    }

    void testLeakyBucketKeysAreIndependent() {
        RateLimiter.Limiter l = new RateLimiter.LeakyBucket(1, 0, now::get);
        assertTrue(l.allow("a"));
        assertFalse(l.allow("a"));
        assertTrue(l.allow("b"));
    }

    // ---- leaky bucket (shaper) -------------------------------------------------------------------
    void testShaperSpacesRequestsAndRejectsWhenTheQueueIsFull() {
        RateLimiter.LeakyBucketShaper s = new RateLimiter.LeakyBucketShaper(2, 4, now::get);
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 7; i++) waits.add(s.schedule("u"));
        assertEquals(List.of(0L, 500L, 1000L, 1500L, -1L, -1L, -1L), waits);
    }

    void testRejectedRequestsDoNotConsumeASlot() {
        RateLimiter.LeakyBucketShaper s = new RateLimiter.LeakyBucketShaper(2, 2, now::get);
        s.schedule("u"); s.schedule("u");
        for (int i = 0; i < 10; i++) assertEquals(-1L, s.schedule("u"));
        now.set(500);                                            // one slot has drained
        assertEquals(500L, s.schedule("u"));
    }

    void testShaperQueueDrainsWithTime() {
        RateLimiter.LeakyBucketShaper s = new RateLimiter.LeakyBucketShaper(2, 4, now::get);
        for (int i = 0; i < 4; i++) s.schedule("u");
        now.set(10_000);
        assertEquals(0L, s.schedule("u"), "after an idle period the next request goes straight through");
    }

    void testShaperKeysAreIndependent() {
        RateLimiter.LeakyBucketShaper s = new RateLimiter.LeakyBucketShaper(1, 2, now::get);
        assertEquals(0L, s.schedule("a"));
        assertEquals(1000L, s.schedule("a"));
        assertEquals(-1L, s.schedule("a"), "a's queue (capacity 2) is full");
        assertEquals(0L, s.schedule("b"), "b has its own queue");
    }

    // ---- sliding window log ----------------------------------------------------------------------
    void testSlidingWindowLimitsRequestsPerWindow() {
        RateLimiter.Limiter l = new RateLimiter.SlidingWindowLog(3, 1000, now::get);
        for (long t : new long[]{0, 100, 200}) { now.set(t); assertTrue(l.allow("u")); }
        now.set(300);
        assertFalse(l.allow("u"));
    }

    void testSlidingWindowEntriesExpireExactlyAtTheWindowEdge() {
        RateLimiter.Limiter l = new RateLimiter.SlidingWindowLog(3, 1000, now::get);
        for (long t : new long[]{0, 100, 200}) { now.set(t); l.allow("u"); }
        now.set(999);
        assertFalse(l.allow("u"), "the request from t=0 is still inside the window");
        now.set(1000);
        assertTrue(l.allow("u"), "the request from t=0 has just left the window");
        assertFalse(l.allow("u"));
    }

    void testSlidingWindowDoesNotRecordRejectedRequests() {
        RateLimiter.Limiter l = new RateLimiter.SlidingWindowLog(1, 1000, now::get);
        assertTrue(l.allow("u"));
        for (long t = 100; t < 1000; t += 100) { now.set(t); assertFalse(l.allow("u")); }
        now.set(1000);
        assertTrue(l.allow("u"), "rejections must not extend the penalty");
    }

    // ---- fixed window ----------------------------------------------------------------------------
    void testFixedWindowResetsAtTheBoundary() {
        RateLimiter.Limiter l = new RateLimiter.FixedWindow(2, 1000, now::get);
        now.set(1500);
        assertEquals(2, burst(l, "u", 5));
        now.set(1999);
        assertFalse(l.allow("u"));
        now.set(2000);
        assertEquals(2, burst(l, "u", 5));
    }

    void testFixedWindowKeysAreIndependent() {
        RateLimiter.Limiter l = new RateLimiter.FixedWindow(1, 1000, now::get);
        assertTrue(l.allow("a"));
        assertFalse(l.allow("a"));
        assertTrue(l.allow("b"));
    }

    // ---- factory and comparison ------------------------------------------------------------------
    void testFactoryBuildsTheRequestedAlgorithm() {
        assertTrue(RateLimiter.create(RateLimiter.Algorithm.TOKEN_BUCKET, 3, 1000, now::get) instanceof RateLimiter.TokenBucket);
        assertTrue(RateLimiter.create(RateLimiter.Algorithm.LEAKY_BUCKET, 3, 1000, now::get) instanceof RateLimiter.LeakyBucket);
        assertTrue(RateLimiter.create(RateLimiter.Algorithm.SLIDING_WINDOW_LOG, 3, 1000, now::get) instanceof RateLimiter.SlidingWindowLog);
        assertTrue(RateLimiter.create(RateLimiter.Algorithm.FIXED_WINDOW, 3, 1000, now::get) instanceof RateLimiter.FixedWindow);
    }

    void testEveryAlgorithmEnforcesTheLimitWithinOneInstant() {
        for (RateLimiter.Algorithm a : RateLimiter.Algorithm.values())
            assertEquals(3, burst(RateLimiter.create(a, 3, 1000, now::get), "k", 10), a.name());
    }

    void testEveryAlgorithmIsolatesKeys() {
        for (RateLimiter.Algorithm a : RateLimiter.Algorithm.values()) {
            RateLimiter.Limiter l = RateLimiter.create(a, 2, 1000, now::get);
            burst(l, "x", 5);
            assertEquals(2, burst(l, "y", 5), a.name());
        }
    }

    void testEveryAlgorithmRecoversAfterAFullWindow() {
        for (RateLimiter.Algorithm a : RateLimiter.Algorithm.values()) {
            now.set(0);
            RateLimiter.Limiter l = RateLimiter.create(a, 3, 1000, now::get);
            burst(l, "k", 3);
            now.set(2000);
            assertEquals(3, burst(l, "k", 10), a.name());
        }
    }

    void testBoundaryBurstOnlyFixedWindowLetsDoubleThrough() {
        Map<RateLimiter.Algorithm, Integer> total = new EnumMap<>(RateLimiter.Algorithm.class);
        for (RateLimiter.Algorithm a : RateLimiter.Algorithm.values()) {
            now.set(999);
            RateLimiter.Limiter l = RateLimiter.create(a, 5, 1000, now::get);
            int first = burst(l, "k", 5);
            now.set(1000);
            total.put(a, first + burst(l, "k", 5));
        }
        assertEquals(10, total.get(RateLimiter.Algorithm.FIXED_WINDOW));
        assertEquals(5, total.get(RateLimiter.Algorithm.SLIDING_WINDOW_LOG));
        assertEquals(5, total.get(RateLimiter.Algorithm.TOKEN_BUCKET));
        assertEquals(5, total.get(RateLimiter.Algorithm.LEAKY_BUCKET));
    }

    // ---- concurrency -----------------------------------------------------------------------------
    void testEveryAlgorithmAllowsExactlyTheLimitUnderContention() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (RateLimiter.Algorithm a : RateLimiter.Algorithm.values()) {
            RateLimiter.Limiter l = RateLimiter.create(a, 100, 60_000, () -> 0L);      // frozen clock: no refill, one window
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> burst(l, "hot", 50)));
            int total = 0;
            for (Future<Integer> f : fs) total += f.get();
            assertEquals(100, total, a.name());
        }
        pool.shutdown();
    }

    void testShaperUnderContentionHandsOutDistinctSlots() throws Exception {
        RateLimiter.LeakyBucketShaper s = new RateLimiter.LeakyBucketShaper(10, 1000, () -> 0L);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < 200; i++) fs.add(pool.submit(() -> s.schedule("k")));
        Set<Long> waits = new HashSet<>();
        for (Future<Long> f : fs) waits.add(f.get());
        pool.shutdown();
        assertEquals(200, waits.size(), "two callers must never be given the same departure time");
        assertTrue(waits.contains(0L) && waits.contains(19_900L));
    }

    public static void main(String[] args) throws Exception { System.exit(run(RateLimiterTest.class) == 0 ? 0 : 1); }
}
