package org.red5.logback.syslog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.Status;

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
            assertEquals(1, errorsFrom(ctx, bad), "the rejected appender records an error status");
            assertEquals(0, errorsFrom(ctx, good));
            good.stop();
            bad.stop();
        }
    }

    @Test
    void matchingIpSanIsAccepted(@TempDir Path dir) throws Exception {
        Path ks = TestKeystore.create(dir, "changeit");   // SAN dns:localhost,ip:127.0.0.1
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = tlsServer(15183, ks)) {
            SyslogAppender a = tlsAppender(ctx, "IP", "127.0.0.1", 15183, ks);
            a.start();
            Logger l = ctx.getLogger("t.Ip");
            l.addAppender(a);
            l.info("by-ip");
            String m = s.poll(5000);
            assertNotNull(m, "a certificate whose SAN matches the IP literal must be accepted");
            assertTrue(m.contains("by-ip"), m);
            a.stop();
        }
    }

    @Test
    void hostnameMismatchIsRejected(@TempDir Path dir) throws Exception {
        Path ks = TestKeystore.create(dir, "other.jks", "changeit", "dns:other.example");
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = tlsServer(15184, ks)) {
            SyslogAppender a = tlsAppender(ctx, "MISMATCH", "localhost", 15184, ks);   // trusted, but issued for other.example
            a.start();
            assertTrue(a.isStarted());
            Logger l = ctx.getLogger("t.Mismatch");
            l.addAppender(a);
            l.info("must-not-arrive");
            assertNull(s.poll(1000), "a certificate for another host name must be rejected");
            assertEquals(1, a.backlogSize(), "the rejected message waits in the backlog");
            assertEquals(1, errorsFrom(ctx, a), "the host name mismatch is reported");
            a.stop();
        }
    }

    @Test
    void hostnameMismatchIsAcceptedWhenVerificationIsDisabled(@TempDir Path dir) throws Exception {
        Path ks = TestKeystore.create(dir, "other.jks", "changeit", "dns:other.example");
        LoggerContext ctx = new LoggerContext();
        try (CapturingServer s = tlsServer(15185, ks)) {
            SyslogAppender a = tlsAppender(ctx, "NOVERIFY", "localhost", 15185, ks);
            a.setSslVerifyHostname(false);
            a.start();
            Logger l = ctx.getLogger("t.NoVerify");
            l.addAppender(a);
            l.info("insecure-ok");
            String m = s.poll(5000);
            assertNotNull(m, "with sslVerifyHostname=false a trusted certificate for another name is accepted");
            assertTrue(m.contains("insecure-ok"), m);
            a.stop();
        }
    }

    @Test
    void tlsConfiguredFromJoranXmlDelivers(@TempDir Path dir) throws Exception {
        Path ks = TestKeystore.create(dir, "changeit");
        Map<String, String> before = sslProps();
        String xml = """
                <configuration>
                  <appender name="SYSLOG" class="org.red5.logback.syslog.SyslogAppender">
                    <syslogHost>localhost</syslogHost>
                    <port>15186</port>
                    <protocol>TLS</protocol>
                    <suffixPattern>%msg</suffixPattern>
                    <sslTrustStore>STORE</sslTrustStore>
                    <sslTrustStorePassword>changeit</sslTrustStorePassword>
                    <sslVerifyHostname>true</sslVerifyHostname>
                  </appender>
                  <root level="INFO"><appender-ref ref="SYSLOG"/></root>
                </configuration>
                """.replace("STORE", ks.toString());
        try (CapturingServer s = tlsServer(15186, ks)) {
            LoggerContext ctx = new LoggerContext();
            ctx.setMDCAdapter(new LogbackMDCAdapter());
            JoranConfigurator jc = new JoranConfigurator();
            jc.setContext(ctx);
            jc.doConfigure(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            SyslogAppender a = (SyslogAppender) ctx.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("SYSLOG");
            assertNotNull(a);
            assertTrue(a.isStarted(), ctx.getStatusManager().getCopyOfStatusList().toString());
            ctx.getLogger("t.Xml").info("tls-from-xml");
            String m = s.poll(5000);
            assertNotNull(m, "TLS delivery configured from XML");
            assertTrue(m.contains("tls-from-xml"), m);
            ctx.stop();
            assertEquals(before, sslProps());
            assertTrue(ctx.getStatusManager().getCopyOfStatusList().stream().noneMatch(st -> st.getMessage().contains("changeit")),
                    "the store password must not appear in any status message");
        }
    }

    private static long errorsFrom(LoggerContext ctx, SyslogAppender a) {
        return ctx.getStatusManager().getCopyOfStatusList().stream()
                .filter(st -> st.getLevel() == Status.ERROR && st.getOrigin() == a).count();
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
