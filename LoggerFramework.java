import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Logger Framework.
 *   Chain of Responsibility: a Logger hands each record to the first Appender; every Appender applies its
 *                            own minimum level, writes if it passes, then forwards to the next one.
 *   Strategy:                Formatter (plain text, JSON) and the destination (console, memory, file) are swappable.
 *   Registry:                LoggerFactory returns one Logger per name (a thread-safe map, not a hard-coded Singleton).
 *   Async:                   AsyncAppender decorates any sink with a bounded queue and a worker thread,
 *                            so callers are never blocked by slow I/O.
 */
public class LoggerFramework {
    enum Level { DEBUG, INFO, WARN, ERROR }

    record LogRecord(Instant time, Level level, String logger, String thread, String message) {}

    interface Formatter { String format(LogRecord r); }

    static class TextFormatter implements Formatter {
        public String format(LogRecord r) { return r.time() + " [" + r.thread() + "] " + r.level() + " " + r.logger() + " - " + r.message(); }
    }
    static class JsonFormatter implements Formatter {
        public String format(LogRecord r) {
            return "{\"ts\":\"" + r.time() + "\",\"level\":\"" + r.level() + "\",\"logger\":\"" + escape(r.logger())
                + "\",\"msg\":\"" + escape(r.message()) + "\"}";
        }
        /** JSON string escaping: quotes, backslashes and every control character (a raw newline would break the line-per-record format). */
        static String escape(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
                }
            }
            return sb.toString();
        }
    }

    static abstract class Appender {
        private final Level min; private final Formatter formatter; private Appender next;
        Appender(Level min, Formatter formatter) { this.min = min; this.formatter = formatter; }

        Appender then(Appender next) { this.next = next; return next; }   // returns `next` so chains read left to right

        final void handle(LogRecord r) {
            if (r.level().ordinal() >= min.ordinal()) write(formatter.format(r));
            if (next != null) next.handle(r);
        }
        abstract void write(String line);
        void close() {}
    }

    static class ConsoleAppender extends Appender {
        ConsoleAppender(Level min, Formatter f) { super(min, f); }
        void write(String line) { System.out.println(line); }
    }

    static class MemoryAppender extends Appender {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        MemoryAppender(Level min, Formatter f) { super(min, f); }
        void write(String line) { lines.add(line); }
    }

    /** Decorator: queues formatted lines and writes them from a background thread. */
    static class AsyncAppender extends Appender {
        private static final String STOP = "\u0000STOP";
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(10_000);
        private final Appender sink; private final Thread worker;

        AsyncAppender(Level min, Formatter f, Appender sink) {
            super(min, f); this.sink = sink;
            worker = new Thread(() -> {
                try { for (String s; !(s = queue.take()).equals(STOP); ) sink.write(s); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "log-writer");
            worker.setDaemon(true); worker.start();
        }
        void write(String line) {
            try { queue.put(line); }                          // blocks only if the queue is full (back-pressure)
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override void close() {
            try { queue.put(STOP); worker.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            sink.close();
        }
    }

    static class Logger {
        private final String name; private final Level threshold; private final Appender head; private final Supplier<Instant> clock;
        Logger(String name, Level threshold, Appender head, Supplier<Instant> clock) {
            this.name = name; this.threshold = threshold; this.head = head; this.clock = clock;
        }
        boolean enabled(Level l) { return l.ordinal() >= threshold.ordinal(); }

        /** "{}" placeholders are only filled in if the level is enabled, so disabled debug calls cost almost nothing. */
        void log(Level level, String fmt, Object... args) {
            if (!enabled(level)) return;
            StringBuilder sb = new StringBuilder(); int a = 0, i = 0;
            while (i < fmt.length()) {
                int p = fmt.indexOf("{}", i);
                if (p < 0 || a >= args.length) { sb.append(fmt, i, fmt.length()); break; }
                sb.append(fmt, i, p).append(args[a++]); i = p + 2;
            }
            head.handle(new LogRecord(clock.get(), level, name, Thread.currentThread().getName(), sb.toString()));
        }
        void debug(String f, Object... a) { log(Level.DEBUG, f, a); }
        void info(String f, Object... a) { log(Level.INFO, f, a); }
        void warn(String f, Object... a) { log(Level.WARN, f, a); }
        void error(String f, Object... a) { log(Level.ERROR, f, a); }
    }

    static class LoggerFactory {
        private final Map<String, Logger> loggers = new ConcurrentHashMap<>();
        private final Level threshold; private final Appender head; private final Supplier<Instant> clock;
        LoggerFactory(Level threshold, Appender head, Supplier<Instant> clock) { this.threshold = threshold; this.head = head; this.clock = clock; }
        Logger get(String name) { return loggers.computeIfAbsent(name, n -> new Logger(n, threshold, head, clock)); }
    }

    public static void main(String[] args) throws Exception {
        Supplier<Instant> fixed = () -> Instant.parse("2026-11-06T18:00:00Z");

        ConsoleAppender console = new ConsoleAppender(Level.INFO, new TextFormatter());      // INFO and above, readable
        MemoryAppender json = new MemoryAppender(Level.DEBUG, new JsonFormatter());           // everything, machine readable
        MemoryAppender asyncSink = new MemoryAppender(Level.DEBUG, new TextFormatter());      // sink only: its formatter is not used
        AsyncAppender async = new AsyncAppender(Level.WARN, new TextFormatter(), asyncSink);  // WARN and above, off-thread
        console.then(json).then(async);

        LoggerFactory factory = new LoggerFactory(Level.DEBUG, console, fixed);
        Logger log = factory.get("OrderService");
        log.debug("loading order {}", 42);
        log.info("order {} placed by {}", 42, "ann");
        log.warn("payment slow: {} ms", 1800);
        log.error("payment failed for order {}", 42);
        System.out.println("Same name gives the same logger: " + (factory.get("OrderService") == log));
        System.out.println("JSON appender captured " + json.lines.size() + " lines, first: " + json.lines.get(0));

        System.out.println("Async: 4 threads x 1000 warnings through the queue (separate chain, so the console stays quiet)");
        LoggerFactory loadFactory = new LoggerFactory(Level.DEBUG, async, fixed);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 4; t++) fs.add(pool.submit(() -> { for (int i = 0; i < 1000; i++) loadFactory.get("Load").warn("msg {}", i); }));
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        async.close();                                        // drains the queue before returning
        System.out.println("Async sink received " + asyncSink.lines.size() + " lines (expected 4002: 4000 + 2 earlier WARN/ERROR)");
    }
}
