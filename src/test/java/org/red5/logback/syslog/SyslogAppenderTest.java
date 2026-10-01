package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

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

    @Test
    void mapsLevelAndFormatsSuffix() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15150, null)) {
            SyslogAppender a = appender(ctx, 15150);
            a.start();
            assertTrue(a.isStarted());
            Logger l = ctx.getLogger("t.Logger");
            l.addAppender(a); l.setLevel(Level.DEBUG);
            l.warn("careful");
            String m = s.poll(3000);
            assertNotNull(m);
            assertTrue(m.contains("t.Logger careful"), m);
            a.stop();
        }
    }

    @Test
    void stackTraceSentAsOneMessagePerLine() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15151, null)) {
            SyslogAppender a = appender(ctx, 15151);
            a.start();
            Logger l = ctx.getLogger("t.Trace");
            l.addAppender(a);
            l.error("boom", new IllegalStateException("bad\nmultiline"));
            String first = s.poll(3000);
            assertTrue(first.contains("boom"));
            String next = s.poll(3000);
            assertNotNull(next);
            assertFalse(next.contains("\n"), "no embedded newline: " + next);
            a.stop();
        }
    }

    @Test
    void throwableExcludedSendsOnlyTheMessage() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("udp", 15152, null)) {
            SyslogAppender a = appender(ctx, 15152);
            a.setThrowableExcluded(true);
            a.start();
            Logger l = ctx.getLogger("t.Ex");
            l.addAppender(a);
            l.error("only", new RuntimeException("x"));
            assertTrue(s.poll(3000).contains("only"));
            assertNull(s.poll(500));
            a.stop();
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
    }

    @Test
    void messageWithNewlineIsSplitIntoSeparateSyslogMessages() throws Exception {
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = CapturingServer.start("tcp", 15153, null)) {
            SyslogAppender a = appender(ctx, 15153);
            a.setProtocol(Protocol.TCP);
            a.start();
            Logger l = ctx.getLogger("t.Nl");
            l.addAppender(a);
            l.info("line1\nline2");
            assertTrue(s.poll(3000).contains("line1"));
            assertTrue(s.poll(3000).contains("line2"));
            a.stop();
        }
    }
}
