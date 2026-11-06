import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * A tiny, dependency-free test harness with JUnit-style assertions, so the tests run with nothing but javac.
 * Every test class extends this, declares methods named test*, and ends with
 *   public static void main(String[] a) throws Exception { System.exit(run(MyTest.class) == 0 ? 0 : 1); }
 * Each test method gets a fresh instance, runs on its own thread with a 30 s timeout, and has System.out silenced
 * (use capture(...) to read what the code under test prints).
 * Porting to JUnit 5 is mechanical: add @Test, replace the assert* calls with the Assertions.* equivalents.
 */
public class MiniTest {
    interface Body { void run() throws Exception; }

    static class AssertionFailed extends RuntimeException {
        AssertionFailed(String message) { super(message); }
    }

    // ---- assertions ----------------------------------------------------------------------------
    static void fail(String message) { throw new AssertionFailed(message); }

    static void assertTrue(boolean condition) { assertTrue(condition, "expected true"); }
    static void assertTrue(boolean condition, String message) { if (!condition) fail(message); }
    static void assertFalse(boolean condition) { assertTrue(!condition, "expected false"); }
    static void assertFalse(boolean condition, String message) { assertTrue(!condition, message); }

    static void assertEquals(long expected, long actual) { assertEquals(expected, actual, null); }
    static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) fail(prefix(message) + "expected <" + expected + "> but was <" + actual + ">");
    }
    static void assertEquals(double expected, double actual, double tolerance) {
        if (Math.abs(expected - actual) > tolerance) fail("expected <" + expected + "> but was <" + actual + ">");
    }
    /** Exact-match overload so assertEquals(1, map.get(k)) resolves without boxing ambiguity; null actual fails. */
    static void assertEquals(int expected, Integer actual) { assertEquals(Integer.valueOf(expected), (Object) actual, null); }
    static void assertEquals(int expected, Integer actual, String message) { assertEquals(Integer.valueOf(expected), (Object) actual, message); }
    static void assertEquals(Object expected, Object actual) { assertEquals(expected, actual, null); }
    static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) fail(prefix(message) + "expected <" + expected + "> but was <" + actual + ">");
    }

    static void assertNull(Object o) { assertNull(o, "expected null but was <" + o + ">"); }
    static void assertNull(Object o, String message) { if (o != null) fail(message + " (was <" + o + ">)"); }
    static void assertNotNull(Object o) { assertNotNull(o, "expected a non-null value"); }
    static void assertNotNull(Object o, String message) { if (o == null) fail(message); }
    static void assertSame(Object expected, Object actual) { if (expected != actual) fail("expected the same instance"); }
    static void assertNotSame(Object a, Object b) { if (a == b) fail("expected different instances"); }

    static void assertContains(String text, String part) {
        if (text == null || !text.contains(part)) fail("expected <" + text + "> to contain <" + part + ">");
    }
    static void assertNotContains(String text, String part) {
        if (text != null && text.contains(part)) fail("expected <" + text + "> NOT to contain <" + part + ">");
    }

    /** Passes if the body throws an instance of `type`; returns it so the test can check the message. */
    static <T extends Throwable> T assertThrows(Class<T> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionFailed("expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionFailed("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    static <T extends Throwable> T assertThrows(Class<T> type, Body body, String message) {
        try { return assertThrows(type, body); }
        catch (AssertionFailed e) { throw new AssertionFailed(message + ": " + e.getMessage()); }
    }

    private static String prefix(String message) { return message == null ? "" : message + ": "; }

    // ---- helpers -------------------------------------------------------------------------------
    /** Runs the body and returns everything it printed to System.out. */
    static String capture(Body body) throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        try { body.run(); } finally { System.setOut(previous); }
        return buffer.toString();
    }

    // ---- runner --------------------------------------------------------------------------------
    /** Runs every test* method of the class; returns the number of failures. */
    static int run(Class<?> testClass) throws Exception {
        List<Method> tests = new ArrayList<>();
        for (Method m : testClass.getDeclaredMethods())
            if (m.getName().startsWith("test") && m.getParameterCount() == 0) tests.add(m);
        tests.sort(Comparator.comparing(Method::getName));

        PrintStream real = System.out, quiet = new PrintStream(OutputStream.nullOutputStream());
        int passed = 0, failed = 0;
        for (Method m : tests) {
            m.setAccessible(true);
            Object instance = testClass.getDeclaredConstructor().newInstance();
            Throwable[] error = new Throwable[1];
            Thread thread = new Thread(() -> {
                try { m.invoke(instance); }
                catch (InvocationTargetException e) { error[0] = e.getCause(); }
                catch (Throwable e) { error[0] = e; }
            }, "test-" + m.getName());
            thread.setDaemon(true);
            System.setOut(quiet);
            thread.start();
            thread.join(30_000);
            System.setOut(real);
            if (thread.isAlive()) error[0] = new AssertionFailed("timed out after 30 s");
            if (error[0] == null) { passed++; real.println("  PASS " + m.getName()); }
            else { failed++; real.println("  FAIL " + m.getName() + "  ->  " + error[0]); }
        }
        real.printf("%s: %d passed, %d failed%n", testClass.getSimpleName(), passed, failed);
        return failed;
    }
}
