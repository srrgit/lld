import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NotificationSystemTest extends MiniTest {
    static final NotificationSystem.ChannelType EMAIL = NotificationSystem.ChannelType.EMAIL, SMS = NotificationSystem.ChannelType.SMS,
        PUSH = NotificationSystem.ChannelType.PUSH;
    static final NotificationSystem.User ANN = new NotificationSystem.User("u1", "Ann"), BEN = new NotificationSystem.User("u2", "Ben");

    static NotificationSystem.Event event(String type, NotificationSystem.User u, NotificationSystem.Priority p, String... kv) {
        Map<String, String> data = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) data.put(kv[i], kv[i + 1]);
        return new NotificationSystem.Event(type, u, data, p);
    }
    static NotificationSystem.Event shipped(NotificationSystem.User u, String order) {
        return event("ORDER_SHIPPED", u, NotificationSystem.Priority.NORMAL, "name", u.name(), "orderId", order);
    }

    /** A channel that counts attempts and can be told to fail or throw. */
    static class ProbeChannel implements NotificationSystem.Channel {
        final NotificationSystem.ChannelType type; final List<String> sent = new ArrayList<>();
        int attempts; int failFirst; boolean throwing;
        ProbeChannel(NotificationSystem.ChannelType type) { this.type = type; }
        public NotificationSystem.ChannelType type() { return type; }
        public boolean send(NotificationSystem.User u, String content) {
            attempts++;
            if (throwing) throw new RuntimeException("provider exploded");
            if (failFirst > 0) { failFirst--; return false; }
            sent.add(u.name() + ": " + content);
            return true;
        }
    }

    static class Rig {
        final ProbeChannel email = new ProbeChannel(EMAIL), sms = new ProbeChannel(SMS), push = new ProbeChannel(PUSH);
        final NotificationSystem.ChannelFactory factory = new NotificationSystem.ChannelFactory();
        final NotificationSystem.Templates templates = new NotificationSystem.Templates();
        final NotificationSystem.Preferences prefs = new NotificationSystem.Preferences();
        final NotificationSystem.EventBus bus = new NotificationSystem.EventBus();
        final long[] now = {0};
        final NotificationSystem.NotificationService svc;
        Rig() {
            factory.register(email); factory.register(sms); factory.register(push);
            templates.put("ORDER_SHIPPED", EMAIL, "Hi {name}, order {orderId} shipped.");
            templates.put("ORDER_SHIPPED", SMS, "Order {orderId} shipped");
            templates.put("SECURITY_ALERT", SMS, "Login from {place}");
            svc = new NotificationSystem.NotificationService(factory, templates, prefs,
                new NotificationSystem.RateLimit(2, 60_000, () -> now[0]), bus, "ORDER_SHIPPED", "SECURITY_ALERT");
        }
    }

    // ---- templates -------------------------------------------------------------------------------
    void testTemplateFillsPlaceholders() {
        NotificationSystem.Templates t = new NotificationSystem.Templates();
        t.put("E", EMAIL, "Hi {name}, order {id}");
        assertEquals("Hi Ann, order 7", t.render("E", EMAIL, Map.of("name", "Ann", "id", "7")));
    }

    void testTemplateReplacesRepeatedPlaceholders() {
        NotificationSystem.Templates t = new NotificationSystem.Templates();
        t.put("E", EMAIL, "{x}-{x}-{x}");
        assertEquals("1-1-1", t.render("E", EMAIL, Map.of("x", "1")));
    }

    void testMissingDataLeavesThePlaceholderVisible() {
        NotificationSystem.Templates t = new NotificationSystem.Templates();
        t.put("E", EMAIL, "Hi {name} from {city}");
        assertEquals("Hi Ann from {city}", t.render("E", EMAIL, Map.of("name", "Ann")));
    }

    void testChannelSpecificTemplateWinsAndEmailIsTheFallback() {
        NotificationSystem.Templates t = new NotificationSystem.Templates();
        t.put("E", EMAIL, "long email text");
        t.put("E", SMS, "short sms");
        assertEquals("short sms", t.render("E", SMS, Map.of()));
        assertEquals("long email text", t.render("E", PUSH, Map.of()));
    }

    void testNoTemplateAtAllIsAnError() {
        NotificationSystem.Templates t = new NotificationSystem.Templates();
        t.put("E", SMS, "only sms");
        assertContains(assertThrows(IllegalArgumentException.class, () -> t.render("E", PUSH, Map.of())).getMessage(), "No template");
        assertThrows(IllegalArgumentException.class, () -> t.render("UNKNOWN", EMAIL, Map.of()));
    }

    // ---- preferences -----------------------------------------------------------------------------
    void testPreferencesAreStoredPerUserAndEventType() {
        NotificationSystem.Preferences p = new NotificationSystem.Preferences();
        p.set("u1", "A", EMAIL, PUSH);
        p.set("u1", "B", SMS);
        assertEquals(EnumSet.of(EMAIL, PUSH), p.channelsFor("u1", "A"));
        assertEquals(EnumSet.of(SMS), p.channelsFor("u1", "B"));
    }

    void testNoPreferencesMeansNoChannels() {
        NotificationSystem.Preferences p = new NotificationSystem.Preferences();
        assertTrue(p.channelsFor("ghost", "A").isEmpty());
        p.set("u1", "A", EMAIL);
        assertTrue(p.channelsFor("u1", "other").isEmpty());
        assertTrue(p.channelsFor("u2", "A").isEmpty());
    }

    void testOptingOutOfEverythingAndOverwriting() {
        NotificationSystem.Preferences p = new NotificationSystem.Preferences();
        p.set("u1", "A", EMAIL, SMS);
        p.set("u1", "A");
        assertTrue(p.channelsFor("u1", "A").isEmpty());
        p.set("u1", "A", PUSH);
        assertEquals(EnumSet.of(PUSH), p.channelsFor("u1", "A"));
    }

    void testDuplicateChannelsInPreferencesAreHarmless() {
        NotificationSystem.Preferences p = new NotificationSystem.Preferences();
        p.set("u1", "A", EMAIL, EMAIL);
        assertEquals(EnumSet.of(EMAIL), p.channelsFor("u1", "A"));
    }

    // ---- rate limit ------------------------------------------------------------------------------
    void testRateLimitAllowsUpToTheLimitPerWindow() {
        long[] now = {0};
        NotificationSystem.RateLimit r = new NotificationSystem.RateLimit(2, 60_000, () -> now[0]);
        assertTrue(r.tryAcquire("u1", EMAIL));
        assertTrue(r.tryAcquire("u1", EMAIL));
        assertFalse(r.tryAcquire("u1", EMAIL));
    }

    void testRateLimitWindowSlides() {
        long[] now = {0};
        NotificationSystem.RateLimit r = new NotificationSystem.RateLimit(2, 60_000, () -> now[0]);
        r.tryAcquire("u1", EMAIL); r.tryAcquire("u1", EMAIL);
        now[0] = 59_999;
        assertFalse(r.tryAcquire("u1", EMAIL));
        now[0] = 60_000;
        assertTrue(r.tryAcquire("u1", EMAIL), "the first send has just left the window");
    }

    void testRateLimitIsPerUserAndPerChannel() {
        long[] now = {0};
        NotificationSystem.RateLimit r = new NotificationSystem.RateLimit(1, 60_000, () -> now[0]);
        assertTrue(r.tryAcquire("u1", EMAIL));
        assertFalse(r.tryAcquire("u1", EMAIL));
        assertTrue(r.tryAcquire("u1", SMS));
        assertTrue(r.tryAcquire("u2", EMAIL));
    }

    void testRejectedAttemptsAreNotCounted() {
        long[] now = {0};
        NotificationSystem.RateLimit r = new NotificationSystem.RateLimit(1, 1000, () -> now[0]);
        r.tryAcquire("u", EMAIL);
        for (int i = 0; i < 5; i++) assertFalse(r.tryAcquire("u", EMAIL));
        now[0] = 1000;
        assertTrue(r.tryAcquire("u", EMAIL));
    }

    // ---- factory and channels --------------------------------------------------------------------
    void testFactoryReturnsRegisteredChannelsAndRejectsUnknownOnes() {
        NotificationSystem.ChannelFactory f = new NotificationSystem.ChannelFactory();
        ProbeChannel email = new ProbeChannel(EMAIL);
        f.register(email);
        assertSame(email, f.get(EMAIL));
        assertContains(assertThrows(IllegalArgumentException.class, () -> f.get(SMS)).getMessage(), "No channel");
    }

    void testRegisteringTheSameTypeAgainReplacesTheChannel() {
        NotificationSystem.ChannelFactory f = new NotificationSystem.ChannelFactory();
        ProbeChannel first = new ProbeChannel(EMAIL), second = new ProbeChannel(EMAIL);
        f.register(first); f.register(second);
        assertSame(second, f.get(EMAIL));
    }

    void testLoggingChannelRecordsWhatWasSent() {
        List<String> sink = new ArrayList<>();
        NotificationSystem.LoggingChannel c = new NotificationSystem.LoggingChannel(SMS, sink);
        assertTrue(c.send(ANN, "hello"));
        assertEquals(List.of("SMS -> Ann: hello"), sink);
        assertEquals(SMS, c.type());
    }

    void testFlakyChannelFailsTheFirstNSendsThenRecovers() {
        List<String> sink = new ArrayList<>();
        NotificationSystem.FlakyChannel c = new NotificationSystem.FlakyChannel(new NotificationSystem.LoggingChannel(EMAIL, sink), 2);
        assertFalse(c.send(ANN, "1")); assertFalse(c.send(ANN, "2")); assertTrue(c.send(ANN, "3"));
        assertEquals(1, sink.size());
        assertEquals(EMAIL, c.type());
    }

    // ---- event bus -------------------------------------------------------------------------------
    void testEventBusCallsOnlyHandlersOfThatEventTypeInOrder() {
        NotificationSystem.EventBus bus = new NotificationSystem.EventBus();
        List<String> calls = new ArrayList<>();
        bus.subscribe("A", e -> calls.add("first"));
        bus.subscribe("A", e -> calls.add("second"));
        bus.subscribe("B", e -> calls.add("other"));
        bus.publish(event("A", ANN, NotificationSystem.Priority.NORMAL));
        assertEquals(List.of("first", "second"), calls);
    }

    void testPublishingAnEventNobodyHandlesIsHarmless() {
        new NotificationSystem.EventBus().publish(event("NOBODY", ANN, NotificationSystem.Priority.NORMAL));
    }

    // ---- the service -----------------------------------------------------------------------------
    void testNotificationGoesToEachPreferredChannelWithRenderedText() {
        Rig r = new Rig();
        r.prefs.set("u1", "ORDER_SHIPPED", EMAIL, SMS);
        r.bus.publish(shipped(ANN, "#1"));
        assertEquals(List.of("Ann: Hi Ann, order #1 shipped."), r.email.sent);
        assertEquals(List.of("Ann: Order #1 shipped"), r.sms.sent);
        assertTrue(r.push.sent.isEmpty(), "PUSH was not requested");
    }

    void testUserWithNoPreferencesGetsNothing() {
        Rig r = new Rig();
        r.bus.publish(shipped(BEN, "#1"));
        assertTrue(r.email.sent.isEmpty() && r.sms.sent.isEmpty() && r.push.sent.isEmpty());
        assertTrue(r.svc.deadLetters.isEmpty() && r.svc.suppressed.isEmpty());
    }

    void testEventTypesTheServiceDidNotSubscribeToAreIgnored() {
        Rig r = new Rig();
        r.prefs.set("u1", "NEWSLETTER", EMAIL);
        r.templates.put("NEWSLETTER", EMAIL, "news");
        r.bus.publish(event("NEWSLETTER", ANN, NotificationSystem.Priority.NORMAL));
        assertTrue(r.email.sent.isEmpty());
    }

    void testTransientFailuresAreRetriedUpToThreeAttempts() {
        Rig r = new Rig();
        r.prefs.set("u1", "ORDER_SHIPPED", EMAIL);
        r.email.failFirst = 2;
        r.bus.publish(shipped(ANN, "#1"));
        assertEquals(3, r.email.attempts);
        assertEquals(1, r.email.sent.size());
        assertTrue(r.svc.deadLetters.isEmpty());
    }

    void testAChannelThatKeepsFailingIsDeadLetteredAfterThreeAttempts() {
        Rig r = new Rig();
        r.prefs.set("u1", "ORDER_SHIPPED", EMAIL);
        r.email.failFirst = 99;
        r.bus.publish(shipped(ANN, "#1"));
        assertEquals(3, r.email.attempts);
        assertEquals(List.of("ORDER_SHIPPED via EMAIL for Ann"), r.svc.deadLetters);
    }

    void testOneFailingChannelDoesNotStopTheOthers() {
        Rig r = new Rig();
        r.prefs.set("u1", "ORDER_SHIPPED", EMAIL, SMS);
        r.email.failFirst = 99;
        r.bus.publish(shipped(ANN, "#1"));
        assertEquals(1, r.sms.sent.size());
        assertEquals(1, r.svc.deadLetters.size());
    }

    void testAProviderThatThrowsIsTreatedAsAFailureNotACrash() {
        Rig r = new Rig();
        r.prefs.set("u1", "ORDER_SHIPPED", EMAIL, SMS);
        r.email.throwing = true;
        r.bus.publish(shipped(ANN, "#1"));                     // must not propagate to the publisher
        assertEquals(3, r.email.attempts);
        assertEquals(1, r.sms.sent.size());
        assertEquals(List.of("ORDER_SHIPPED via EMAIL for Ann"), r.svc.deadLetters);
    }

    void testMissingTemplateIsDeadLetteredWithoutBreakingOtherChannels() {
        Rig r = new Rig();
        NotificationSystem.Templates bare = new NotificationSystem.Templates();
        bare.put("ORDER_SHIPPED", SMS, "Order {orderId} shipped");              // no EMAIL template, so PUSH has no fallback either
        NotificationSystem.EventBus bus = new NotificationSystem.EventBus();
        NotificationSystem.Preferences prefs = new NotificationSystem.Preferences();
        prefs.set("u1", "ORDER_SHIPPED", PUSH, SMS);
        NotificationSystem.NotificationService svc = new NotificationSystem.NotificationService(
            r.factory, bare, prefs, new NotificationSystem.RateLimit(5, 1000, () -> 0L), bus, "ORDER_SHIPPED");
        bus.publish(shipped(ANN, "#1"));
        assertEquals(1, r.sms.sent.size(), "SMS still went out");
        assertEquals(1, svc.deadLetters.size());
        assertContains(svc.deadLetters.get(0), "ORDER_SHIPPED via PUSH for Ann");
        assertContains(svc.deadLetters.get(0), "No template");
    }

    void testUnregisteredChannelIsDeadLettered() {
        Rig r = new Rig();
        NotificationSystem.ChannelFactory onlyEmail = new NotificationSystem.ChannelFactory();
        onlyEmail.register(r.email);
        NotificationSystem.EventBus bus = new NotificationSystem.EventBus();
        NotificationSystem.Preferences prefs = new NotificationSystem.Preferences();
        prefs.set("u1", "ORDER_SHIPPED", SMS, EMAIL);
        NotificationSystem.NotificationService svc = new NotificationSystem.NotificationService(
            onlyEmail, r.templates, prefs, new NotificationSystem.RateLimit(5, 1000, () -> 0L), bus, "ORDER_SHIPPED");
        bus.publish(shipped(ANN, "#1"));
        assertEquals(1, r.email.sent.size());
        assertContains(svc.deadLetters.get(0), "via SMS");
    }

    // ---- rate limiting in the service ------------------------------------------------------------
    void testNormalNotificationsBeyondTheLimitAreSuppressedAndRecorded() {
        Rig r = new Rig();
        r.prefs.set("u2", "ORDER_SHIPPED", SMS);
        for (int i = 1; i <= 4; i++) r.bus.publish(shipped(BEN, "#" + i));
        assertEquals(2, r.sms.sent.size());
        assertEquals(2, r.svc.suppressed.size());
        assertContains(r.svc.suppressed.get(0), "ORDER_SHIPPED via SMS for Ben (rate limited)");
    }

    void testLimitRecoversAfterTheWindow() {
        Rig r = new Rig();
        r.prefs.set("u2", "ORDER_SHIPPED", SMS);
        for (int i = 1; i <= 3; i++) r.bus.publish(shipped(BEN, "#" + i));
        r.now[0] = 61_000;
        r.bus.publish(shipped(BEN, "#4"));
        assertEquals(3, r.sms.sent.size());
    }

    void testCriticalNotificationsBypassTheLimitAndDoNotUseUpTheQuota() {
        Rig r = new Rig();
        r.prefs.set("u2", "ORDER_SHIPPED", SMS);
        r.prefs.set("u2", "SECURITY_ALERT", SMS);
        for (int i = 0; i < 5; i++) r.bus.publish(event("SECURITY_ALERT", BEN, NotificationSystem.Priority.CRITICAL, "place", "Lisbon"));
        assertEquals(5, r.sms.sent.size(), "all critical alerts delivered");
        r.bus.publish(shipped(BEN, "#1")); r.bus.publish(shipped(BEN, "#2"));
        assertEquals(7, r.sms.sent.size(), "the normal quota of 2 was still untouched");
        assertTrue(r.svc.suppressed.isEmpty());
    }

    void testLimitsAreIndependentPerUserAndChannel() {
        Rig r = new Rig();
        r.prefs.set("u1", "ORDER_SHIPPED", SMS, EMAIL);
        r.prefs.set("u2", "ORDER_SHIPPED", SMS);
        for (int i = 1; i <= 3; i++) { r.bus.publish(shipped(ANN, "#a" + i)); r.bus.publish(shipped(BEN, "#b" + i)); }
        assertEquals(2 + 2, r.sms.sent.size());
        assertEquals(2, r.email.sent.size());
        assertEquals(3, r.svc.suppressed.size(), "Ann's SMS, Ann's EMAIL and Ben's SMS each lose their third message");
    }

    public static void main(String[] args) throws Exception { System.exit(run(NotificationSystemTest.class) == 0 ? 0 : 1); }
}
