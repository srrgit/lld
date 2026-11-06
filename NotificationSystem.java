import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Notification System.
 *   Observer:  an EventBus; the NotificationService subscribes to domain events (OrderShipped, ...).
 *   Strategy:  each Channel (email, SMS, push) implements the same interface.
 *   Factory:   ChannelFactory builds the channels, so adding WhatsApp means one new class and one registration.
 *   Plus:      per-user channel preferences, a template engine, retry with a dead-letter list,
 *              and a per-user/per-channel rate limit that CRITICAL notifications bypass.
 */
public class NotificationSystem {
    enum ChannelType { EMAIL, SMS, PUSH }
    enum Priority { NORMAL, CRITICAL }

    record User(String id, String name) {}
    record Event(String type, User user, Map<String, String> data, Priority priority) {}

    interface Channel {
        ChannelType type();
        boolean send(User user, String content);              // false = transient failure, worth retrying
    }

    static class LoggingChannel implements Channel {
        private final ChannelType type; private final List<String> sent;
        LoggingChannel(ChannelType type, List<String> sent) { this.type = type; this.sent = sent; }
        public ChannelType type() { return type; }
        public boolean send(User u, String content) { sent.add(type + " -> " + u.name() + ": " + content); return true; }
    }

    /** Simulates a flaky provider: fails the first N sends. */
    static class FlakyChannel implements Channel {
        private final Channel inner; private int failuresLeft;
        FlakyChannel(Channel inner, int failures) { this.inner = inner; this.failuresLeft = failures; }
        public ChannelType type() { return inner.type(); }
        public boolean send(User u, String c) { if (failuresLeft > 0) { failuresLeft--; return false; } return inner.send(u, c); }
    }

    static class ChannelFactory {
        private final Map<ChannelType, Channel> channels = new EnumMap<>(ChannelType.class);
        void register(Channel c) { channels.put(c.type(), c); }
        Channel get(ChannelType t) { return Optional.ofNullable(channels.get(t)).orElseThrow(() -> new IllegalArgumentException("No channel " + t)); }
    }

    static class Templates {
        private final Map<String, String> byKey = new HashMap<>();     // key = eventType + ":" + channel
        void put(String eventType, ChannelType ch, String template) { byKey.put(eventType + ":" + ch, template); }
        String render(String eventType, ChannelType ch, Map<String, String> data) {
            String t = byKey.getOrDefault(eventType + ":" + ch, byKey.get(eventType + ":" + ChannelType.EMAIL));
            if (t == null) throw new IllegalArgumentException("No template for " + eventType);
            for (var e : data.entrySet()) t = t.replace("{" + e.getKey() + "}", e.getValue());
            return t;
        }
    }

    static class Preferences {
        private final Map<String, Map<String, Set<ChannelType>>> byUser = new HashMap<>();  // user -> eventType -> channels
        void set(String userId, String eventType, ChannelType... channels) {
            byUser.computeIfAbsent(userId, k -> new HashMap<>()).put(eventType, channels.length == 0 ? EnumSet.noneOf(ChannelType.class) : EnumSet.copyOf(List.of(channels)));
        }
        Set<ChannelType> channelsFor(String userId, String eventType) {
            return byUser.getOrDefault(userId, Map.of()).getOrDefault(eventType, Set.of());
        }
    }

    /** At most `limit` notifications per user per channel per window. */
    static class RateLimit {
        private final int limit; private final long windowMs; private final LongSupplier clock;
        private final Map<String, Deque<Long>> sends = new ConcurrentHashMap<>();
        RateLimit(int limit, long windowMs, LongSupplier clock) { this.limit = limit; this.windowMs = windowMs; this.clock = clock; }
        boolean tryAcquire(String userId, ChannelType ch) {
            Deque<Long> d = sends.computeIfAbsent(userId + "|" + ch, k -> new ArrayDeque<>());
            synchronized (d) {
                long now = clock.getAsLong();
                while (!d.isEmpty() && d.peekFirst() <= now - windowMs) d.pollFirst();
                if (d.size() >= limit) return false;
                d.addLast(now); return true;
            }
        }
    }

    static class EventBus {
        private final Map<String, List<Consumer<Event>>> handlers = new HashMap<>();
        void subscribe(String eventType, Consumer<Event> h) { handlers.computeIfAbsent(eventType, k -> new ArrayList<>()).add(h); }
        void publish(Event e) { handlers.getOrDefault(e.type(), List.of()).forEach(h -> h.accept(e)); }
    }

