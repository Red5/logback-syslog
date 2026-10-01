package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

class SyslogAppenderAsyncTest {

    /** A bare LoggerContext has no MDC adapter, which prepareForDeferredProcessing needs. */
    private static LoggerContext newContext() {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        return ctx;
    }

    private static long millisSince(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    }

    private SyslogAppender unroutable(LoggerContext ctx, int queueSize) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setSyslogHost("10.255.255.1");
        a.setPort(9); // unroutable, writer stalls on connect
        a.setProtocol(Protocol.TCP);
        a.setQueueSize(queueSize);
        return a;
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
    void overflowDropsAndCountsWithoutBlocking() throws Exception {
        LoggerContext ctx = newContext();
        SyslogAppender a = unroutable(ctx, 10);
        a.setShutdownTimeoutMs(300);
        a.start();
        Logger l = ctx.getLogger("t.Over");
        l.addAppender(a);
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            l.info("x" + i);
        }
        assertTrue(millisSince(t0) < 1000, "caller must not block");
        assertTrue(a.getDroppedCount() > 0);
        assertTrue(a.getDroppedCount() >= 1000 - 10 - 1, "all but queue capacity (+1 in flight) dropped: " + a.getDroppedCount());
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
        SyslogAppender a = unroutable(ctx, 100);
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
    void stopWithWriterBlockedOnNonReadingPeerReturnsPromptly() throws Exception {
        LoggerContext ctx = newContext();
        try (ServerSocket server = new ServerSocket(15162, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    server.accept(); // accept and never read
                    Thread.sleep(60_000);
                } catch (Exception ignored) {
                }
            });
            SyslogAppender a = new SyslogAppender();
            a.setContext(ctx);
            a.setSyslogHost("127.0.0.1");
            a.setPort(15162);
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
            acceptor.interrupt();
        }
    }

    @Test
    void stopReleasesProducerBlockedInBlockWhenFull() throws Exception {
        LoggerContext ctx = newContext();
        SyslogAppender a = unroutable(ctx, 1);
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
        assertTrue(millisSince(t0) < 300 + 4000, "stop+release took " + millisSince(t0) + " stop=" + stopMs);
    }

    @Test
    void invalidQueueSettingsPreventStart() {
        LoggerContext ctx = newContext();
        SyslogAppender a = unroutable(ctx, 0);
        a.start();
        assertFalse(a.isStarted());
        SyslogAppender b = unroutable(ctx, 10);
        b.setShutdownTimeoutMs(-1);
        b.start();
        assertFalse(b.isStarted());
    }
}
