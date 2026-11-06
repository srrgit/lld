import java.util.*;
import java.util.concurrent.*;

public class LRUCacheTest extends MiniTest {
    void testGetOnMissingKeyReturnsNull() {
        assertNull(new LRUCache<String, Integer>(2).get("x"));
    }

    void testPutThenGet() {
        LRUCache<String, Integer> c = new LRUCache<>(2);
        c.put("a", 1);
        assertEquals(1, c.get("a"));
        assertEquals(1, c.size());
    }

    void testCapacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<String, Integer>(0));
        assertThrows(IllegalArgumentException.class, () -> new LRUCache<String, Integer>(-3));
    }

    void testLeastRecentlyUsedEntryIsEvicted() {
        LRUCache<String, Integer> c = new LRUCache<>(2);
        c.put("a", 1); c.put("b", 2); c.put("c", 3);
        assertNull(c.get("a"));
        assertEquals(2, c.get("b"));
        assertEquals(3, c.get("c"));
        assertEquals(2, c.size());
    }

    void testGetRefreshesRecency() {
        LRUCache<String, Integer> c = new LRUCache<>(2);
        c.put("a", 1); c.put("b", 2);
        c.get("a");                                            // b is now the oldest
        c.put("c", 3);
        assertNull(c.get("b"));
        assertEquals(1, c.get("a"));
    }

    void testPutOnExistingKeyUpdatesValueAndRefreshesRecency() {
        LRUCache<String, Integer> c = new LRUCache<>(2);
        c.put("a", 1); c.put("b", 2);
        c.put("a", 10);                                        // update, not a new entry
        assertEquals(2, c.size());
        c.put("c", 3);                                         // evicts b, not a
        assertEquals(10, c.get("a"));
        assertNull(c.get("b"));
    }

    void testUpdatingAnExistingKeyNeverEvictsAnything() {
        LRUCache<String, Integer> c = new LRUCache<>(2);
        c.put("a", 1); c.put("b", 2);
        c.put("a", 5); c.put("b", 6); c.put("a", 7);
        assertEquals(2, c.size());
        assertEquals(7, c.get("a")); assertEquals(6, c.get("b"));
    }

    void testCapacityOfOne() {
        LRUCache<String, Integer> c = new LRUCache<>(1);
        c.put("a", 1);
        c.put("b", 2);
        assertNull(c.get("a"));
        assertEquals(2, c.get("b"));
        assertEquals(1, c.size());
    }

    void testOrderingIsMostRecentFirst() {
        LRUCache<String, Integer> c = new LRUCache<>(3);
        c.put("a", 1); c.put("b", 2); c.put("c", 3);
        assertEquals(List.of("c", "b", "a"), c.keysMostRecentFirst());
        c.get("a");
        assertEquals(List.of("a", "c", "b"), c.keysMostRecentFirst());
        c.put("d", 4);
        assertEquals(List.of("d", "a", "c"), c.keysMostRecentFirst());
    }

    void testEmptyCacheHasNoKeys() {
        assertEquals(List.of(), new LRUCache<String, Integer>(3).keysMostRecentFirst());
    }

    void testMissingKeyDoesNotChangeOrder() {
        LRUCache<String, Integer> c = new LRUCache<>(2);
        c.put("a", 1); c.put("b", 2);
        c.get("zzz");
        assertEquals(List.of("b", "a"), c.keysMostRecentFirst());
    }

    void testNullValuesAreIndistinguishableFromMissesButStillOccupyASlot() {
        LRUCache<String, Integer> c = new LRUCache<>(2);
        c.put("a", null);
        assertNull(c.get("a"));
        assertEquals(1, c.size());
    }

    void testStatsCountHitsAndMisses() {
        LRUCache<String, Integer> c = new LRUCache<>(2);
        c.put("a", 1);
        c.get("a"); c.get("a"); c.get("nope");
        assertEquals("hits=2 misses=1", c.stats());
    }

    void testEvictedKeyCanBeReinserted() {
        LRUCache<String, Integer> c = new LRUCache<>(1);
        c.put("a", 1); c.put("b", 2); c.put("a", 3);
        assertEquals(3, c.get("a"));
        assertNull(c.get("b"));
    }

    void testMatchesLinkedHashMapReferenceOnRandomWorkload() {
        Random rnd = new Random(99);
        LRUCache<Integer, Integer> mine = new LRUCache<>(7);
        LRUCache.LinkedHashMapLRU<Integer, Integer> ref = new LRUCache.LinkedHashMapLRU<>(7);
        for (int i = 0; i < 20_000; i++) {
            int k = rnd.nextInt(20);
            if (rnd.nextBoolean()) { mine.put(k, i); ref.put(k, i); }
            else assertEquals(ref.get(k), mine.get(k), "op " + i);
        }
        List<Integer> expected = new ArrayList<>(ref.keySet());
        Collections.reverse(expected);
        assertEquals(expected, mine.keysMostRecentFirst());
    }

    void testLinkedHashMapVersionEvictsEldest() {
        LRUCache.LinkedHashMapLRU<String, Integer> m = new LRUCache.LinkedHashMapLRU<>(2);
        m.put("a", 1); m.put("b", 2); m.get("a"); m.put("c", 3);
        assertEquals(Set.of("a", "c"), m.keySet());
    }

    void testNeverExceedsCapacityUnderContention() throws Exception {
        LRUCache<Integer, Integer> c = new LRUCache<>(50);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int seed = t;
            fs.add(pool.submit(() -> {
                Random r = new Random(seed);
                for (int i = 0; i < 10_000; i++) {
                    int k = r.nextInt(300);
                    if (r.nextInt(3) == 0) c.put(k, k); else c.get(k);
                    assertTrue(c.size() <= 50, "capacity exceeded: " + c.size());
                }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertEquals(c.size(), c.keysMostRecentFirst().size(), "list and map must stay in step");
        assertEquals(new HashSet<>(c.keysMostRecentFirst()).size(), c.size(), "no duplicate keys in the recency list");
    }

    public static void main(String[] args) throws Exception { System.exit(run(LRUCacheTest.class) == 0 ? 0 : 1); }
}
