package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

/** Reconnect backoff while the destination is down, driven by a fake clock. Ports 15190-15199. */
class SyslogAppenderBackoffTest {

    private static final int PORT = 15193;

    @Test
    void noConnectAttemptsDuringBackoffThenOneAttemptPerWindowAndOrderKept() throws Exception {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        AtomicLong now = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        SyslogAppender a = SyslogAppenderFailureTest.tcp(ctx, PORT);
        a.setSync(true);   // writes happen on the logging thread, so the sequence is deterministic
        a.nanoClock = now::get;
        a.start();
        Logger l = ctx.getLogger("t.Backoff");
        l.addAppender(a);
        try {
            l.info("e1");   // first write fails: one attempt, backoff 1 s
            assertEquals(1, a.transportAttempts.get());
            l.info("e2");
            l.info("e3");
            advanceMs(now, 999);
            l.info("e4");
            assertEquals(1, a.transportAttempts.get(), "no connect attempt inside the 1 s window");
            advanceMs(now, 1);
            l.info("e5");   // window over: exactly one replay attempt, which fails; backoff doubles to 2 s
            assertEquals(2, a.transportAttempts.get(), "exactly one attempt after the window");
            advanceMs(now, 1999);
            l.info("e6");
            assertEquals(2, a.transportAttempts.get(), "no attempt inside the doubled 2 s window");
            assertBacklog(a, "e1", "e2", "e3", "e4", "e5", "e6");
            assertEquals(0, a.getDroppedCount());
            try (CapturingServer s = CapturingServer.start("tcp", PORT, null)) {
                advanceMs(now, 1);
                l.info("e7");   // replay succeeds, then e7 is written directly
                for (String e : new String[] { "e1", "e2", "e3", "e4", "e5", "e6", "e7" }) {
                    String m = s.poll(5000);
                    assertNotNull(m, "missing " + e);
                    assertTrue(m.endsWith(e), "expected " + e + " got " + m);
                }
                assertEquals(0, a.backlogSize());
                long attempts = a.transportAttempts.get();
                l.info("e8");   // success reset the backoff: the next line is written at once
                assertEquals(attempts + 1, a.transportAttempts.get());
                assertTrue(s.poll(5000).endsWith("e8"));
                assertNull(s.poll(300), "no duplicates");
            }
        } finally {
            a.stop();
        }
    }

    @Test
    void backoffDoublesUpToTheMaximum() {
        AtomicLong now = new AtomicLong();
        SyslogAppender.Backoff b = new SyslogAppender.Backoff(1000, 30_000, now::get);
        assertTrue(b.due());
        long[] expectedMs = { 1000, 2000, 4000, 8000, 16000, 30000, 30000 };
        for (long ms : expectedMs) {
            b.failed();
            advanceMs(now, ms - 1);
            assertTrue(!b.due(), "still waiting before " + ms + " ms");
            advanceMs(now, 1);
            assertTrue(b.due(), "due after " + ms + " ms");
        }
        b.succeeded();
        b.failed();
        advanceMs(now, 999);
        assertTrue(!b.due(), "success resets the delay to the initial 1 s");
        advanceMs(now, 1);
        assertTrue(b.due());
    }

    private static void advanceMs(AtomicLong now, long ms) {
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
    }

    private static void assertBacklog(SyslogAppender a, String... expected) {
        List<String> msgs = a.backlogMessages();
        assertEquals(expected.length, msgs.size(), msgs.toString());
        for (int i = 0; i < expected.length; i++) {
            assertTrue(msgs.get(i).endsWith(expected[i]), "position " + i + ": " + msgs);
        }
    }
}
