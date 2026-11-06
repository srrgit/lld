import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Rate Limiter with four interchangeable algorithms behind one interface, chosen by a factory.
 *   TokenBucket       O(1) memory per key, allows bursts up to capacity, smooth refill. The usual pick.
 *   LeakyBucket       water-level meter: each request adds one unit, the level drains at a constant rate, a request
 *                     that would overflow is rejected. As a yes/no limiter it behaves like a token bucket seen from
 *                     the other side. The version that really differs is LeakyBucketShaper below, which does not
 *                     reject bursts but spaces the requests out at a fixed rate (smooth output, added latency).
 *   SlidingWindowLog  exact (no boundary burst) but stores one timestamp per allowed request.
 *   FixedWindow       simplest and cheapest, but allows up to 2x the limit across a window boundary.
 * Time comes from an injected clock so behaviour is testable without sleeping.
 */
public class RateLimiter {
    interface Limiter { boolean allow(String key); }

    enum Algorithm { TOKEN_BUCKET, LEAKY_BUCKET, SLIDING_WINDOW_LOG, FIXED_WINDOW }

    static class TokenBucket implements Limiter {
        private static class Bucket { double tokens; long lastMs; }
        private final double capacity, refillPerMs; private final LongSupplier clock;
        private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

        TokenBucket(double capacity, double refillPerSecond, LongSupplier clock) {
            this.capacity = capacity; this.refillPerMs = refillPerSecond / 1000.0; this.clock = clock;
        }
        public boolean allow(String key) {
            Bucket b = buckets.computeIfAbsent(key, k -> { Bucket x = new Bucket(); x.tokens = capacity; x.lastMs = clock.getAsLong(); return x; });
            synchronized (b) {                                   // lazy refill, no background thread
                long now = clock.getAsLong();
                b.tokens = Math.min(capacity, b.tokens + (now - b.lastMs) * refillPerMs);
                b.lastMs = now;
                if (b.tokens >= 1) { b.tokens -= 1; return true; }
                return false;
            }
        }
    }

    /**
     * Leaky bucket as a meter. `level` is how full the bucket is; it drains at leakPerSecond.
     * A request adds 1 and is allowed only if the bucket does not overflow its capacity.
     */
    static class LeakyBucket implements Limiter {
        private static class Bucket { double level; long lastMs; }
        private final double capacity, leakPerMs; private final LongSupplier clock;
        private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

        LeakyBucket(double capacity, double leakPerSecond, LongSupplier clock) {
            this.capacity = capacity; this.leakPerMs = leakPerSecond / 1000.0; this.clock = clock;
        }
        public boolean allow(String key) {
            Bucket b = buckets.computeIfAbsent(key, k -> { Bucket x = new Bucket(); x.lastMs = clock.getAsLong(); return x; });
            synchronized (b) {                                   // drain lazily, no background thread
                long now = clock.getAsLong();
                b.level = Math.max(0, b.level - (now - b.lastMs) * leakPerMs);
                b.lastMs = now;
                if (b.level + 1 <= capacity) { b.level += 1; return true; }
                return false;                                    // would overflow
            }
        }
    }

    /**
     * Leaky bucket as a shaper (the queue version). Instead of rejecting a burst it schedules each request to
     * leave the bucket at a fixed rate, one every intervalMs, and rejects only when the queue is already full.
     * No real queue or thread is needed: each key just remembers when its next free departure slot is.
     * schedule() returns how long the caller must wait in ms, or -1 if the queue is full.
     */
    static class LeakyBucketShaper {
        private static class Slot { long nextFreeMs; }
        private final long intervalMs; private final int queueCapacity; private final LongSupplier clock;
        private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

        LeakyBucketShaper(int ratePerSecond, int queueCapacity, LongSupplier clock) {
            this.intervalMs = 1000L / ratePerSecond; this.queueCapacity = queueCapacity; this.clock = clock;
        }
        long schedule(String key) {
            Slot s = slots.computeIfAbsent(key, k -> new Slot());
            synchronized (s) {
                long now = clock.getAsLong();
                long departure = Math.max(now, s.nextFreeMs);
                long queued = (departure - now) / intervalMs;     // how many requests are ahead of this one
                if (queued >= queueCapacity) return -1;
                s.nextFreeMs = departure + intervalMs;
                return departure - now;
            }
        }
    }

    static class SlidingWindowLog implements Limiter {
        private final int limit; private final long windowMs; private final LongSupplier clock;
        private final ConcurrentHashMap<String, Deque<Long>> logs = new ConcurrentHashMap<>();
        SlidingWindowLog(int limit, long windowMs, LongSupplier clock) { this.limit = limit; this.windowMs = windowMs; this.clock = clock; }
        public boolean allow(String key) {
            Deque<Long> log = logs.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (log) {
                long now = clock.getAsLong();
                while (!log.isEmpty() && log.peekFirst() <= now - windowMs) log.pollFirst();   // drop expired entries
                if (log.size() < limit) { log.addLast(now); return true; }
                return false;
            }
        }
    }

