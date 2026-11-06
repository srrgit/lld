import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoggerFrameworkTest extends MiniTest {
    static final Instant T = Instant.parse("2026-11-06T18:00:00Z");
    static final LoggerFramework.Level DEBUG = LoggerFramework.Level.DEBUG, INFO = LoggerFramework.Level.INFO,
        WARN = LoggerFramework.Level.WARN, ERROR = LoggerFramework.Level.ERROR;

    static LoggerFramework.MemoryAppender memory(LoggerFramework.Level min) { return new LoggerFramework.MemoryAppender(min, new LoggerFramework.TextFormatter()); }
    static LoggerFramework.Logger logger(LoggerFramework.Level threshold, LoggerFramework.Appender head) {
        return new LoggerFramework.Logger("Svc", threshold, head, () -> T);
    }
    static LoggerFramework.LogRecord record(LoggerFramework.Level l, String msg) { return new LoggerFramework.LogRecord(T, l, "Svc", "main", msg); }

    // ---- formatting ------------------------------------------------------------------------------
    void testTextFormat() {
        assertEquals("2026-11-06T18:00:00Z [main] INFO Svc - hello", new LoggerFramework.TextFormatter().format(record(INFO, "hello")));
    }

    void testJsonFormat() {
        assertEquals("{\"ts\":\"2026-11-06T18:00:00Z\",\"level\":\"WARN\",\"logger\":\"Svc\",\"msg\":\"hi\"}",
            new LoggerFramework.JsonFormatter().format(record(WARN, "hi")));
    }

    void testJsonEscapesQuotesBackslashesAndControlCharacters() {
        String out = new LoggerFramework.JsonFormatter().format(record(INFO, "say \"hi\"\\ line1\nline2\ttab\u0001"));
        assertContains(out, "say \\\"hi\\\"\\\\ line1\\nline2\\ttab\\u0001");
        assertFalse(out.contains("\n"), "a JSON record must stay on one line");
    }

    void testJsonEscapesTheLoggerNameToo() {
        LoggerFramework.LogRecord r = new LoggerFramework.LogRecord(T, INFO, "a\"b", "main", "x");
        assertContains(new LoggerFramework.JsonFormatter().format(r), "\"logger\":\"a\\\"b\"");
    }

    // ---- placeholder substitution ----------------------------------------------------------------
    void testPlaceholdersAreFilledInOrder() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        logger(DEBUG, m).info("order {} placed by {}", 42, "ann");
        assertContains(m.lines.get(0), "order 42 placed by ann");
    }

    void testMessageWithoutPlaceholdersIsLeftAlone() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        logger(DEBUG, m).info("plain");
        assertContains(m.lines.get(0), "- plain");
    }

    void testMoreArgumentsThanPlaceholdersAreIgnored() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        logger(DEBUG, m).info("only {}", "one", "two", "three");
        assertContains(m.lines.get(0), "- only one");
    }

    void testMorePlaceholdersThanArgumentsKeepTheLeftoversVisible() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        logger(DEBUG, m).info("a={} b={} c={}", 1);
        assertContains(m.lines.get(0), "a=1 b={} c={}");
    }

    void testNullArgumentIsPrintedAsNull() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        logger(DEBUG, m).info("user={}", (Object) null);
        assertContains(m.lines.get(0), "user=null");
    }

    void testPlaceholderAtTheStartAndEnd() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        logger(DEBUG, m).info("{} middle {}", "A", "B");
        assertContains(m.lines.get(0), "- A middle B");
    }

    // ---- levels ----------------------------------------------------------------------------------
    void testLoggerThresholdDropsLowerLevels() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        LoggerFramework.Logger l = logger(WARN, m);
        l.debug("d"); l.info("i"); l.warn("w"); l.error("e");
        assertEquals(2, m.lines.size());
        assertContains(m.lines.get(0), "WARN");
        assertContains(m.lines.get(1), "ERROR");
    }

    void testEnabledReflectsTheThreshold() {
        LoggerFramework.Logger l = logger(INFO, memory(DEBUG));
        assertFalse(l.enabled(DEBUG));
        assertTrue(l.enabled(INFO));
        assertTrue(l.enabled(ERROR));
    }

    void testDisabledLevelsNeverBuildTheMessage() {
        AtomicInteger calls = new AtomicInteger();
        Object expensive = new Object() { @Override public String toString() { calls.incrementAndGet(); return "x"; } };
        LoggerFramework.Logger l = logger(WARN, memory(DEBUG));
        l.debug("value {}", expensive);
        assertEquals(0, calls.get());
        l.error("value {}", expensive);
        assertEquals(1, calls.get());
    }

    void testEveryLevelMethodUsesItsOwnLevel() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        LoggerFramework.Logger l = logger(DEBUG, m);
        l.debug("a"); l.info("b"); l.warn("c"); l.error("d");
        assertContains(m.lines.get(0), "DEBUG"); assertContains(m.lines.get(1), "INFO");
        assertContains(m.lines.get(2), "WARN"); assertContains(m.lines.get(3), "ERROR");
    }

    // ---- appender chain --------------------------------------------------------------------------
    void testEachAppenderAppliesItsOwnMinimumLevel() {
        LoggerFramework.MemoryAppender a = memory(INFO), b = memory(DEBUG), c = memory(ERROR);
        a.then(b).then(c);
        LoggerFramework.Logger l = logger(DEBUG, a);
        l.debug("d"); l.info("i"); l.error("e");
        assertEquals(2, a.lines.size());
        assertEquals(3, b.lines.size());
        assertEquals(1, c.lines.size());
    }

    void testARecordFilteredByTheFirstAppenderStillReachesTheRest() {
        LoggerFramework.MemoryAppender strict = memory(ERROR), lenient = memory(DEBUG);
        strict.then(lenient);
        logger(DEBUG, strict).debug("only the lenient one writes this");
        assertEquals(0, strict.lines.size());
        assertEquals(1, lenient.lines.size());
    }

    void testThenReturnsTheNextAppenderSoChainsReadLeftToRight() {
        LoggerFramework.MemoryAppender a = memory(DEBUG), b = memory(DEBUG);
        assertSame(b, a.then(b));
    }

    void testEachAppenderUsesItsOwnFormatter() {
        LoggerFramework.MemoryAppender text = new LoggerFramework.MemoryAppender(DEBUG, new LoggerFramework.TextFormatter());
        LoggerFramework.MemoryAppender json = new LoggerFramework.MemoryAppender(DEBUG, new LoggerFramework.JsonFormatter());
        text.then(json);
        logger(DEBUG, text).info("x");
        assertTrue(text.lines.get(0).startsWith("2026"));
        assertTrue(json.lines.get(0).startsWith("{"));
    }

    void testConsoleAppenderWritesToStandardOutput() throws Exception {
        LoggerFramework.ConsoleAppender console = new LoggerFramework.ConsoleAppender(INFO, new LoggerFramework.TextFormatter());
        String out = capture(() -> logger(DEBUG, console).info("to the console"));
        assertContains(out, "INFO Svc - to the console");
    }

    void testRecordCarriesTheCallersThreadAndTheInjectedClock() throws Exception {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        LoggerFramework.Logger l = logger(DEBUG, m);
        Thread t = new Thread(() -> l.info("from a worker"), "caller-7");
        t.start(); t.join();
        assertContains(m.lines.get(0), "[caller-7]");
        assertTrue(m.lines.get(0).startsWith("2026-11-06T18:00:00Z"));
    }

    // ---- factory ---------------------------------------------------------------------------------
    void testFactoryReturnsOneLoggerPerName() {
        LoggerFramework.LoggerFactory f = new LoggerFramework.LoggerFactory(DEBUG, memory(DEBUG), () -> T);
        assertSame(f.get("A"), f.get("A"));
        assertNotSame(f.get("A"), f.get("B"));
    }

    void testFactoryLoggersCarryTheirNameAndShareTheChain() {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        LoggerFramework.LoggerFactory f = new LoggerFramework.LoggerFactory(DEBUG, m, () -> T);
        f.get("Orders").info("a"); f.get("Billing").info("b");
        assertContains(m.lines.get(0), "Orders - a");
        assertContains(m.lines.get(1), "Billing - b");
    }

    void testFactoryIsSafeUnderConcurrentLookups() throws Exception {
        LoggerFramework.LoggerFactory f = new LoggerFramework.LoggerFactory(DEBUG, memory(DEBUG), () -> T);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<LoggerFramework.Logger>> fs = new ArrayList<>();
        for (int i = 0; i < 64; i++) fs.add(pool.submit(() -> f.get("shared")));
        Set<LoggerFramework.Logger> distinct = new HashSet<>();
        for (Future<LoggerFramework.Logger> x : fs) distinct.add(x.get());
        pool.shutdown();
        assertEquals(1, distinct.size());
    }

    // ---- concurrency and async -------------------------------------------------------------------
    void testConcurrentLoggingLosesNothing() throws Exception {
        LoggerFramework.MemoryAppender m = memory(DEBUG);
        LoggerFramework.Logger l = logger(DEBUG, m);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 8; t++) fs.add(pool.submit(() -> { for (int i = 0; i < 500; i++) l.info("msg {}", i); }));
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertEquals(4000, m.lines.size());
    }

    void testAsyncAppenderDeliversEverythingInOrderOnceClosed() {
        LoggerFramework.MemoryAppender sink = memory(DEBUG);
        LoggerFramework.AsyncAppender async = new LoggerFramework.AsyncAppender(DEBUG, new LoggerFramework.TextFormatter(), sink);
        LoggerFramework.Logger l = logger(DEBUG, async);
        for (int i = 0; i < 2000; i++) l.info("n={}", i);
        async.close();
        assertEquals(2000, sink.lines.size());
        for (int i = 0; i < 2000; i++) assertContains(sink.lines.get(i), "n=" + i + "");
        assertTrue(sink.lines.get(1999).endsWith("n=1999"));
    }

    void testAsyncAppenderAppliesItsOwnLevelAndPassesFormattedLinesToTheSink() {
        LoggerFramework.MemoryAppender sink = new LoggerFramework.MemoryAppender(ERROR, new LoggerFramework.JsonFormatter());   // sink's own settings are not consulted
        LoggerFramework.AsyncAppender async = new LoggerFramework.AsyncAppender(WARN, new LoggerFramework.TextFormatter(), sink);
        LoggerFramework.Logger l = logger(DEBUG, async);
        l.info("dropped"); l.warn("kept");
        async.close();
        assertEquals(1, sink.lines.size());
        assertContains(sink.lines.get(0), "WARN Svc - kept");
        assertFalse(sink.lines.get(0).startsWith("{"), "the async appender's formatter decides the text");
    }

    void testAsyncAppenderWithConcurrentProducers() throws Exception {
        LoggerFramework.MemoryAppender sink = memory(DEBUG);
        LoggerFramework.AsyncAppender async = new LoggerFramework.AsyncAppender(DEBUG, new LoggerFramework.TextFormatter(), sink);
        LoggerFramework.Logger l = logger(DEBUG, async);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 4; t++) fs.add(pool.submit(() -> { for (int i = 0; i < 1000; i++) l.warn("m{}", i); }));
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        async.close();
        assertEquals(4000, sink.lines.size());
    }

    void testClosingAnAsyncAppenderTwiceDoesNotHang() {
        LoggerFramework.AsyncAppender async = new LoggerFramework.AsyncAppender(DEBUG, new LoggerFramework.TextFormatter(), memory(DEBUG));
        async.close();
        async.close();
    }

    void testClosingWithNothingQueuedIsImmediate() {
        new LoggerFramework.AsyncAppender(DEBUG, new LoggerFramework.TextFormatter(), memory(DEBUG)).close();
    }

    public static void main(String[] args) throws Exception { System.exit(run(LoggerFrameworkTest.class) == 0 ? 0 : 1); }
}
