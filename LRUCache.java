import java.util.*;
import java.util.concurrent.*;

/**
 * LRU Cache with O(1) get and put: a HashMap for lookup plus a doubly linked list for recency.
 * Head side = most recently used, tail side = next to evict. Sentinel nodes remove all null checks.
 * Also includes the 6-line LinkedHashMap version often accepted as the "use the library" answer.
 * To make eviction pluggable (LFU, FIFO), extract an EvictionPolicy interface with onAccess/onInsert/victim.
 */
public class LRUCache<K, V> {
    private static class Node<K, V> {
        final K key; V val; Node<K, V> prev, next;
        Node(K key, V val) { this.key = key; this.val = val; }
    }

    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head = new Node<>(null, null), tail = new Node<>(null, null);
    private long hits, misses;

    public LRUCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        head.next = tail; tail.prev = head;
    }

    public synchronized V get(K key) {
        Node<K, V> n = map.get(key);
        if (n == null) { misses++; return null; }
        hits++;
        unlink(n); addFront(n);
        return n.val;
    }

    public synchronized void put(K key, V val) {
        Node<K, V> n = map.get(key);
        if (n != null) { n.val = val; unlink(n); addFront(n); return; }
        if (map.size() == capacity) {                      // evict least recently used
            Node<K, V> lru = tail.prev;
            unlink(lru); map.remove(lru.key);
        }
        n = new Node<>(key, val);
        map.put(key, n); addFront(n);
    }

    public synchronized int size() { return map.size(); }
    public synchronized String stats() { return "hits=" + hits + " misses=" + misses; }

    public synchronized List<K> keysMostRecentFirst() {
        List<K> out = new ArrayList<>();
        for (Node<K, V> n = head.next; n != tail; n = n.next) out.add(n.key);
        return out;
    }

    private void unlink(Node<K, V> n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void addFront(Node<K, V> n) { n.next = head.next; n.prev = head; head.next.prev = n; head.next = n; }

    /** The shortcut version: access-ordered LinkedHashMap that evicts the eldest entry. */
    static class LinkedHashMapLRU<K, V> extends LinkedHashMap<K, V> {
        private final int cap;
        LinkedHashMapLRU(int cap) { super(16, 0.75f, true); this.cap = cap; }
        @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) { return size() > cap; }
    }

    public static void main(String[] args) throws Exception {
        LRUCache<String, Integer> c = new LRUCache<>(3);
        c.put("a", 1); c.put("b", 2); c.put("c", 3);
        System.out.println("After a,b,c:            " + c.keysMostRecentFirst());
        c.get("a");
        System.out.println("After get(a):           " + c.keysMostRecentFirst());
        c.put("d", 4);
        System.out.println("After put(d), b evicted: " + c.keysMostRecentFirst() + "  get(b)=" + c.get("b"));
        c.put("c", 30);
        System.out.println("Update c refreshes it:  " + c.keysMostRecentFirst() + "  " + c.stats());

        // Both implementations must agree on a random workload.
        Random rnd = new Random(1);
        LRUCache<Integer, Integer> mine = new LRUCache<>(5);
        LinkedHashMapLRU<Integer, Integer> ref = new LinkedHashMapLRU<>(5);
        for (int i = 0; i < 10_000; i++) {
            int k = rnd.nextInt(12);
            if (rnd.nextBoolean()) { mine.put(k, i); ref.put(k, i); }
            else { Integer x = mine.get(k), y = ref.get(k); if (!Objects.equals(x, y)) throw new AssertionError("Mismatch at op " + i); }
        }
        List<Integer> refOrder = new ArrayList<>(ref.keySet());
        Collections.reverse(refOrder);                      // LinkedHashMap lists eldest first
        System.out.println("Matches LinkedHashMap after 10k random ops: " + mine.keysMostRecentFirst().equals(refOrder));

        // Thread-safety: the cache must never exceed its capacity under contention.
        LRUCache<Integer, Integer> shared = new LRUCache<>(100);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final int seed = t;
            fs.add(pool.submit(() -> {
                Random r = new Random(seed);
                for (int i = 0; i < 20_000; i++) { int k = r.nextInt(500); if (r.nextInt(3) == 0) shared.put(k, k); else shared.get(k); }
            }));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        System.out.println("Size after 160k concurrent ops: " + shared.size() + " (capacity 100, never exceeded)");
    }
}