    static class NotificationService {
        private final ChannelFactory channels; private final Templates templates; private final Preferences prefs; private final RateLimit limit;
        final List<String> deadLetters = new ArrayList<>(); final List<String> suppressed = new ArrayList<>();
        private static final int MAX_ATTEMPTS = 3;

        NotificationService(ChannelFactory c, Templates t, Preferences p, RateLimit l, EventBus bus, String... eventTypes) {
            channels = c; templates = t; prefs = p; limit = l;
            for (String type : eventTypes) bus.subscribe(type, this::handle);
        }

        private void handle(Event e) {
            for (ChannelType ch : prefs.channelsFor(e.user().id(), e.type())) {
                if (e.priority() != Priority.CRITICAL && !limit.tryAcquire(e.user().id(), ch)) {
                    suppressed.add(e.type() + " via " + ch + " for " + e.user().name() + " (rate limited)");
                    continue;
                }
                try {                                             // one broken channel must not stop the others, or the publisher
                    String content = templates.render(e.type(), ch, e.data());
                    Channel channel = channels.get(ch);
                    boolean ok = false;
                    for (int attempt = 1; attempt <= MAX_ATTEMPTS && !ok; attempt++) {
                        try { ok = channel.send(e.user(), content); }
                        catch (RuntimeException providerError) { ok = false; }
                    }
                    if (!ok) deadLetters.add(e.type() + " via " + ch + " for " + e.user().name());
                } catch (IllegalArgumentException config) {       // missing template or unregistered channel
                    deadLetters.add(e.type() + " via " + ch + " for " + e.user().name() + " (" + config.getMessage() + ")");
                }
            }
        }
    }

    public static void main(String[] args) {
        List<String> sent = new ArrayList<>();
        ChannelFactory factory = new ChannelFactory();
        factory.register(new FlakyChannel(new LoggingChannel(ChannelType.EMAIL, sent), 2));   // first 2 sends fail, 3rd works
        factory.register(new LoggingChannel(ChannelType.SMS, sent));
        factory.register(new LoggingChannel(ChannelType.PUSH, sent));

        Templates templates = new Templates();
        templates.put("ORDER_SHIPPED", ChannelType.EMAIL, "Hi {name}, your order {orderId} has shipped and arrives {eta}.");
        templates.put("ORDER_SHIPPED", ChannelType.SMS, "Order {orderId} shipped, ETA {eta}");
        templates.put("SECURITY_ALERT", ChannelType.SMS, "New login to your account from {place}");

        Preferences prefs = new Preferences();
        User ann = new User("u1", "Ann"), ben = new User("u2", "Ben");
        prefs.set("u1", "ORDER_SHIPPED", ChannelType.EMAIL, ChannelType.PUSH);
        prefs.set("u2", "ORDER_SHIPPED", ChannelType.SMS);
        prefs.set("u2", "SECURITY_ALERT", ChannelType.SMS);
        templates.put("ORDER_SHIPPED", ChannelType.PUSH, "Order {orderId} is on its way");

        long[] now = {0};
        EventBus bus = new EventBus();
        NotificationService svc = new NotificationService(factory, templates, prefs, new RateLimit(2, 60_000, () -> now[0]), bus, "ORDER_SHIPPED", "SECURITY_ALERT");

        bus.publish(new Event("ORDER_SHIPPED", ann, Map.of("name", "Ann", "orderId", "#1001", "eta", "Friday"), Priority.NORMAL));
        for (int i = 2; i <= 4; i++)
            bus.publish(new Event("ORDER_SHIPPED", ben, Map.of("name", "Ben", "orderId", "#100" + i, "eta", "Monday"), Priority.NORMAL));
        bus.publish(new Event("SECURITY_ALERT", ben, Map.of("place", "Lisbon"), Priority.CRITICAL));
        now[0] = 61_000;
        bus.publish(new Event("ORDER_SHIPPED", ben, Map.of("name", "Ben", "orderId", "#1005", "eta", "Tuesday"), Priority.NORMAL));

        sent.forEach(s -> System.out.println("SENT       " + s));
        svc.suppressed.forEach(s -> System.out.println("SUPPRESSED " + s));
        System.out.println("Dead letters: " + svc.deadLetters);
    }
}
