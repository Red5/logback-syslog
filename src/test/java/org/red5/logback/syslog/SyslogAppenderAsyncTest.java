package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;

class SyslogAppenderAsyncTest {

    /** Test seam: send() blocks on an interruptible gate (a deterministic stalled writer) and counts deliveries. */
    static class GatedAppender extends SyslogAppender {
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger delivered = new AtomicInteger();
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        volatile String failOn;

        @Override
        protected void send(ILoggingEvent event) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (failOn != null && failOn.equals(event.getFormattedMessage())) {
                throw new AssertionError("boom");
            }
            messages.add(event.getFormattedMessage());
            delivered.incrementAndGet();
        }
    }

    /** A bare LoggerContext has no MDC adapter, which prepareForDeferredProcessing needs. */
    private static LoggerContext newContext() {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        return ctx;
    }

    private static long millisSince(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    private static <T extends SyslogAppender> T udp(T a, LoggerContext ctx, int queueSize) {
        a.setContext(ctx);
        a.setSyslogHost("127.0.0.1");
        a.setPort(15163); // UDP, nothing needs to listen: the gated send never writes
        a.setSuffixPattern("%msg");
        a.setQueueSize(queueSize);
        return a;
    }

    private static List<String> warnings(LoggerContext ctx) {
        List<String> out = new ArrayList<>();
        for (Status s : ctx.getStatusManager().getCopyOfStatusList()) {
            if (s.getLevel() == Status.WARN) {
                out.add(s.getMessage());
            }
        }
        return out;
    }

    @Test
    void appendReturnsImmediatelyAndDeliversInOrder() throws Exception {
        LoggerContext ctx = newContext();
        try (CapturingServer s = CapturingServer.start("tcp", 15160, null)) {
            SyslogAppender a = new SyslogAppender();
            a.setContext(ctx);
            a.setSyslogHost("127.0.0.1");
            a.setPort(15160);
            a.setProtocol(Protocol.TCP);
            a.setSuffixPattern("%msg");
            a.start();
            assertNotNull(a.writerThread(), "async is the default");
            Logger l = ctx.getLogger("t.Async");
            l.addAppender(a);
            for (int i = 0; i < 50; i++) {
                l.info("m" + i);
            }
            for (int i = 0; i < 50; i++) {
                String m = s.poll(3000);
                assertNotNull(m, "missing m" + i);
                assertTrue(m.contains("m" + i), "order: expected m" + i + " got " + m);
            }
            a.stop();
        }
    }

    @Test
    void overflowDropsCountsAndReportsTotalWithoutBlocking() throws Exception {
        LoggerContext ctx = newContext();
        GatedAppender a = udp(new GatedAppender(), ctx, 10);
        a.setShutdownTimeoutMs(300);
        a.start();
        Logger l = ctx.getLogger("t.Over");
        l.addAppender(a);
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            l.info("x" + i);
        }
        assertTrue(millisSince(t0) < 1000, "caller must not block");
        assertTrue(a.getDroppedCount() >= 1000 - 10 - 1, "all but queue capacity (+1 in flight) dropped: " + a.getDroppedCount());
        a.stop();
        long total = a.getDroppedCount();
        assertTrue(total >= 989);
        assertTrue(warnings(ctx).stream().anyMatch(m -> m.contains("stopped") && m.contains(String.valueOf(total))),
                "final warn must carry the total " + total + ": " + warnings(ctx));
    }

    @Test
    void rateLimitWindowRolloverReportsCumulativeCount() throws Exception {
        LoggerContext ctx = newContext();
        GatedAppender a = udp(new GatedAppender(), ctx, 5);
        a.dropReportIntervalMs = 50;
        a.setShutdownTimeoutMs(200);
        a.start();
        Logger l = ctx.getLogger("t.Roll");
        l.addAppender(a);
        for (int i = 0; i < 200; i++) {
            l.info("x" + i);
        }
        Thread.sleep(120);
        l.info("one more");
        long cumulative = a.getDroppedCount();
        List<String> full = warnings(ctx).stream().filter(m -> m.contains("queue full")).toList();
        assertTrue(full.size() >= 2, "expected a report per window: " + full);
        assertTrue(full.get(full.size() - 1).contains(String.valueOf(cumulative)), "last report must carry " + cumulative + ": " + full);
        a.stop();
    }

    @Test
    void stopDrainsWithinTimeoutAndIsIdempotent() throws Exception {
        LoggerContext ctx = newContext();
        try (CapturingServer s = CapturingServer.start("udp", 15161, null)) {
            SyslogAppender a = new SyslogAppender();
            a.setContext(ctx);
            a.setSyslogHost("127.0.0.1");
            a.setPort(15161);
            a.setSuffixPattern("%msg");
            a.start();
            Thread w = a.writerThread();
            Logger l = ctx.getLogger("t.Stop");
            l.addAppender(a);
            l.info("last");
            a.stop();
            a.stop(); // second stop must be harmless
            w.join(2000);
            assertFalse(w.isAlive(), "writer thread must terminate");
            assertTrue(s.poll(2000).contains("last"));
            l.info("after-stop"); // must not throw
            assertEquals(0, a.getDroppedCount());
        }
    }

    @Test
    void stopWithStalledWriterReturnsPromptlyAndCountsLeftovers() throws Exception {
        LoggerContext ctx = newContext();
        GatedAppender a = udp(new GatedAppender(), ctx, 100);
        a.setShutdownTimeoutMs(500);
        a.start();
        Thread w = a.writerThread();
        Logger l = ctx.getLogger("t.Stalled");
        l.addAppender(a);
        for (int i = 0; i < 50; i++) {
            l.info("s" + i);
        }
        long t0 = System.nanoTime();
        a.stop();
        long elapsed = millisSince(t0);
        assertTrue(elapsed < 500 + 3000, "stop took " + elapsed);
        w.join(1000);
        assertFalse(w.isAlive(), "writer thread must terminate");
        assertTrue(a.getDroppedCount() >= 49, "leftovers must be counted: " + a.getDroppedCount());
        l.info("after-stop"); // must not throw
    }

    @Test
    void zeroShutdownTimeoutDoesNotWait() throws Exception {
        LoggerContext ctx = newContext();
        GatedAppender a = udp(new GatedAppender(), ctx, 100);
        a.setShutdownTimeoutMs(0);
        a.start();
        Thread w = a.writerThread();
        Logger l = ctx.getLogger("t.Zero");
        l.addAppender(a);
        for (int i = 0; i < 20; i++) {
            l.info("z" + i);
        }
        long t0 = System.nanoTime();
        a.stop();
        assertTrue(millisSince(t0) < 3000, "stop took " + millisSince(t0));
        w.join(1000);
        assertFalse(w.isAlive());
        assertTrue(a.getDroppedCount() >= 19);
    }

    @Test
    void stopReleasesProducerBlockedInBlockWhenFull() throws Exception {
        LoggerContext ctx = newContext();
        GatedAppender a = udp(new GatedAppender(), ctx, 1);
        a.setBlockWhenFull(true);
        a.setShutdownTimeoutMs(300);
        a.start();
        Logger l = ctx.getLogger("t.Block");
        l.addAppender(a);
        Thread producer = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < 20; i++) {
                l.info("b" + i);
            }
        });
        Thread.sleep(500);
        assertTrue(producer.isAlive(), "producer should be blocked on the full queue");
        long t0 = System.nanoTime();
        a.stop();
        long stopMs = millisSince(t0);
        producer.join(3000);
        assertFalse(producer.isAlive(), "blocked producer must be released by stop()");
        assertTrue(stopMs < 300 + 3000, "stop took " + stopMs);
    }

    @Test
    void stressAccountingDeliveredPlusDroppedEqualsSubmitted() throws Exception {
        for (boolean block : new boolean[] { false, true }) {
            LoggerContext ctx = newContext();
            GatedAppender a = udp(new GatedAppender(), ctx, 64);
            a.gate.countDown(); // writer runs freely
            a.setBlockWhenFull(block);
            a.setShutdownTimeoutMs(5000);
            a.start();
            Logger l = ctx.getLogger("t.Stress");
            AtomicLong submitted = new AtomicLong();
            List<Thread> producers = new ArrayList<>();
            for (int p = 0; p < 6; p++) {
                producers.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 20_000; i++) {
                        // call append directly so events racing with stop() are submitted-and-counted, not swallowed by doAppend
                        a.append(new LoggingEvent(Logger.class.getName(), l, Level.INFO, "e", null, null));
                        submitted.incrementAndGet();
                    }
                }));
            }
            Thread.sleep(30);
            a.stop();
            for (Thread t : producers) {
                t.join(20_000);
                assertFalse(t.isAlive());
            }
            assertTrue(a.delivered.get() > 0, "writer delivered something");
            assertEquals(submitted.get(), a.delivered.get() + a.getDroppedCount(),
                    "block=" + block + " delivered=" + a.delivered.get() + " dropped=" + a.getDroppedCount());
        }
    }

    @Test
    void writerSurvivesThrowableAndCountsFailedEvent() throws Exception {
        LoggerContext ctx = newContext();
        GatedAppender a = udp(new GatedAppender(), ctx, 100);
        a.gate.countDown();
        a.failOn = "boom";
        a.start();
        Logger l = ctx.getLogger("t.Throw");
        l.addAppender(a);
        l.info("a");
        l.info("boom");
        l.info("c");
        a.stop();
        assertEquals(List.of("a", "c"), a.messages);
        assertEquals(1, a.getDroppedCount());
        assertEquals(1, ctx.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() == Status.ERROR && s.getMessage().contains("writer failed")).count());
    }

    @Test
    void enqueueFailureIsRateLimited() {
        LoggerContext ctx = new LoggerContext(); // no MDC adapter: prepareForDeferredProcessing fails per event
        SyslogAppender a = udp(new SyslogAppender(), ctx, 10);
        a.start();
        Logger l = ctx.getLogger("t.Enq");
        l.addAppender(a);
        for (int i = 0; i < 100; i++) {
            l.info("x");
        }
        assertEquals(100, a.getDroppedCount());
        long errors = ctx.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() == Status.ERROR && s.getMessage().contains("enqueue failed")).count();
        assertEquals(1, errors);
        a.stop();
    }

    @Test
    void restartDeliversNewEventsExactlyOnceInOrder() throws Exception {
        LoggerContext ctx = newContext();
        try (CapturingServer s = CapturingServer.start("udp", 15164, null)) {
            SyslogAppender a = new SyslogAppender();
            a.setContext(ctx);
            a.setSyslogHost("127.0.0.1");
            a.setPort(15164);
            a.setSuffixPattern("%msg");
            a.start();
            Thread first = a.writerThread();
            Logger l = ctx.getLogger("t.Restart");
            l.addAppender(a);
            for (int i = 0; i < 3; i++) {
                l.info("old" + i);
            }
            a.stop();
            first.join(2000);
            assertFalse(first.isAlive());
            a.start();
            assertTrue(a.isStarted());
            assertNotSame(first, a.writerThread());
            for (int i = 0; i < 3; i++) {
                l.info("new" + i);
            }
            List<String> got = new ArrayList<>();
            String m;
            while ((m = s.poll(1500)) != null) {
                got.add(m);
            }
            a.stop();
            assertEquals(6, got.size(), "exactly once: " + got);
            for (int i = 0; i < 3; i++) {
                assertTrue(got.get(i).contains("old" + i), got.toString());
                assertTrue(got.get(3 + i).contains("new" + i), got.toString());
            }
        }
    }

    /** The only test that depends on the network black-holing 10.255.255.1; skipped unless the connect really times out. */
    @Test
    void stopWithStalledConnectReturnsPromptly() throws Exception {
        boolean blackHole;
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("10.255.255.1", 9), 300);
            blackHole = false;
        } catch (SocketTimeoutException e) {
            blackHole = true;
        } catch (IOException e) {
            blackHole = false; // immediate unreachable: environment cannot stall a connect
        }
        assumeTrue(blackHole, "10.255.255.1 is not a black hole here");
        LoggerContext ctx = newContext();
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setSyslogHost("10.255.255.1");
        a.setPort(9);
        a.setProtocol(Protocol.TCP);
        a.setSuffixPattern("%msg");
        a.setQueueSize(100);
        a.setShutdownTimeoutMs(500);
        a.start();
        Thread w = a.writerThread();
        Logger l = ctx.getLogger("t.Connect");
        l.addAppender(a);
        for (int i = 0; i < 20; i++) {
            l.info("c" + i);
        }
        long t0 = System.nanoTime();
        a.stop();
        assertTrue(millisSince(t0) < 500 + 3000, "stop took " + millisSince(t0));
        w.join(1000);
        assertFalse(w.isAlive(), "writer thread must terminate");
        assertTrue(a.getDroppedCount() >= 19);
    }

    @Test
    void stopWithWriterBlockedOnNonReadingPeerReturnsPromptly() throws Exception {
        LoggerContext ctx = newContext();
        Thread acceptor = null;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            acceptor = Thread.ofVirtual().start(() -> {
                try (Socket accepted = server.accept()) { // accept and never read
                    Thread.sleep(60_000);
                } catch (Exception ignored) {
                }
            });
            SyslogAppender a = new SyslogAppender();
            a.setContext(ctx);
            a.setSyslogHost("127.0.0.1");
            a.setPort(server.getLocalPort());
            a.setProtocol(Protocol.TCP);
            a.setSuffixPattern("%msg");
            a.setMaxMessageLength(60000);
            a.setShutdownTimeoutMs(500);
            a.setQueueSize(5000);
            a.start();
            Thread w = a.writerThread();
            Logger l = ctx.getLogger("t.NoRead");
            l.addAppender(a);
            String big = "z".repeat(50_000);
            for (int i = 0; i < 2000; i++) {
                l.info(big);
            }
            Thread.sleep(500); // let the writer fill socket buffers and block
            long t0 = System.nanoTime();
            a.stop();
            long elapsed = millisSince(t0);
            assertTrue(elapsed < 500 + 3000, "stop took " + elapsed);
            w.join(1000);
            assertFalse(w.isAlive(), "writer thread must terminate");
            assertTrue(a.getDroppedCount() > 0);
        } finally {
            if (acceptor != null) {
                acceptor.interrupt();
            }
        }
    }

    @Test
    void invalidQueueSettingsPreventStart() {
        LoggerContext ctx = newContext();
        SyslogAppender a = udp(new SyslogAppender(), ctx, 0);
        a.start();
        assertFalse(a.isStarted());
        SyslogAppender b = udp(new SyslogAppender(), ctx, 10);
        b.setShutdownTimeoutMs(-1);
        b.start();
        assertFalse(b.isStarted());
    }

    private static SyslogAppender tcpAppender(LoggerContext ctx, int port) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setSyslogHost("127.0.0.1");
        a.setPort(port);
        a.setProtocol(Protocol.TCP);
        a.setSuffixPattern("%msg");
        return a;
    }

    @Test
    void serverDownAtStartThenUpReplaysBacklogBeforeNewMessages() throws Exception {
        LoggerContext ctx = newContext();
        SyslogAppender a = tcpAppender(ctx, 15166);
        a.setAppName("app");
        a.start();
        assertTrue(a.isStarted());
        assertFalse(a.syslogInstance().getConfig().isThrowExceptionOnWrite(), "failures must route to the backlog handlers");
        Logger l = ctx.getLogger("t.Backlog");
        l.addAppender(a);
        l.info("early1");
        l.info("early2");
        Thread.sleep(500);   // let the writer hit the dead port and backlog both
        try (CapturingServer s = CapturingServer.start("tcp", 15166, null)) {
            l.info("later");
            String m1 = s.poll(10_000);
            String m2 = s.poll(10_000);
            String m3 = s.poll(10_000);
            assertNotNull(m1, "early1 missing");
            assertNotNull(m2, "early2 missing");
            assertNotNull(m3, "later missing");
            assertTrue(m1.contains("early1"), m1);
            assertTrue(m2.contains("early2"), m2);
            assertTrue(m3.contains("later"), m3);
            assertFalse(m1.contains("app: app"), "replay must not prefix the ident twice: " + m1);
            assertNull(s.poll(500), "no duplicates");
        } finally {
            a.stop();
        }
    }

    @Test
    void backlogSizeZeroDisablesTheHandler() {
        LoggerContext ctx = newContext();
        SyslogAppender a = tcpAppender(ctx, 15167);
        a.setBacklogSize(0);
        a.start();
        try {
            assertEquals(0, a.backlogSize());
        } finally {
            a.stop();
        }
    }

    @Test
    void backlogIsBoundedAndKeepsNewest() throws Exception {
        LoggerContext ctx = newContext();
        SyslogAppender a = tcpAppender(ctx, 15168);
        a.setBacklogSize(3);
        a.backoffInitialMs = 0;   // b6 must trigger the replay at once, not wait out a reconnect backoff
        a.start();
        Logger l = ctx.getLogger("t.Bounded");
        l.addAppender(a);
        for (int i = 0; i < 6; i++) {
            l.info("b" + i);
        }
        long end = System.currentTimeMillis() + 10_000;
        while (a.backlogSize() < 3 && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        Thread.sleep(300);
        assertEquals(3, a.backlogSize());
        try (CapturingServer s = CapturingServer.start("tcp", 15168, null)) {
            l.info("b6");
            String[] expected = { "b3", "b4", "b5", "b6" };
            for (String e : expected) {
                String m = s.poll(10_000);
                assertNotNull(m, "missing " + e);
                assertTrue(m.contains(e), "expected " + e + " got " + m);
            }
            assertNull(s.poll(500), "b0..b2 were evicted and nothing is duplicated");
        } finally {
            a.stop();
        }
    }
}
