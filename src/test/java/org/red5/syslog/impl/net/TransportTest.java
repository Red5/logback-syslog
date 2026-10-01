package org.red5.syslog.impl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.red5.syslog.Syslog;
import org.red5.syslog.SyslogIF;
import org.red5.syslog.impl.net.tcp.TCPNetSyslogConfig;
import org.red5.syslog.impl.net.tcp.ssl.SSLTCPNetSyslogConfig;
import org.red5.syslog.impl.net.udp.UDPNetSyslogConfig;
import org.red5.syslog.server.impl.net.tcp.ssl.SSLTCPNetSyslogServerConfigIF;
import org.red5.syslog.testsupport.CapturingServer;
import org.red5.syslog.testsupport.TestKeystore;

class TransportTest {

    private static final String[] SSL_PROPS = { "javax.net.ssl.keyStore", "javax.net.ssl.keyStorePassword",
            "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword" };

    private static String must(CapturingServer s, long ms) throws InterruptedException {
        String m = s.poll(ms);
        assertNotNull(m, "expected a message but none arrived");
        return m;
    }

    /** Polls (3s per message, no fixed sleeps) until the last received message satisfies the stop condition. */
    private static List<String> collectUntil(CapturingServer s, Predicate<String> last) throws InterruptedException {
        List<String> out = new ArrayList<>();
        String m;
        do {
            m = must(s, 3000);
            out.add(m);
        } while (!last.test(m));
        return out;
    }

