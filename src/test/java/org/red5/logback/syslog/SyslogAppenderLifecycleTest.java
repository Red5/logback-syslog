package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.red5.syslog.testsupport.CapturingServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

/** Start/stop without the static Syslog registry. Ports 15190-15199. */
class SyslogAppenderLifecycleTest {

    private static LoggerContext newContext() {
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        return ctx;
    }

    private static SyslogAppender appender(LoggerContext ctx, String name, Protocol protocol, int port) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setName(name);
        a.setSyslogHost("127.0.0.1");
        a.setPort(port);
        a.setProtocol(protocol);
        a.setSuffixPattern("%msg");
        return a;
    }

    @Test
    void appenderDoesNotUseTheStaticSyslogRegistry() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/red5/logback/syslog/SyslogAppender.java"));
        assertFalse(src.contains("import org.red5.syslog.Syslog;"), "the appender must not touch org.red5.syslog.Syslog");
        assertFalse(src.contains("Syslog.createInstance") || src.contains("Syslog.destroyInstance"));
    }

    @Test
    void stopWithEmptyQueueIsQuick() {
        for (boolean sync : new boolean[] { false, true }) {
            LoggerContext ctx = newContext();
            SyslogAppender a = appender(ctx, "Q", Protocol.UDP, 15192);
            a.setSync(sync);
            a.start();
            assertTrue(a.isStarted());
            long t0 = System.nanoTime();
            a.stop();
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            // the registry's destroyInstance slept 500 ms per stop; the async writer polls every 100 ms
            assertTrue(ms < 400, "sync=" + sync + " stop took " + ms + " ms");
        }
    }

    @Test
    void stopClosesTheTcpConnection() throws Exception {
        LoggerContext ctx = newContext();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            SyslogAppender a = appender(ctx, "EOF", Protocol.TCP, server.getLocalPort());
            a.setSync(true);
            a.start();
            Logger l = ctx.getLogger("t.Eof");
            l.addAppender(a);
            l.info("hello");
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(5000);
                BufferedReader in = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
                String line = in.readLine();
                assertNotNull(line);
                assertTrue(line.contains("hello"), line);
                a.stop();
                assertEquals(-1, in.read(), "after stop() the peer must see EOF");
            }
        }
    }

    @Test
    void repeatedStartStopAndSameNameInTwoContextsWork() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15191, null)) {
            LoggerContext c1 = newContext();
            LoggerContext c2 = newContext();
            SyslogAppender a1 = appender(c1, "SYSLOG", Protocol.UDP, 15191);
            SyslogAppender a2 = appender(c2, "SYSLOG", Protocol.UDP, 15191);
            Logger l1 = c1.getLogger("t.One");
            Logger l2 = c2.getLogger("t.Two");
            l1.addAppender(a1);
            l2.addAppender(a2);
            for (int cycle = 0; cycle < 3; cycle++) {
                a1.start();
                a2.start();
                assertTrue(a1.isStarted() && a2.isStarted(), "cycle " + cycle);
                l1.info("one-" + cycle);
                l2.info("two-" + cycle);
                a1.stop();
                a2.stop();
            }
            List<String> got = new ArrayList<>();
            String m;
            while ((m = s.poll(1000)) != null) {
                got.add(m);
            }
            for (int cycle = 0; cycle < 3; cycle++) {
                String one = "one-" + cycle;
                String two = "two-" + cycle;
                assertEquals(1, got.stream().filter(x -> x.endsWith(one)).count(), got.toString());
                assertEquals(1, got.stream().filter(x -> x.endsWith(two)).count(), got.toString());
            }
        }
    }
}
