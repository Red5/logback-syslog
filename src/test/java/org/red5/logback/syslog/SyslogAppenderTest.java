package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.red5.syslog.SyslogLevel;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.status.Status;

class SyslogAppenderTest {

    private SyslogAppender appender(LoggerContext ctx, int port) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setSyslogHost("127.0.0.1");
        a.setPort(port);
        a.setProtocol(Protocol.UDP);
        a.setFacility("LOCAL0");
        a.setSuffixPattern("[%thread] %logger %msg");
        a.setStackTracePattern("%ex{full}");
        a.setSync(true);
        return a;
    }

    private static boolean hasError(LoggerContext ctx) {
        return ctx.getStatusManager().getCopyOfStatusList().stream().anyMatch(s -> s.getLevel() == Status.ERROR);
    }

    private static List<String> drain(CapturingServer s, long firstWaitMs) throws Exception {
        List<String> out = new ArrayList<>();
        String m = s.poll(firstWaitMs);
        while (m != null) {
            out.add(m);
            m = s.poll(500);
        }
        return out;
    }

    @Test
    void levelMapping() {
        assertEquals(SyslogLevel.ERROR, SyslogAppender.toSyslogLevel(Level.ERROR));
        assertEquals(SyslogLevel.WARN, SyslogAppender.toSyslogLevel(Level.WARN));
        assertEquals(SyslogLevel.INFO, SyslogAppender.toSyslogLevel(Level.INFO));
        assertEquals(SyslogLevel.DEBUG, SyslogAppender.toSyslogLevel(Level.DEBUG));
        assertEquals(SyslogLevel.DEBUG, SyslogAppender.toSyslogLevel(Level.TRACE));
    }

    @Test
    void splitLinesSplitsOnAnyLineBreakAndSkipsBlanks() {
        assertEquals(List.of("a", "b", "c"), SyslogAppender.splitLines("a\nb\r\n\n  \nc"));
        assertTrue(SyslogAppender.splitLines(null).isEmpty());
    }

    @Test
    void withoutThrowableHidesThrowableOnly() {
        LoggerContext ctx = new LoggerContext();
        LoggingEvent e = new LoggingEvent("f", ctx.getLogger("x"), Level.ERROR, "m", new RuntimeException("r"), null);
        assertNotNull(e.getThrowableProxy());
        ILoggingEvent v = SyslogAppender.withoutThrowable(e);
        assertNull(v.getThrowableProxy());
        assertEquals("m", v.getMessage());
        assertEquals(Level.ERROR, v.getLevel());
    }

    @Test
    void mapsLevelAndFormatsSuffix() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15150, null)) {
            SyslogAppender a = appender(ctx, 15150);
            try {
                a.start();
                assertTrue(a.isStarted());
                Logger l = ctx.getLogger("t.Logger");
                l.addAppender(a);
                l.setLevel(Level.DEBUG);
                l.warn("careful");
                String m = s.poll(3000);
                assertNotNull(m);
                assertTrue(m.contains("t.Logger careful"), m);
            } finally {
                a.stop();
            }
        }
    }

    @Test
    void stackTraceSentAsOneMessagePerLine() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15151, null)) {
            SyslogAppender a = appender(ctx, 15151);
            try {
                a.start();
                Logger l = ctx.getLogger("t.Trace");
                l.addAppender(a);
                l.error("boom", new IllegalStateException("bad\nmultiline"));
                List<String> msgs = drain(s, 3000);
                assertTrue(msgs.size() >= 3, msgs.toString());
                assertTrue(msgs.get(0).contains("boom"), msgs.get(0));
                assertFalse(msgs.get(0).contains("IllegalStateException"), msgs.get(0));
                int iseIdx = -1, multiIdx = -1;
                for (int i = 1; i < msgs.size(); i++) {
                    if (iseIdx < 0 && msgs.get(i).contains("IllegalStateException")) {
                        iseIdx = i;
                    }
                    if (multiIdx < 0 && msgs.get(i).contains("multiline")) {
                        multiIdx = i;
                    }
                }
                assertTrue(iseIdx > 0, msgs.toString());
                assertTrue(multiIdx > iseIdx, msgs.toString());
                for (String m : msgs) {
                    assertFalse(m.contains("\n"), "no embedded newline: " + m);
                }
            } finally {
                a.stop();
            }
        }
    }

    @Test
    void throwableExcludedSendsOnlyTheMessage() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15152, null)) {
            SyslogAppender a = appender(ctx, 15152);
            a.setThrowableExcluded(true);
            try {
                a.start();
                Logger l = ctx.getLogger("t.Ex");
                l.addAppender(a);
                l.error("only", new RuntimeException("x"));
                assertTrue(s.poll(3000).contains("only"));
                assertNull(s.poll(500));
            } finally {
                a.stop();
            }
        }
    }

    @Test
    void messageWithNewlineIsSplitIntoSeparateSyslogMessages() throws Exception {
        // the ported TCP server reads with readLine(), so this is an integration check only;
        // client-side splitting is proven by splitLines unit test and the UDP stack trace test
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("tcp", 15153, null)) {
            SyslogAppender a = appender(ctx, 15153);
            a.setProtocol(Protocol.TCP);
            try {
                a.start();
                Logger l = ctx.getLogger("t.Nl");
                l.addAppender(a);
                l.info("line1\nline2");
                assertTrue(s.poll(3000).contains("line1"));
                assertTrue(s.poll(3000).contains("line2"));
            } finally {
                a.stop();
            }
        }
    }

    @Test
    void udpMessageWithNewlineArrivesAsSeparateMessages() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15156, null)) {
            SyslogAppender a = appender(ctx, 15156);
            try {
                a.start();
                Logger l = ctx.getLogger("t.Nl2");
                l.addAppender(a);
                l.info("line1\nline2");
                String m1 = s.poll(3000);
                String m2 = s.poll(3000);
                assertTrue(m1.contains("line1") && !m1.contains("\n") && !m1.contains("line2"), m1);
                assertTrue(m2.endsWith("line2") && !m2.contains("line1"), m2);
            } finally {
                a.stop();
            }
        }
    }

    private void stackCount(int port, boolean excluded, int expected) throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", port, null)) {
            SyslogAppender a = appender(ctx, port);
            a.setSuffixPattern("%msg%ex");
            a.setThrowableExcluded(excluded);
            try {
                a.start();
                assertTrue(a.isStarted());
                Logger l = ctx.getLogger("t.Dup");
                l.addAppender(a);
                l.error("dup", new IllegalStateException("once"));
                int count = 0;
                for (String m : drain(s, 3000)) {
                    if (m.contains("IllegalStateException: once")) {
                        count++;
                    }
                }
                assertEquals(expected, count);
            } finally {
                a.stop();
            }
        }
    }

    @Test
    void throwableInSuffixPatternAppearsExactlyOnce() throws Exception {
        stackCount(15157, false, 1);
    }

    @Test
    void throwableInSuffixPatternHonorsThrowableExcluded() throws Exception {
        stackCount(15158, true, 0);
    }

    @Test
    void sameNamedAppendersInDifferentContextsBothWork() throws Exception {
        LoggerContext c1 = new LoggerContext();
        LoggerContext c2 = new LoggerContext();
        try (CapturingServer s1 = CapturingServer.start("udp", 15154, null)) {
            CapturingServer s2 = null;
            SyslogAppender a1 = appender(c1, 15154);
            SyslogAppender a2 = null;
            try {
                // second server must use a different protocol registry key, so use tcp for it
                s2 = CapturingServer.start("tcp", 15155, null);
                a2 = appender(c2, 15155);
                a2.setProtocol(Protocol.TCP);
                a1.setName("SYSLOG");
                a2.setName("syslog");
                a1.start();
                a2.start();
                assertTrue(a1.isStarted());
                assertTrue(a2.isStarted());
                c1.getLogger("a").addAppender(a1);
                c2.getLogger("b").addAppender(a2);
                c1.getLogger("a").info("from-one");
                c2.getLogger("b").info("from-two");
                assertTrue(s1.poll(3000).contains("from-one"));
                assertTrue(s2.poll(3000).contains("from-two"));
            } finally {
                a1.stop();
                if (a2 != null) {
                    a2.stop();
                }
                if (s2 != null) {
                    s2.close();
                }
            }
        }
    }

    @Test
    void stopTwiceAndStartTwiceAreHarmless() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15159, null)) {
            SyslogAppender a = appender(ctx, 15159);
            try {
                a.start();
                a.start();
                assertTrue(a.isStarted());
                a.stop();
                a.stop();
                assertFalse(a.isStarted());
                assertFalse(hasError(ctx));
            } finally {
                a.stop();
            }
        }
    }

    @Test
    void invalidConfigLeavesAppenderInactive() {
        LoggerContext ctx = new LoggerContext();
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setFacility("NOPE");
        a.start();
        assertFalse(a.isStarted());
        assertTrue(hasError(ctx));
    }

    private void rejects(String rule, Consumer<SyslogAppender> breaker) {
        LoggerContext ctx = new LoggerContext();
        SyslogAppender a = appender(ctx, 15160);
        breaker.accept(a);
        try {
            a.start();
            assertFalse(a.isStarted(), rule);
            assertTrue(hasError(ctx), rule);
        } finally {
            a.stop();
        }
    }

    @Test
    void validationRejectsBadConfiguration() {
        rejects("null suffix", a -> a.setSuffixPattern(null));
        rejects("empty suffix", a -> a.setSuffixPattern(""));
        rejects("null stack pattern", a -> a.setStackTracePattern(null));
        rejects("empty stack pattern", a -> a.setStackTracePattern(""));
        rejects("port 0", a -> a.setPort(0));
        rejects("port 65536", a -> a.setPort(65536));
        rejects("maxMessageLength 0", a -> a.setMaxMessageLength(0));
        rejects("null host", a -> a.setSyslogHost(null));
        rejects("empty host", a -> a.setSyslogHost(""));
        rejects("null protocol", a -> a.setProtocol(null));
        rejects("tls without stores", a -> a.setProtocol(Protocol.TLS));
    }

    @Test
    void emptyStackPatternAllowedWhenThrowableExcludedAndEmptyAppNameIsUnset() {
        LoggerContext ctx = new LoggerContext();
        SyslogAppender a = appender(ctx, 15160);
        a.setStackTracePattern("");
        a.setThrowableExcluded(true);
        a.setAppName("");
        try {
            a.start();
            assertTrue(a.isStarted());
            assertFalse(hasError(ctx));
        } finally {
            a.stop();
        }
    }
}