    static class FixedWindow implements Limiter {
        private final int limit; private final long windowMs; private final LongSupplier clock;
        private final ConcurrentHashMap<String, long[]> state = new ConcurrentHashMap<>();   // [windowStart, count]
        FixedWindow(int limit, long windowMs, LongSupplier clock) { this.limit = limit; this.windowMs = windowMs; this.clock = clock; }
        public boolean allow(String key) {
            long[] s = state.computeIfAbsent(key, k -> new long[]{-1, 0});
            synchronized (s) {
                long now = clock.getAsLong(), start = now / windowMs * windowMs;
                if (s[0] != start) { s[0] = start; s[1] = 0; }
                if (s[1] < limit) { s[1]++; return true; }
                return false;
            }
        }
    }

    /** limit requests per windowMs. */
    static Limiter create(Algorithm a, int limit, long windowMs, LongSupplier clock) {
        return switch (a) {
            case TOKEN_BUCKET -> new TokenBucket(limit, limit * 1000.0 / windowMs, clock);
            case LEAKY_BUCKET -> new LeakyBucket(limit, limit * 1000.0 / windowMs, clock);
            case SLIDING_WINDOW_LOG -> new SlidingWindowLog(limit, windowMs, clock);
            case FIXED_WINDOW -> new FixedWindow(limit, windowMs, clock);
        };
    }

    private static int burst(Limiter l, String key, int n) {
        int ok = 0; for (int i = 0; i < n; i++) if (l.allow(key)) ok++; return ok;
    }

    public static void main(String[] args) throws Exception {
        AtomicLong t = new AtomicLong(0);
        LongSupplier clock = t::get;

        System.out.println("Token bucket: capacity 5, refill 1 token/sec");
        Limiter tb = new TokenBucket(5, 1.0, clock);
        System.out.println("  7 rapid requests at t=0   -> allowed " + burst(tb, "u", 7));
        t.set(2000);
        System.out.println("  4 requests at t=2s        -> allowed " + burst(tb, "u", 4) + " (2 tokens refilled)");

        t.set(0);
        System.out.println("Leaky bucket (meter): capacity 5, leaks 1/sec");
        Limiter lb = new LeakyBucket(5, 1.0, clock);
        System.out.println("  7 rapid requests at t=0   -> allowed " + burst(lb, "u", 7));
        t.set(2000);
        System.out.println("  4 requests at t=2s        -> allowed " + burst(lb, "u", 4) + " (2 units drained)");

        t.set(0);
        System.out.println("Leaky bucket (shaper): 2 requests/sec out, queue holds 4. Burst of 7 at t=0:");
        LeakyBucketShaper shaper = new LeakyBucketShaper(2, 4, clock);
        StringBuilder waits = new StringBuilder();
        for (int i = 0; i < 7; i++) { long w = shaper.schedule("u"); waits.append(w < 0 ? "REJECT " : w + "ms "); }
        System.out.println("  wait before release: " + waits.toString().trim() + "  (smooth 500ms spacing, overflow rejected)");

        System.out.println("Per-key isolation:");
        Limiter iso = new TokenBucket(2, 0.5, clock);
        System.out.println("  alice x3 -> " + burst(iso, "alice", 3) + " allowed;  bob x1 -> " + burst(iso, "bob", 1) + " allowed");

        System.out.println("Boundary burst, limit 5 per 1000ms (5 requests at t=999, 5 more at t=1000):");
        for (Algorithm a : new Algorithm[]{Algorithm.FIXED_WINDOW, Algorithm.SLIDING_WINDOW_LOG, Algorithm.TOKEN_BUCKET, Algorithm.LEAKY_BUCKET}) {
            t.set(999);
            Limiter l = create(a, 5, 1000, clock);
            int first = burst(l, "k", 5);
            t.set(1000);
            int second = burst(l, "k", 5);
            System.out.printf("  %-18s allowed %d + %d = %d%n", a, first, second, first + second);
        }

        System.out.println("Concurrency: bucket of 100 tokens, no refill, 8 threads x 50 requests on one key");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        Map<String, Limiter> shared = new LinkedHashMap<>();
        shared.put("TokenBucket", new TokenBucket(100, 0, System::currentTimeMillis));
        shared.put("LeakyBucket", new LeakyBucket(100, 0, System::currentTimeMillis));
        for (var e : shared.entrySet()) {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> burst(e.getValue(), "hot", 50)));
            int total = 0; for (Future<Integer> f : fs) total += f.get();
            System.out.println("  " + e.getKey() + " allowed = " + total + " (expected exactly 100)");
        }
        pool.shutdown();
    }
}