    /** Joins split parts back into the original payload by stripping the "..." continuation markers. */
    private static String reassemble(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            // each part carries its own header; the server leaves the host name as the first token
            String s = p.substring(p.indexOf(' ') + 1);
            if (s.startsWith("...")) {
                s = s.substring(3);
            }
            if (s.endsWith("...")) {
                s = s.substring(0, s.length() - 3);
            }
            sb.append(s);
        }
        return sb.toString();
    }

    @Test
    void udpDelivers() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15141, null)) {
            UDPNetSyslogConfig c = new UDPNetSyslogConfig();
            c.setHost("127.0.0.1");
            c.setPort(15141);
            SyslogIF log = Syslog.createInstance("t-udp", c);
            try {
                log.info("udp-ok");
                assertTrue(must(s, 3000).contains("udp-ok"));
            } finally {
                Syslog.destroyInstance("t-udp");
            }
        }
    }

    @Test
    void tcpDeliversMultipleLinesInOrderAndSurvivesReconnect() throws Exception {
        try (CapturingServer s = CapturingServer.start("tcp", 15142, null)) {
            TCPNetSyslogConfig c = new TCPNetSyslogConfig();
            c.setHost("127.0.0.1");
            c.setPort(15142);
            SyslogIF log = Syslog.createInstance("t-tcp", c);
            try {
                log.info("one");
                log.info("two");
                assertTrue(must(s, 3000).contains("one"));
                assertTrue(must(s, 3000).contains("two"));
                log.flush(); // closes the connection; the next write opens a fresh one
                log.info("three");
                assertTrue(must(s, 3000).contains("three"));
            } finally {
                Syslog.destroyInstance("t-tcp");
            }
        }
    }

    @Test
    void oversizedUdpMessageIsTruncatedNotDropped() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15143, null)) {
            UDPNetSyslogConfig c = new UDPNetSyslogConfig();
            c.setHost("127.0.0.1");
            c.setPort(15143);
            c.setMaxMessageLength(200);
            c.setTruncateMessage(true);
            SyslogIF log = Syslog.createInstance("t-trunc", c);
            try {
                log.info("x".repeat(5000));
                String m = must(s, 3000);
                assertTrue(m.length() <= 200, "length " + m.length());
                assertTrue(m.length() > 100, "truncated message should still carry most of the budget");
                // the link is still usable afterwards
                log.info("after-trunc");
                assertTrue(must(s, 3000).contains("after-trunc"));
            } finally {
                Syslog.destroyInstance("t-trunc");
            }
        }
    }

    @Test
    void oversizedTcpMessageIsTruncatedAndConnectionSurvives() throws Exception {
        try (CapturingServer s = CapturingServer.start("tcp", 15145, null)) {
            TCPNetSyslogConfig c = new TCPNetSyslogConfig();
            c.setHost("127.0.0.1");
            c.setPort(15145);
            c.setMaxMessageLength(200);
            c.setTruncateMessage(true);
            SyslogIF log = Syslog.createInstance("t-tcp-trunc", c);
            try {
                log.info("x".repeat(5000));
                log.info("after-trunc");
                String m = must(s, 3000);
                assertTrue(m.length() <= 200, "length " + m.length());
                assertTrue(m.length() > 100);
                assertTrue(must(s, 3000).contains("after-trunc"));
            } finally {
                Syslog.destroyInstance("t-tcp-trunc");
            }
        }
    }

    @Test
    void oversizedUdpMessageIsSplitWhenNotTruncating() throws Exception {
        try (CapturingServer s = CapturingServer.start("udp", 15146, null)) {
            UDPNetSyslogConfig c = new UDPNetSyslogConfig();
            c.setHost("127.0.0.1");
            c.setPort(15146);
            c.setMaxMessageLength(200);
            c.setTruncateMessage(false);
            SyslogIF log = Syslog.createInstance("t-split-udp", c);
            try {
                log.info("y".repeat(600));
                List<String> parts = collectUntil(s, m -> m.endsWith("y"));
                assertTrue(parts.size() >= 3, "parts " + parts.size());
                parts.forEach(p -> assertTrue(p.length() <= 200, "part length " + p.length()));
                assertEquals("y".repeat(600), reassemble(parts));
            } finally {
                Syslog.destroyInstance("t-split-udp");
            }
        }
    }

    @Test
    void oversizedTcpMessageIsSplitInOrderWithoutDroppingConnection() throws Exception {
        try (CapturingServer s = CapturingServer.start("tcp", 15147, null)) {
            TCPNetSyslogConfig c = new TCPNetSyslogConfig();
            c.setHost("127.0.0.1");
            c.setPort(15147);
            c.setMaxMessageLength(200);
            c.setTruncateMessage(false);
            SyslogIF log = Syslog.createInstance("t-split-tcp", c);
            try {
                String payload = "0123456789".repeat(60);
                log.info(payload);
                log.info("after-split");
                List<String> parts = collectUntil(s, m -> m.contains("after-split"));
                assertTrue(parts.size() >= 4, "parts " + parts.size());
                assertTrue(parts.get(parts.size() - 1).contains("after-split"), "parts " + parts);
                parts.forEach(p -> assertTrue(p.length() <= 200, "part length " + p.length()));
                assertEquals(payload, reassemble(parts.subList(0, parts.size() - 1)));
            } finally {
                Syslog.destroyInstance("t-split-tcp");
            }
        }
    }

    @Test
    void tlsDeliversWithoutTouchingJvmWideSslProperties(@TempDir Path dir) throws Exception {
        Path ks = TestKeystore.create(dir, "changeit");
        Map<String, String> before = sslProps();
        try (CapturingServer s = CapturingServer.start("ssl", 15144, cfg -> {
            SSLTCPNetSyslogServerConfigIF ssl = (SSLTCPNetSyslogServerConfigIF) cfg;
            ssl.setKeyStore(ks.toString());
            ssl.setKeyStorePassword("changeit");
        })) {
            assertEquals(before, sslProps(), "the TLS server must not set javax.net.ssl.* properties");
            SSLTCPNetSyslogConfig c = new SSLTCPNetSyslogConfig();
            c.setHost("127.0.0.1");
            c.setPort(15144);
            c.setTrustStore(ks.toString());
            c.setTrustStorePassword("changeit");
            SyslogIF log = Syslog.createInstance("t-tls", c);
            try {
                assertEquals(before, sslProps(), "the TLS client must not set javax.net.ssl.* properties");
                log.info("tls-ok");
                assertTrue(must(s, 5000).contains("tls-ok"));
            } finally {
                Syslog.destroyInstance("t-tls");
            }
        }
        assertEquals(before, sslProps());
    }

    private static Map<String, String> sslProps() {
        Map<String, String> m = new HashMap<>();
        for (String k : SSL_PROPS) {
            m.put(k, System.getProperty(k));
        }
        return m;
    }
}
