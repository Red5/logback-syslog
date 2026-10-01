package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;

/** Delivery failures and backlog loss are reported and counted. Ports 15190-15199. */
class SyslogAppenderFailureTest {

    private static LoggerContext newContext() {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        return ctx;
    }

    /** A loopback port with nothing listening (connections are refused at once). */
    static int closedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return s.getLocalPort();
        }
    }

    static SyslogAppender tcp(LoggerContext ctx, int port) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setName("F");
        a.setSyslogHost("127.0.0.1");
        a.setPort(port);
        a.setProtocol(Protocol.TCP);
        a.setSuffixPattern("%msg");
        return a;
    }

    static List<String> statuses(LoggerContext ctx, int level, String fragment) {
        return ctx.getStatusManager().getCopyOfStatusList().stream()
                .filter(s -> s.getLevel() == level && s.getMessage().contains(fragment))
                .map(Status::getMessage).toList();
    }

    @Test
    void outageIsReportedOnceAndEvictionsAndLeftoversAreCounted() throws Exception {
        LoggerContext ctx = newContext();
        SyslogAppender a = tcp(ctx, closedPort());
        a.setSync(true);
        a.setBacklogSize(3);
        a.start();
        Logger l = ctx.getLogger("t.Down");
        l.addAppender(a);
        for (int i = 0; i < 10; i++) {
            l.info("m" + i);
            // nothing is delivered, so every submitted message is either dropped or waiting in the backlog
            assertEquals(i + 1, a.getDroppedCount() + a.backlogSize(), "accounting after m" + i);
        }
        assertEquals(7, a.getDroppedCount(), "evictions are counted");
        assertEquals(3, a.backlogSize());
        a.stop();
        assertEquals(10, a.getDroppedCount(), "7 evicted + 3 discarded at stop");
        List<String> errors = statuses(ctx, Status.ERROR, "syslog destination unavailable");
        assertEquals(1, errors.size(), "one ERROR per outage, not one per event: " + errors);
        List<String> warns = statuses(ctx, Status.WARN, "stopped");
        assertEquals(1, warns.size(), warns.toString());
        assertTrue(warns.get(0).contains("10 events dropped"), warns.get(0));
        assertTrue(warns.get(0).contains("3 messages were still in the backlog"), warns.get(0));
    }

    @Test
    void asyncOutageCountsEverySubmittedEvent() throws Exception {
        LoggerContext ctx = newContext();
        SyslogAppender a = tcp(ctx, closedPort());
        a.setBacklogSize(3);
        a.start();
        Logger l = ctx.getLogger("t.AsyncDown");
        l.addAppender(a);
        for (int i = 0; i < 10; i++) {
            l.info("m" + i);
        }
        long end = System.currentTimeMillis() + 10_000;
        while (a.getDroppedCount() + a.backlogSize() < 10 && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        assertEquals(10, a.getDroppedCount() + a.backlogSize());
        a.stop();
        assertEquals(10, a.getDroppedCount());
        assertEquals(1, statuses(ctx, Status.ERROR, "syslog destination unavailable").size());
    }

    @Test
    void recoveryReplaysBacklogAndReportsIt() throws Exception {
        LoggerContext ctx = newContext();
        SyslogAppender a = tcp(ctx, 15190);
        a.setSync(true);
        a.start();
        Logger l = ctx.getLogger("t.Recover");
        l.addAppender(a);
        l.info("early1");
        l.info("early2");
        assertEquals(2, a.backlogSize());
        try (CapturingServer s = CapturingServer.start("tcp", 15190, null)) {
            l.info("later");
            for (String expected : new String[] { "early1", "early2", "later" }) {
                String m = s.poll(5000);
                assertNotNull(m, "missing " + expected);
                assertTrue(m.contains(expected), "expected " + expected + " got " + m);
            }
            List<String> infos = statuses(ctx, Status.INFO, "syslog destination recovered");
            assertEquals(1, infos.size(), infos.toString());
            assertTrue(infos.get(0).contains("replayed 2 backlogged messages"), infos.get(0));
            assertEquals(0, a.getDroppedCount());
        } finally {
            a.stop();
        }
    }

    @Test
    void withoutBacklogFailedWritesAreCountedAndReported() throws Exception {
        LoggerContext ctx = newContext();
        SyslogAppender a = tcp(ctx, closedPort());
        a.setSync(true);
        a.setBacklogSize(0);
        a.start();
        Logger l = ctx.getLogger("t.NoBacklog");
        l.addAppender(a);
        for (int i = 0; i < 5; i++) {
            l.info("n" + i);
        }
        assertEquals(5, a.getDroppedCount());
        assertEquals(1, statuses(ctx, Status.ERROR, "syslog write failed").size());
        a.stop();
    }
}
