package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.red5.syslog.server.impl.net.tcp.ssl.SSLTCPNetSyslogServerConfigIF;
import org.red5.syslog.testsupport.CapturingServer;
import org.red5.syslog.testsupport.TestKeystore;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

/** Appender-level TLS: private SSLContext per appender, no JVM-wide javax.net.ssl.* state. Ports 15180-15189. */
class SyslogAppenderTlsTest {

    private static final String[] SSL_PROPS = { "javax.net.ssl.keyStore", "javax.net.ssl.keyStorePassword",
            "javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword" };

    private static Map<String, String> sslProps() {
        Map<String, String> m = new HashMap<>();
        for (String k : SSL_PROPS) {
            m.put(k, System.getProperty(k));
        }
        return m;
    }

    private static CapturingServer tlsServer(int port, Path keyStore) throws Exception {
        return CapturingServer.start("ssl", port, cfg -> {
            SSLTCPNetSyslogServerConfigIF ssl = (SSLTCPNetSyslogServerConfigIF) cfg;
            ssl.setKeyStore(keyStore.toString());
            ssl.setKeyStorePassword("changeit");
        });
    }

    static SyslogAppender tlsAppender(LoggerContext ctx, String name, String host, int port, Path trustStore) {
        SyslogAppender a = new SyslogAppender();
        a.setContext(ctx);
        a.setName(name);
        a.setSyslogHost(host);
        a.setPort(port);
        a.setProtocol(Protocol.TLS);
        a.setSuffixPattern("%msg");
        a.setSslTrustStore(trustStore.toString());
        a.setSslTrustStorePassword("changeit");
        a.setSync(true);
        return a;
    }

    private void deliverAndCheckProps(Path dir, int port) throws Exception {
        Path ks = TestKeystore.create(dir, "changeit");
        Map<String, String> before = sslProps();
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = tlsServer(port, ks)) {
            SyslogAppender a = tlsAppender(ctx, "TLS", "localhost", port, ks);
            a.start();
            assertTrue(a.isStarted());
            assertEquals(before, sslProps(), "start() must not set javax.net.ssl.* properties");
            Logger l = ctx.getLogger("t.Tls");
            l.addAppender(a);
            l.info("tls-appender-ok");
            String m = s.poll(5000);
            assertNotNull(m, "TLS delivery");
            assertTrue(m.contains("tls-appender-ok"), m);
            a.stop();
        }
        assertEquals(before, sslProps(), "stop() must leave javax.net.ssl.* properties unchanged");
    }

    @Test
    void systemPropertiesStayUnsetWhenUnset(@TempDir Path dir) throws Exception {
        Map<String, String> saved = sslProps();
        try {
            for (String k : SSL_PROPS) {
                System.clearProperty(k);
            }
            deliverAndCheckProps(dir, 15180);
            for (String k : SSL_PROPS) {
                assertNull(System.getProperty(k), k);
            }
        } finally {
            restore(saved);
        }
    }

    @Test
    void presetSystemPropertiesAreNotOverwritten(@TempDir Path dir) throws Exception {
        Map<String, String> saved = sslProps();
        try {
            for (String k : SSL_PROPS) {
                System.setProperty(k, "sentinel-" + k);
            }
            deliverAndCheckProps(dir, 15181);
            for (String k : SSL_PROPS) {
                assertEquals("sentinel-" + k, System.getProperty(k), k);
            }
        } finally {
            restore(saved);
        }
    }

    @Test
    void twoAppendersWithDifferentTrustStoresCoexist(@TempDir Path dir) throws Exception {
        Path serverKs = TestKeystore.create(dir, "server.jks", "changeit", "dns:localhost,ip:127.0.0.1");
        Path otherKs = TestKeystore.create(dir, "other.jks", "changeit", "dns:localhost,ip:127.0.0.1");
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = tlsServer(15182, serverKs)) {
            SyslogAppender good = tlsAppender(ctx, "GOOD", "localhost", 15182, serverKs);
            SyslogAppender bad = tlsAppender(ctx, "BAD", "localhost", 15182, otherKs);   // trusts a different certificate
            good.start();
            bad.start();
            assertTrue(good.isStarted());
            assertTrue(bad.isStarted());
            Logger lg = ctx.getLogger("t.Good");
            lg.setAdditive(false);
            lg.addAppender(good);
            Logger lb = ctx.getLogger("t.Bad");
            lb.setAdditive(false);
            lb.addAppender(bad);
            lb.info("from-bad");
            lg.info("from-good");
            String m = s.poll(5000);
            assertNotNull(m, "the appender trusting the server must deliver");
            assertTrue(m.contains("from-good"), m);
            assertNull(s.poll(500), "the appender with the wrong trust store must not deliver");
            assertEquals(1, bad.backlogSize(), "the rejected message waits in the backlog");
            good.stop();
            bad.stop();
        }
    }

    private static void restore(Map<String, String> saved) {
        saved.forEach((k, v) -> {
            if (v == null) {
                System.clearProperty(k);
            } else {
                System.setProperty(k, v);
            }
        });
    }
}
