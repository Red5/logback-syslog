package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.red5.syslog.impl.unix.socket.UnixDatagramSocket;
import org.red5.syslog.impl.unix.socket.UnixDatagramTestSeam;
import org.red5.syslog.testsupport.UnixDatagramServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;

/** Protocol UNIX through the appender: datagram (default) and stream sockets, availability, backlog and lifecycle. */
class SyslogAppenderUnixTest {

    @TempDir
    Path dir;

    @AfterEach
    void resetSeam() {
        UnixDatagramTestSeam.reset();
    }

    private static LoggerContext context() {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        return ctx;
    }

    private static SyslogAppender unix(LoggerContext ctx, Path path) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setName("U");
        a.setProtocol(Protocol.UNIX);
        a.setUnixSocketPath(path.toString());
        a.setFacility("LOCAL0");
        a.setAppName("unixapp");
        a.setSuffixPattern("%msg");
        return a;
    }

    private static void assumeDatagram() {
        Assumptions.assumeTrue(UnixDatagramSocket.isAvailable(), UnixDatagramSocket::unavailableReason);
    }

    @Test
    void defaultSocketTypeIsDatagram() {
        assertEquals(UnixSocketType.DATAGRAM, new SyslogAppender().getUnixSocketType());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void datagramByDefaultDeliversFrames() throws Exception {
        assumeDatagram();
        LoggerContext ctx = context();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("app.sock"))) {
            SyslogAppender a = unix(ctx, server.path());   // async writer thread, the default
            a.start();
            assertTrue(a.isStarted());
            Logger l = ctx.getLogger("t.Unix");
            l.addAppender(a);
            try {
                l.info("hello-dgram");
                l.warn("second-dgram");
                String m = server.poll(5000);
                assertNotNull(m);
                assertTrue(m.startsWith("<134>"), m);   // LOCAL0 (16) * 8 + INFO (6)
                assertTrue(m.contains("unixapp: hello-dgram"), m);
                assertFalse(m.endsWith("\n"), "datagrams are not newline framed");
                String m2 = server.poll(5000);
                assertNotNull(m2);
                assertTrue(m2.startsWith("<132>") && m2.endsWith("second-dgram"), m2);
            } finally {
                a.stop();
            }
            assertEquals(0, a.getDroppedCount());
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void localFramesOmitTheHostnameUnlessSendLocalNameIsSet() throws Exception {
        assumeDatagram();
        // the local socket format is <PRI>TIMESTAMP TAG: MSG; a hostname there would be taken as the tag
        String local = "<134>[A-Z][a-z]{2} [ \\d]\\d \\d{2}:\\d{2}:\\d{2} unixapp: ";
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("host.sock"))) {
            String m = sendOne(server, null, "no-host");
            assertTrue(m.matches(local + "no-host"), m);
            m = sendOne(server, true, "with-host");
            assertTrue(m.matches("<134>[A-Z][a-z]{2} [ \\d]\\d \\d{2}:\\d{2}:\\d{2} \\S+ unixapp: with-host"), m);
            assertFalse(m.matches(local + "with-host"), m);
        }
    }

    private String sendOne(UnixDatagramServer server, Boolean sendLocalName, String text) throws Exception {
        LoggerContext ctx = context();
        SyslogAppender a = unix(ctx, server.path());
        a.setSync(true);
        if (sendLocalName != null) {
            a.setSendLocalName(sendLocalName);
        }
        a.start();
        Logger l = ctx.getLogger("t.Host");
        l.addAppender(a);
        try {
            l.info(text);
            String m = server.poll(5000);
            assertNotNull(m);
            return m;
        } finally {
            a.stop();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rfc5424FramesCarryNoDefaultIdent() throws Exception {
        assumeDatagram();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("r5424.sock"))) {
            LoggerContext ctx = context();
            SyslogAppender a = unix(ctx, server.path());
            a.setSync(true);
            a.setRfc5424(true);
            a.start();
            Logger l = ctx.getLogger("t.R5424");
            l.addAppender(a);
            try {
                l.info("plain");
                String m = server.poll(5000);
                assertNotNull(m);
                assertTrue(m.matches("<134>1 \\S+ \\S+ unixapp - - - plain"), m);
                assertFalse(m.contains("java:"), m);
            } finally {
                a.stop();
            }
            ctx = context();
            a = unix(ctx, server.path());
            a.setSync(true);
            a.setRfc5424(true);
            StructuredDataParam sd = new StructuredDataParam();
            sd.setId("meta@1234");
            sd.addParam("k", "v");
            a.addStructuredData(sd);
            a.start();
            l = ctx.getLogger("t.R5424");
            l.addAppender(a);
            try {
                l.info("with-sd");
                String m = server.poll(5000);
                assertNotNull(m);
                assertTrue(m.matches("<134>1 \\S+ \\S+ unixapp - - \\[meta@1234 k=\"v\"\\] with-sd"), m);
                assertFalse(m.contains("java:"), m);
            } finally {
                a.stop();
            }
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void rfc5424OverDatagramWarnsButStarts() {
        assumeDatagram();
        LoggerContext ctx = context();
        SyslogAppender a = unix(ctx, dir.resolve("warn.sock"));
        a.setRfc5424(true);
        a.start();
        try {
            assertTrue(a.isStarted());
            assertEquals(1, SyslogAppenderFailureTest.statuses(ctx, Status.WARN, "do not parse RFC 5424").size());
        } finally {
            a.stop();
        }
        LoggerContext ctx2 = context();
        SyslogAppender b = unix(ctx2, dir.resolve("warn2.sock"));
        b.setRfc5424(true);
        b.setUnixSocketType(UnixSocketType.STREAM);
        b.start();
        try {
            assertTrue(b.isStarted());
            assertTrue(SyslogAppenderFailureTest.statuses(ctx2, Status.WARN, "RFC 5424").isEmpty(), "no warning for STREAM");
        } finally {
            b.stop();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void maxMessageLengthIsCappedForDatagramOnly() {
        assumeDatagram();
        LoggerContext ctx = context();
        SyslogAppender a = unix(ctx, dir.resolve("cap.sock"));
        a.setMaxMessageLength(100_000);
        a.start();
        try {
            assertTrue(a.isStarted());
            assertEquals(List.of("syslog appender [U]: maxMessageLength 100000 lowered to 65507 (unix datagram limit)"),
                    SyslogAppenderFailureTest.statuses(ctx, Status.WARN, "lowered to"));
        } finally {
            a.stop();
        }
        LoggerContext ctx2 = context();
        SyslogAppender b = unix(ctx2, dir.resolve("cap2.sock"));
        b.setMaxMessageLength(100_000);
        b.setUnixSocketType(UnixSocketType.STREAM);
        b.start();
        try {
            assertTrue(SyslogAppenderFailureTest.statuses(ctx2, Status.WARN, "lowered to").isEmpty(), "no cap for STREAM");
        } finally {
            b.stop();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void asyncStopClosesTheDatagramSocket() throws Exception {
        assumeDatagram();
        LoggerContext ctx = context();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("async.sock"))) {
            Logger l = ctx.getLogger("t.Async");
            asyncCycle(ctx, l, server);   // warm up
            int before = UnixDatagramTestSeam.openFdCount();
            for (int i = 0; i < 20; i++) {
                asyncCycle(ctx, l, server);
            }
            int after = UnixDatagramTestSeam.openFdCount();
            assertTrue(after - before <= 2, "fd count grew from " + before + " to " + after);
        }
    }

    private void asyncCycle(LoggerContext ctx, Logger l, UnixDatagramServer server) throws Exception {
        SyslogAppender a = unix(ctx, server.path());   // default async queue and writer thread
        a.start();
        l.addAppender(a);
        try {
            l.info("async-cycle");
            assertNotNull(server.poll(5000));
        } finally {
            l.detachAppender(a);
            a.stop();
        }
        assertFalse(a.writerThread().isAlive(), "writer thread ended");
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void streamTypeStillWorks() throws Exception {
        LoggerContext ctx = context();
        Path p = dir.resolve("stream.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(p));
            UnixDatagramTestSeam.forceUnavailable("stream does not need it");
            SyslogAppender a = unix(ctx, p);
            a.setUnixSocketType(UnixSocketType.STREAM);
            a.setSync(true);
            a.start();
            assertTrue(a.isStarted());
            Logger l = ctx.getLogger("t.Stream");
            l.addAppender(a);
            try {
                l.info("over-stream");
                try (SocketChannel c = server.accept();
                        BufferedReader r = new BufferedReader(new InputStreamReader(Channels.newInputStream(c), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    assertNotNull(line);
                    assertTrue(line.startsWith("<134>") && line.endsWith("unixapp: over-stream"), line);
                }
            } finally {
                a.stop();
            }
        }
    }

    @Test
    void unavailableDatagramLeavesAppenderStoppedWithError() {
        UnixDatagramTestSeam.forceUnavailable("forced reason");
        LoggerContext ctx = context();
        SyslogAppender a = unix(ctx, dir.resolve("never.sock"));
        a.start();
        assertFalse(a.isStarted());
        List<String> errors = SyslogAppenderFailureTest.statuses(ctx, Status.ERROR, "not started");
        assertEquals(List.of("syslog appender [U] not started: unix datagram sockets are unavailable: forced reason; "
                + "use unixSocketType STREAM, or UDP/TCP to 127.0.0.1"), errors);
    }

    @Test
    void nullSocketTypeIsAValidationError() {
        LoggerContext ctx = context();
        SyslogAppender a = unix(ctx, dir.resolve("null.sock"));
        a.setUnixSocketType(null);
        a.start();
        assertFalse(a.isStarted());
        assertEquals(1, SyslogAppenderFailureTest.statuses(ctx, Status.ERROR, "unixSocketType must not be null").size());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void receiverAbsentThenPresentReplaysBacklogInOrder() throws Exception {
        assumeDatagram();
        LoggerContext ctx = context();
        Path p = dir.resolve("late.sock");
        SyslogAppender a = unix(ctx, p);
        a.setSync(true);
        a.backoffInitialMs = 0;   // recovery is under test here, not the reconnect backoff
        a.start();
        Logger l = ctx.getLogger("t.Late");
        l.addAppender(a);
        try {
            l.info("e1");
            l.info("e2");
            l.info("e3");
            assertEquals(3, a.backlogSize());
            assertFalse(SyslogAppenderFailureTest.statuses(ctx, Status.ERROR, "ENOENT").isEmpty(), "outage reported with errno");
            try (UnixDatagramServer server = UnixDatagramServer.bind(p)) {
                l.info("e4");
                for (String e : new String[] { "e1", "e2", "e3", "e4" }) {
                    String m = server.poll(5000);
                    assertNotNull(m, "missing " + e);
                    assertTrue(m.endsWith("unixapp: " + e), "expected " + e + " got " + m);
                }
                assertNull(server.poll(200), "no duplicates");
                assertEquals(0, a.backlogSize());
            }
        } finally {
            a.stop();
        }
        assertEquals(0, a.getDroppedCount());
        assertFalse(SyslogAppenderFailureTest.statuses(ctx, Status.INFO, "recovered").isEmpty());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void stopClosesTheDatagramSocket() throws Exception {
        assumeDatagram();
        LoggerContext ctx = context();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("cycle.sock"))) {
            Logger l = ctx.getLogger("t.Cycle");
            cycle(ctx, l, server);   // warm up
            int before = UnixDatagramTestSeam.openFdCount();
            for (int i = 0; i < 30; i++) {
                cycle(ctx, l, server);
            }
            int after = UnixDatagramTestSeam.openFdCount();
            assertTrue(after - before <= 2, "fd count grew from " + before + " to " + after);
        }
    }

    private void cycle(LoggerContext ctx, Logger l, UnixDatagramServer server) throws Exception {
        SyslogAppender a = unix(ctx, server.path());
        a.setSync(true);
        a.start();
        l.addAppender(a);
        try {
            l.info("cycle");
            assertNotNull(server.poll(2000));
        } finally {
            l.detachAppender(a);
            a.stop();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void joranXmlSelectsProtocolAndSocketType() throws Exception {
        assumeDatagram();
        try (UnixDatagramServer server = UnixDatagramServer.bind(dir.resolve("xml.sock"))) {
            LoggerContext ctx = configure(xml(server.path(), "DATAGRAM"));
            try {
                assertTrue(ctx.getStatusManager().getCopyOfStatusList().stream().noneMatch(s -> s.getLevel() == Status.ERROR));
                SyslogAppender a = (SyslogAppender) ctx.getLogger("ROOT").getAppender("SYSLOG");
                assertNotNull(a);
                assertEquals(UnixSocketType.DATAGRAM, a.getUnixSocketType());
                ctx.getLogger("x.Xml").info("from-xml");
                String m = server.poll(5000);
                assertNotNull(m);
                assertTrue(m.startsWith("<134>") && m.endsWith("xmlapp: x.Xml from-xml"), m);
            } finally {
                ctx.stop();
            }
        }
    }

    @Test
    @EnabledOnOs({ OS.LINUX, OS.MAC })
    void joranXmlStreamSocketType() throws Exception {
        Path p = dir.resolve("xmlstream.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(p));
            LoggerContext ctx = configure(xml(p, "STREAM"));
            try {
                SyslogAppender a = (SyslogAppender) ctx.getLogger("ROOT").getAppender("SYSLOG");
                assertEquals(UnixSocketType.STREAM, a.getUnixSocketType());
                ctx.getLogger("x.Xml").info("from-xml-stream");
                try (SocketChannel c = server.accept();
                        BufferedReader r = new BufferedReader(new InputStreamReader(Channels.newInputStream(c), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    assertNotNull(line);
                    assertTrue(line.endsWith("xmlapp: x.Xml from-xml-stream"), line);
                }
            } finally {
                ctx.stop();
            }
        }
    }

    private static String xml(Path path, String type) {
        return """
                <configuration>
                  <appender name="SYSLOG" class="org.red5.logback.syslog.SyslogAppender">
                    <protocol>UNIX</protocol>
                    <unixSocketPath>%s</unixSocketPath>
                    <unixSocketType>%s</unixSocketType>
                    <facility>LOCAL0</facility>
                    <appName>xmlapp</appName>
                    <suffixPattern>%%logger %%msg</suffixPattern>
                  </appender>
                  <root level="INFO">
                    <appender-ref ref="SYSLOG"/>
                  </root>
                </configuration>
                """.formatted(path, type);
    }

    private static LoggerContext configure(String xml) throws Exception {
        LoggerContext ctx = context();
        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(ctx);
        jc.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return ctx;
    }
}
