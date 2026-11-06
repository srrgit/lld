import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pub-Sub message broker (Observer at its core, Kafka-like in spirit).
 *   - A Topic is an append-only log. Publishing never waits for subscribers.
 *   - Each Subscription has its own offset and its own single worker thread, so a slow subscriber
 *     never blocks the others, and each subscriber sees messages in publish order.
 *   - Delivery is at-least-once: the offset advances only after the handler succeeds. A handler
 *     that keeps failing is retried, then the message goes to a dead-letter list.
 *   - A new subscriber can start from the latest message or replay from the beginning.
 */
public class PubSub {
    record Message(long id, String topic, String payload) {}
    record DeadLetter(String subscriber, Message message, String error) {}

    interface Subscriber { void onMessage(Message m) throws Exception; }

    static class Topic {
        private final List<Message> log = new ArrayList<>();
        synchronized Message append(String topic, String payload) { Message m = new Message(log.size(), topic, payload); log.add(m); return m; }
        synchronized Message read(int offset) { return offset < log.size() ? log.get(offset) : null; }
        synchronized int size() { return log.size(); }
    }

    static class Subscription {
        final String id; private final Topic topic; private final Subscriber handler; private final int maxAttempts;
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final List<DeadLetter> deadLetters;
        private int offset;                                    // only touched by the worker thread

        Subscription(String id, Topic topic, Subscriber handler, int startOffset, int maxAttempts, List<DeadLetter> dlq) {
            this.id = id; this.topic = topic; this.handler = handler; this.offset = startOffset;
            this.maxAttempts = maxAttempts; this.deadLetters = dlq;
        }

        void signal() { worker.submit(this::drain); }

        private void drain() {
            for (Message m; (m = topic.read(offset)) != null; offset++) {
                String lastError = null; boolean ok = false;
                for (int attempt = 1; attempt <= maxAttempts && !ok; attempt++) {
                    try { handler.onMessage(m); ok = true; }
                    catch (Exception e) { lastError = e.getMessage(); }
                }
                if (!ok) deadLetters.add(new DeadLetter(id, m, lastError));
            }
        }

        void stop() throws InterruptedException { worker.shutdown(); worker.awaitTermination(5, TimeUnit.SECONDS); }
    }

    static class Broker {
        private final Map<String, Topic> topics = new ConcurrentHashMap<>();
        private final Map<String, List<Subscription>> subs = new ConcurrentHashMap<>();
        final List<DeadLetter> deadLetters = new CopyOnWriteArrayList<>();

        private volatile boolean closed;

        void createTopic(String name) { topics.putIfAbsent(name, new Topic()); subs.putIfAbsent(name, new CopyOnWriteArrayList<>()); }

        Subscription subscribe(String topic, String subscriberId, Subscriber h, boolean fromBeginning, int maxAttempts) {
            if (closed) throw new IllegalStateException("Broker is shut down");
            if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be at least 1");   // 0 would dead-letter everything unseen
            Topic t = require(topic);
            Subscription s = new Subscription(subscriberId, t, h, fromBeginning ? 0 : t.size(), maxAttempts, deadLetters);
            subs.get(topic).add(s);
            if (fromBeginning) s.signal();                     // replay existing messages
            return s;
        }

        void publish(String topic, String payload) {
            if (closed) throw new IllegalStateException("Broker is shut down");
            Message m = require(topic).append(topic, payload);
            for (Subscription s : subs.get(topic)) s.signal(); // fan out without waiting
        }

        /** Stops accepting new work, then lets every subscriber finish what was already published. */
        void shutdown() throws InterruptedException {
            closed = true;
            for (var l : subs.values()) for (Subscription s : l) s.stop();
        }

        private Topic require(String topic) {
            Topic t = topics.get(topic);
            if (t == null) throw new IllegalArgumentException("No such topic: " + topic);
            return t;
        }
    }

    public static void main(String[] args) throws Exception {
        Broker broker = new Broker();
        broker.createTopic("orders");

        List<String> billing = new CopyOnWriteArrayList<>(), email = new CopyOnWriteArrayList<>(), audit = new CopyOnWriteArrayList<>();
        AtomicInteger emailAttemptsOnOrder2 = new AtomicInteger();

        broker.subscribe("orders", "billing", m -> billing.add(m.payload()), false, 3);
        broker.subscribe("orders", "email", m -> {
            if (m.payload().equals("order-2") && emailAttemptsOnOrder2.incrementAndGet() < 3) throw new RuntimeException("smtp timeout");
            email.add(m.payload());
        }, false, 3);
        broker.subscribe("orders", "audit", m -> {
            if (m.payload().equals("order-3")) throw new RuntimeException("audit store down");
            audit.add(m.payload());
        }, false, 3);

        for (int i = 1; i <= 5; i++) broker.publish("orders", "order-" + i);

        List<String> replay = new CopyOnWriteArrayList<>();
        broker.subscribe("orders", "late-joiner", m -> replay.add(m.payload()), true, 1);   // replays from offset 0

        Thread.sleep(300);
        try { broker.publish("nope", "x"); } catch (IllegalArgumentException e) { System.out.println("Unknown topic: " + e.getMessage()); }
        broker.shutdown();

        System.out.println("billing (in order):         " + billing);
        System.out.println("email (retried order-2):    " + email + "  attempts on order-2 = " + emailAttemptsOnOrder2.get());
        System.out.println("audit (order-3 dead):       " + audit);
        System.out.println("late joiner replayed:       " + replay);
        for (DeadLetter d : broker.deadLetters)
            System.out.println("dead letter -> subscriber=" + d.subscriber() + " message=" + d.message().payload() + " error=" + d.error());
        try { broker.publish("orders", "too late"); } catch (IllegalStateException e) { System.out.println("After shutdown: " + e.getMessage()); }
    }
}
